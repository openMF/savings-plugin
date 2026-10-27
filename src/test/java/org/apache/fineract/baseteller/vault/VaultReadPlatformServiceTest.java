/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.baseteller.data.CashInventoryContextData;
import org.apache.fineract.baseteller.data.CashInventoryCurrencyData;
import org.apache.fineract.baseteller.data.CashInventoryCustodianData;
import org.apache.fineract.baseteller.data.CashInventoryCustodianType;
import org.apache.fineract.baseteller.service.CashInventoryReadPlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformInternalServerException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

class VaultReadPlatformServiceTest {
  private final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
  private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
  private final CashInventoryReadPlatformService inventory =
      mock(CashInventoryReadPlatformService.class);
  private final AppUser user = mock(AppUser.class);
  private final Office office = mock(Office.class);
  private VaultReadPlatformService service;
  private final List<Map<String, Object>> catalog = new ArrayList<>();
  private List<CashInventoryCurrencyData> currencies;
  private List<CashInventoryCustodianData> custodians;
  private boolean officeExists;
  private String hierarchy;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    ThreadLocalContextUtil.setTenant(mock(FineractPlatformTenant.class));
    var dates = new HashMap<BusinessDateType, LocalDate>();
    dates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 10, 26));
    ThreadLocalContextUtil.setBusinessDates(dates);
    when(security.authenticatedUser()).thenReturn(user);
    when(user.getOffice()).thenReturn(office);
    when(office.getId()).thenReturn(8L);
    when(office.getHierarchy()).thenReturn(".1.8.");
    hierarchy = ".1.8.";
    officeExists = true;
    currencies = List.of(new CashInventoryCurrencyData("USD", "US Dollar", 2));
    custodians =
        List.of(
            new CashInventoryCustodianData(
                "VAULT:8", CashInventoryCustodianType.VAULT, 8L, "8", "Safe/Vault - Branch"));
    when(inventory.context())
        .thenAnswer(i -> new CashInventoryContextData(custodians, List.of(), currencies));
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              RowMapper<Object> mapper = call.getArgument(2);
              List<Map<String, Object>> rows;
              if (sql.contains("FROM m_office")) {
                rows =
                    officeExists
                        ? List.of(Map.of("id", 8L, "name", "Branch", "hierarchy", hierarchy))
                        : List.of();
              } else {
                String requested = (String) ((Map<?, ?>) call.getArgument(1)).get("currency");
                rows =
                    catalog.stream().filter(r -> requested.equals(r.get("currency_code"))).toList();
              }
              var result = new ArrayList<>();
              for (var row : rows) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong(anyString()))
                    .thenAnswer(i -> ((Number) row.get(i.getArgument(0))).longValue());
                when(rs.getString(anyString())).thenAnswer(i -> row.get(i.getArgument(0)));
                when(rs.getBigDecimal(anyString())).thenAnswer(i -> row.get(i.getArgument(0)));
                result.add(mapper.mapRow(rs, result.size()));
              }
              return result;
            });
    service = new VaultReadPlatformService(security, jdbc, inventory);
  }

  @AfterEach
  void clearThread() {
    ThreadLocalContextUtil.reset();
  }

  @Test
  void authorizedContextUsesExistingVaultAndNeverClaimsDepositReadiness() {
    var result = service.context(null);
    assertEquals(8L, result.branch().id());
    assertEquals("VAULT:8", result.vault().key());
    assertEquals("Safe/Vault - Branch", result.vault().name());
    assertEquals(VaultData.Status.UNKNOWN, result.vault().status());
    assertEquals(LocalDate.of(2026, 10, 26), result.businessDate());
    assertTrue(result.capabilities().viewContext());
    assertTrue(result.capabilities().viewDenominations());
    assertTrue(result.capabilities().previewDeposit());
    assertFalse(result.capabilities().executeDeposit());
    assertFalse(result.readiness().depositReady());
    assertTrue(result.readiness().limitations().contains("DENOMINATIONS_UNAVAILABLE:USD"));
    verify(user).validateHasPermissionTo(VaultReadPlatformService.PERMISSION);
    verify(inventory, never()).inventory(any(), any(), any(), anyBoolean());
  }

  @Test
  void unauthorizedAndAnonymousRequestsCannotQueryConfiguration() {
    doThrow(new AccessDeniedException("denied")).when(user).validateHasPermissionTo(anyString());
    assertThrows(AccessDeniedException.class, () -> service.context(null));
    verifyNoInteractions(jdbc, inventory);
    when(security.authenticatedUser())
        .thenThrow(new AuthenticationCredentialsNotFoundException("required"));
    assertThrows(AuthenticationCredentialsNotFoundException.class, () -> service.currencies(null));
    verifyNoInteractions(jdbc, inventory);
  }

  @Test
  void missingTenantCannotFallBackToDefaultDatabase() {
    ThreadLocalContextUtil.clearTenant();
    assertThrows(PlatformInternalServerException.class, () -> service.context(null));
    verifyNoInteractions(jdbc, inventory);
  }

  @Test
  void missingBusinessDateFailsClosedWithoutInventingToday() {
    ThreadLocalContextUtil.setBusinessDates(new HashMap<>());
    var error = assertThrows(PlatformInternalServerException.class, () -> service.context(null));
    assertTrue(error.getGlobalisationMessageCode().endsWith("business.date.unavailable"));
    verifyNoInteractions(jdbc, inventory);
  }

  @Test
  void unknownOfficeIsNotFound() {
    officeExists = false;
    assertThrows(VaultNotFoundException.class, () -> service.context("999"));
    verifyNoInteractions(inventory);
  }

  @Test
  void crossBranchDeniedBeforeCatalogOrInventoryLookup() {
    hierarchy = ".1.9.";
    assertThrows(AccessDeniedException.class, () -> service.denominations("9", "USD"));
    verifyNoInteractions(inventory);
  }

  @Test
  void cashierCannotSubstituteATellerDrawerForAVault() {
    custodians =
        List.of(
            new CashInventoryCustodianData(
                "TELLER:8", CashInventoryCustodianType.TELLER, 8L, "8", "Teller"));
    assertThrows(AccessDeniedException.class, () -> service.context(null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "0", "-1", "1.5", "abc", "9223372036854775808", "1 OR 1=1"})
  void invalidOfficeQueryIsStructuredValidation(String value) {
    var error =
        assertThrows(PlatformApiDataValidationException.class, () -> service.context(value));
    assertEquals("officeId", error.getErrors().getFirst().getParameterName());
    verifyNoInteractions(jdbc, inventory);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"US", "USDD", "123", "U$D", "ZZZ"})
  void invalidOrUnsupportedCurrencyRejected(String code) {
    assertThrows(PlatformApiDataValidationException.class, () -> service.denominations(null, code));
  }

  @Test
  void tenantCurrencyConfigurationIsReturnedWithoutAssumingNationalCurrency() {
    currencies = List.of(new CashInventoryCurrencyData("JPY", "Yen", 0));
    var result = service.currencies(null);
    assertEquals(List.of(new VaultData.Currency("JPY", "Yen", 0)), result.currencies());
    assertEquals(VaultData.Status.AVAILABLE, result.status());
    assertThrows(
        PlatformApiDataValidationException.class, () -> service.denominations(null, "USD"));
  }

  @Test
  void missingCurrencyConfigurationIsExplicitlyUnavailable() {
    currencies = List.of();
    assertEquals(VaultData.Status.UNAVAILABLE, service.currencies(null).status());
    assertTrue(service.context(null).readiness().limitations().contains("CURRENCIES_UNAVAILABLE"));
  }

  @Test
  void catalogNormalizesBanknotesSeparatesCurrenciesAndPreservesDecimals() {
    add(1, "USD-note", "USD", "100.000001", "BILL");
    add(2, "USD-coin", "USD", "0.010000", "COIN");
    add(3, "JPY-note", "JPY", "1000.000000", "NOTE");
    var result = service.denominations(null, " usd ");
    assertEquals(2, result.denominations().size());
    assertEquals(VaultData.DenominationType.BANKNOTE, result.denominations().getFirst().type());
    assertEquals(VaultData.DenominationType.COIN, result.denominations().getLast().type());
    assertEquals(new BigDecimal("100.000001"), result.denominations().getFirst().value());
    String json = new Gson().toJson(result);
    assertTrue(json.contains("100.000001"));
    assertTrue(json.contains("\"denominationId\":\"USD-note\""));
    assertFalse(json.contains("JPY"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"BILL", "NOTE", "BANKNOTE", "banknote"})
  void allEstablishedBanknoteAliasesAreNormalized(String type) {
    add(1, "note", "USD", "10", type);
    assertEquals(
        VaultData.DenominationType.BANKNOTE,
        service.denominations(null, "USD").denominations().getFirst().type());
  }

  @Test
  void emptyCatalogIsUnavailableAndDoesNotCreateZeroBalances() {
    assertEquals(VaultData.Status.UNAVAILABLE, service.denominations(null, "USD").status());
    when(inventory.inventory("VAULT:8", "CASH", "USD", false)).thenReturn(List.of());
    var result = service.inventory(null, "USD");
    assertEquals(VaultData.Status.UNAVAILABLE, result.status());
    assertTrue(result.balances().isEmpty());
  }

  @Test
  void recordedInventoryIsDelegatedWithoutRecalculatingOrInitializingCash() {
    var recorded =
        new org.apache.fineract.baseteller.data.CashInventoryData(
            "VAULT:8",
            CashInventoryCustodianType.VAULT,
            8L,
            null,
            "8",
            "Vault",
            org.apache.fineract.baseteller.data.CashInventoryType.CASH,
            "USD",
            2,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            new BigDecimal("40.00"),
            BigDecimal.ZERO,
            new BigDecimal("60.00"),
            null,
            null,
            java.time.OffsetDateTime.parse("2026-10-26T10:00:00Z"));
    when(inventory.inventory("VAULT:8", "CASH", "USD", false)).thenReturn(List.of(recorded));
    var result = service.inventory(null, "USD");
    assertEquals(VaultData.Status.AVAILABLE, result.status());
    assertSame(recorded, result.balances().getFirst());
    verify(inventory).inventory("VAULT:8", "CASH", "USD", false);
    verify(jdbc, never()).update(anyString(), anyMap());
  }

  @Test
  void malformedCatalogFailsInsteadOfDuplicatingOrGuessingCashTypes() {
    add(1, "note", "USD", "10", "VOUCHER");
    assertThrows(PlatformInternalServerException.class, () -> service.denominations(null, "USD"));
    catalog.clear();
    add(1, "same", "USD", "10", "NOTE");
    add(2, "same", "USD", "20", "NOTE");
    assertThrows(PlatformInternalServerException.class, () -> service.denominations(null, "USD"));
    catalog.clear();
    add(1, "one", "USD", "10.00", "NOTE");
    add(2, "two", "USD", "10.000", "NOTE");
    assertThrows(PlatformInternalServerException.class, () -> service.denominations(null, "USD"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1"})
  void nonpositiveConfiguredValuesAreRejected(String value) {
    add(1, "invalid", "USD", value, "COIN");
    assertThrows(PlatformInternalServerException.class, () -> service.denominations(null, "USD"));
  }

  @Test
  void jdbcFailuresCannotLeakSqlOrCredentials() {
    when(inventory.context())
        .thenThrow(
            new DataAccessResourceFailureException("SELECT secret_password FROM /private/path"));
    var error = assertThrows(PlatformInternalServerException.class, () -> service.context(null));
    assertFalse(error.getDefaultUserMessage().contains("SELECT"));
    assertFalse(error.getDefaultUserMessage().contains("private"));
  }

  private void add(long id, String identifier, String currency, String value, String type) {
    catalog.add(
        Map.of(
            "id",
            id,
            "identifier",
            identifier,
            "currency_code",
            currency,
            "value",
            new BigDecimal(value),
            "denomination_type",
            type));
  }
}
