package org.apache.fineract.baseteller.foreigncurrency;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.Cashier;
import org.apache.fineract.baseteller.service.CashExchangeInventory;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

class FxTellerPreviewTest {
  private FxTellerService service;
  private FxRateService rates;
  private CashExchangeInventory inventory;
  private JdbcTemplate jdbc;
  private AppUser actor;
  private FxData.RateDraft draft;
  private boolean open, active;

  @BeforeEach
  void setup() {
    ThreadLocalContextUtil.setTenant(
        new FineractPlatformTenant(1L, "fx-test", "FX test", "UTC", null));
    ThreadLocalContextUtil.setBusinessDates(
        new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 10, 21))));
    open = true;
    active = true;
    actor = mock(AppUser.class);
    var office = mock(Office.class);
    when(actor.getOffice()).thenReturn(office);
    when(office.getHierarchy()).thenReturn(".1.");
    when(actor.getId()).thenReturn(1L);
    when(actor.getStaffId()).thenReturn(10L);
    var security = mock(PlatformSecurityContext.class);
    when(security.authenticatedUser()).thenReturn(actor);
    rates = mock(FxRateService.class);
    inventory = mock(CashExchangeInventory.class);
    draft =
        new FxData.RateDraft(
            1L,
            1L,
            "Branch",
            "2026-10-21",
            "USD",
            "EUR",
            new BigDecimal("2.34"),
            BigDecimal.ZERO,
            BigDecimal.ONE,
            1,
            "DRAFT",
            1L,
            "2026-10-21T00:00:00Z");
    when(rates.currency("USD")).thenReturn(new FxData.Currency("USD", "Dollar", 2));
    when(rates.currency("EUR")).thenReturn(new FxData.Currency("EUR", "Euro", 2));
    when(rates.draft(eq(1L), eq(actor))).thenReturn(draft);
    when(rates.latest(eq(1L), any(), eq("EUR"))).thenReturn(draft);
    when(inventory.quantities(eq(20L), anyString(), any()))
        .thenReturn(Map.of("usd-note", 5L, "eur-note", 5L));
    jdbc =
        mock(
            JdbcTemplate.class,
            call -> {
              if (call.getMethod().getName().equals("query")) {
                String sql = call.getArgument(0);
                if (sql.contains("FROM m_cashiers c"))
                  return open
                      ? List.of(new Cashier(20L, 10L, "Teller", 2L, "Window", 1L, "Branch"))
                      : List.of();
                if (sql.contains("FROM m_client c"))
                  return List.of(new FxData.Customer(1L, "0001", "Synthetic", 1L, active));
                if (sql.contains("FROM m_service_payment_denomination")) {
                  // Exercise the service mapper against synthetic catalog metadata, not invented
                  // balances.
                  var rs = mock(java.sql.ResultSet.class);
                  String currency = call.getArgument(2);
                  when(rs.getString(1))
                      .thenReturn(currency.equals("USD") ? "usd-note" : "eur-note");
                  when(rs.getString(2)).thenReturn("NOTE");
                  when(rs.getBigDecimal(3)).thenReturn(new BigDecimal("100"));
                  org.springframework.jdbc.core.RowMapper<?> mapper = call.getArgument(1);
                  return List.of(mapper.mapRow(rs, 0));
                }
              }
              if (call.getMethod().getName().equals("queryForObject")) return 1L;
              return RETURNS_DEFAULTS.answer(call);
            });
    service =
        new FxTellerService(
            security, jdbc, rates, inventory, mock(ClientReadPlatformService.class));
  }

  @AfterEach
  void cleanup() {
    ThreadLocalContextUtil.reset();
  }

  private FxData.TradeRequest request() {
    return FxValidation.trade(FxValidationTest.TRADE);
  }

  @Test
  void contextAndDenominationsUseTheAuthenticatedDrawerAndPreserveCurrencyMetadata() {
    when(rates.currencies())
        .thenReturn(
            List.of(
                new FxData.Currency("USD", "Dollar", 2), new FxData.Currency("EUR", "Euro", 2)));
    when(rates.currentDrafts(eq(1L), any(), eq(actor))).thenReturn(List.of(draft));
    var context = service.context();
    assertEquals(20L, context.cashier().cashierId());
    assertEquals(1L, context.cashier().officeId());
    assertNull(context.nationalCurrency());
    assertEquals(1, context.rateDrafts().size());
    assertFalse(context.capabilities().executionEnabled());
    var denominations = service.denominations("USD");
    assertEquals("USD", denominations.getFirst().currencyCode());
    assertEquals(5L, denominations.getFirst().availableQuantity());
    verify(inventory).quantities(20L, "USD", LocalDate.of(2026, 10, 21));
  }

  @Test
  void saleAndPurchaseHaveOppositeCashDirectionsAndNoInventedValuation() {
    for (var op : FxData.Operation.values()) {
      var result = service.preview(op, request());
      boolean sale = op == FxData.Operation.SALE;
      assertEquals(
          sale ? FxData.Direction.OUTFLOW : FxData.Direction.INFLOW, result.usdCash().direction());
      assertEquals(
          sale ? FxData.Direction.INFLOW : FxData.Direction.OUTFLOW,
          result.quoteCash().direction());
      assertEquals(new BigDecimal("100"), result.usdCash().denominationTotal());
      assertEquals(new BigDecimal("200"), result.quoteCash().denominationTotal());
      assertFalse(result.executable());
      assertFalse(result.valuationValidated());
      assertNull(result.appliedRate());
      assertNull(result.calculatedNationalAmount());
      assertNull(result.nationalCurrency());
      assertFalse(result.capabilities().checksSupported());
      assertFalse(result.capabilities().vouchersSupported());
    }
    assertTrue(
        mockingDetails(jdbc).getInvocations().stream()
            .allMatch(i -> i.getMethod().getName().startsWith("query")));
  }

  @Test
  void insufficientOutgoingDollarsAndNationalCandidateCashAreRejected() {
    when(inventory.quantities(eq(20L), anyString(), any())).thenReturn(Map.of());
    for (var op : FxData.Operation.values())
      assertThrows(GeneralPlatformDomainRuleException.class, () -> service.preview(op, request()));
  }

  @Test
  void staleDraftClosedSessionInactiveCustomerAndCurrencyMismatchAreRejected() {
    when(rates.latest(eq(1L), any(), eq("EUR"))).thenReturn(null);
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> service.preview(FxData.Operation.SALE, request()));
    when(rates.latest(eq(1L), any(), eq("EUR"))).thenReturn(draft);
    open = false;
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> service.preview(FxData.Operation.SALE, request()));
    open = true;
    active = false;
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> service.preview(FxData.Operation.SALE, request()));
    active = true;
    var r = request();
    var wrong =
        new FxData.TradeRequest(
            r.clientId(),
            r.identifierId(),
            r.reference(),
            r.quoteCurrency(),
            r.usdAmount(),
            r.rateDraftId(),
            r.rateVersion(),
            r.quoteDenominations(),
            r.usdDenominations());
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> service.preview(FxData.Operation.SALE, wrong));
  }

  @Test
  void purchaseRequiresAnExistingIdentifierAndBothUsdTotalsAndPrecisionAreEnforced() {
    var r = request();
    for (var invalid :
        List.of(
            new FxData.TradeRequest(
                r.clientId(),
                null,
                r.reference(),
                r.quoteCurrency(),
                r.usdAmount(),
                r.rateDraftId(),
                r.rateVersion(),
                r.usdDenominations(),
                r.quoteDenominations()),
            new FxData.TradeRequest(
                r.clientId(),
                r.identifierId(),
                r.reference(),
                r.quoteCurrency(),
                new BigDecimal("99"),
                r.rateDraftId(),
                r.rateVersion(),
                r.usdDenominations(),
                r.quoteDenominations()),
            new FxData.TradeRequest(
                r.clientId(),
                r.identifierId(),
                r.reference(),
                r.quoteCurrency(),
                new BigDecimal("100.001"),
                r.rateDraftId(),
                r.rateVersion(),
                r.usdDenominations(),
                r.quoteDenominations())))
      assertThrows(
          GeneralPlatformDomainRuleException.class,
          () -> service.preview(FxData.Operation.PURCHASE, invalid));
  }
}
