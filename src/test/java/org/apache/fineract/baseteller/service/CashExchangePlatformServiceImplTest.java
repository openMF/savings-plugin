package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.baseteller.data.CashExchangeCurrencyData;
import org.apache.fineract.baseteller.data.CashExchangeDenominationData;
import org.apache.fineract.baseteller.data.CashExchangeQuantityData;
import org.apache.fineract.baseteller.data.CashExchangeRequest;
import org.apache.fineract.baseteller.data.CashExchangeTellerData;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@SuppressWarnings({"unchecked", "rawtypes"})
class CashExchangePlatformServiceImplTest {
  private JdbcTemplate jdbc;
  private NamedParameterJdbcTemplate named;
  private CashExchangeInventory inventory;
  private AppUser user;
  private CashExchangePlatformServiceImpl service;

  @BeforeEach
  void setup() {
    var dates = new HashMap<BusinessDateType, LocalDate>();
    dates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 10, 8));
    ThreadLocalContextUtil.setBusinessDates(dates);
    jdbc = mock(JdbcTemplate.class);
    named = mock(NamedParameterJdbcTemplate.class);
    inventory = mock(CashExchangeInventory.class);
    user = mock(AppUser.class);
    Office office = mock(Office.class);
    when(office.getHierarchy()).thenReturn(".1.");
    when(user.getOffice()).thenReturn(office);
    var security = mock(PlatformSecurityContext.class);
    when(security.authenticatedUser()).thenReturn(user);
    service = new CashExchangePlatformServiceImpl(jdbc, named, security, inventory);
    when(named.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenReturn(List.of(new CashExchangeTellerData(10L, 1L, "cashier", "Cashier", 1L)));
    when(jdbc.query(contains("m_organisation_currency"), any(RowMapper.class)))
        .thenReturn(List.of(new CashExchangeCurrencyData("EUR", "Euro", 2)));
    when(jdbc.query(contains("m_service_payment_denomination"), any(RowMapper.class), eq("EUR")))
        .thenReturn(
            List.of(
                new CashExchangeDenominationData(
                    "large", "BANKNOTE", new BigDecimal("500"), 0L, BigDecimal.ZERO),
                new CashExchangeDenominationData(
                    "small", "BANKNOTE", new BigDecimal("100"), 0L, BigDecimal.ZERO)));
    when(inventory.quantities(eq(10L), eq("EUR"), any()))
        .thenReturn(Map.of("large", 1L, "small", 20L));
  }

  private CashExchangeRequest request(long receive, long deliver) {
    return new CashExchangeRequest(
        10L,
        "EUR",
        List.of(new CashExchangeQuantityData("large", receive)),
        List.of(new CashExchangeQuantityData("small", deliver)),
        "unit-key");
  }

  @Test
  void previewCalculatesAuthoritativeTotalsAndDelegatesPermission() {
    var p = service.preview(request(2, 10));
    assertEquals(0, new BigDecimal("1000").compareTo(p.receivedAmount()));
    assertEquals(0, p.receivedAmount().compareTo(p.deliveredAmount()));
    assertEquals(BigDecimal.ZERO, p.netMonetaryEffect());
    verify(user).validateHasCreatePermission("BASE_TELLER_CASH_EXCHANGE");
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void insufficientDenominationCannotUseReceivedCashToSatisfyDelivery() {
    when(inventory.quantities(eq(10L), eq("EUR"), any()))
        .thenReturn(Map.of("large", 1L, "small", 9L));
    assertThrows(RuntimeException.class, () -> service.preview(request(2, 10)));
  }

  @Test
  void mismatchedTotalsFailBeforeInventoryRead() {
    assertThrows(RuntimeException.class, () -> service.preview(request(2, 9)));
    verifyNoInteractions(inventory);
  }

  @Test
  void otherCurrencyIsRejected() {
    var r = request(2, 10);
    assertThrows(
        RuntimeException.class,
        () ->
            service.preview(
                new CashExchangeRequest(
                    10L, "USD", r.receivedDenominations(), r.deliveredDenominations(), "key")));
  }

  @Test
  void unknownOrDisabledDenominationIsRejected() {
    var r = request(2, 10);
    assertThrows(
        RuntimeException.class,
        () ->
            service.preview(
                new CashExchangeRequest(
                    10L,
                    "EUR",
                    List.of(new CashExchangeQuantityData("missing", 2L)),
                    r.deliveredDenominations(),
                    "key")));
  }

  @Test
  void invalidCurrencyPrecisionIsRejected() {
    when(jdbc.query(contains("m_service_payment_denomination"), any(RowMapper.class), eq("EUR")))
        .thenReturn(
            List.of(
                new CashExchangeDenominationData(
                    "small", "COIN", new BigDecimal("0.001"), 0L, BigDecimal.ZERO)));
    assertThrows(RuntimeException.class, () -> service.preview(request(2, 10)));
  }

  @Test
  void unauthorizedDrawerIsRejectedBeforeInventoryAccess() {
    assertThrows(
        RuntimeException.class,
        () ->
            service.preview(
                new CashExchangeRequest(
                    11L,
                    "EUR",
                    request(2, 10).receivedDenominations(),
                    request(2, 10).deliveredDenominations(),
                    "key")));
    verifyNoInteractions(inventory);
  }

  @Test
  void contextOnlyUsesReadPermission() {
    service.context();
    verify(user).validateHasReadPermission("BASE_TELLER_CASH_EXCHANGE");
  }

  @Test
  void createPermissionIsRequiredBeforePersistence() {
    doThrow(new IllegalStateException("denied"))
        .when(user)
        .validateHasCreatePermission(anyString());
    assertThrows(IllegalStateException.class, () -> service.create(request(2, 10)));
    verifyNoInteractions(jdbc, named, inventory);
  }

  @Test
  void receiptHasDedicatedReprintPermission() {
    doThrow(new IllegalStateException("denied"))
        .when(user)
        .validateHasPermissionTo("REPRINT_BASE_TELLER_CASH_EXCHANGE");
    assertThrows(IllegalStateException.class, () -> service.receipt(1L));
    verifyNoInteractions(jdbc, named, inventory);
  }

  @Test
  void preservesConfiguredNoteTypeWithoutCreatingASecondTypeSystem() {
    when(jdbc.query(contains("m_service_payment_denomination"), any(RowMapper.class), eq("EUR")))
        .thenReturn(
            List.of(
                new CashExchangeDenominationData(
                    "large", "NOTE", new BigDecimal("500"), 0L, BigDecimal.ZERO),
                new CashExchangeDenominationData(
                    "small", "NOTE", new BigDecimal("100"), 0L, BigDecimal.ZERO)));
    assertEquals("NOTE", service.preview(request(2, 10)).receivedDenominations().get(0).type());
  }
}
