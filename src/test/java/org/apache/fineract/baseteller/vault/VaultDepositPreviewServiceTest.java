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

import com.google.gson.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformInternalServerException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

class VaultDepositPreviewServiceTest {
  private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
  private static final LocalDate DATE = LocalDate.of(2026, 10, 27);
  private final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
  private final AppUser user = mock(AppUser.class);
  private final VaultReadPlatformService vaults = mock(VaultReadPlatformService.class);
  private final VaultDepositPreviewValidation validation =
      new VaultDepositPreviewValidation(VALIDATORS.getValidator());
  private VaultDepositPreviewService service;
  private final List<VaultData.Denomination> usd = new ArrayList<>();

  @BeforeEach
  void setUp() {
    when(security.authenticatedUser()).thenReturn(user);
    when(vaults.context(any())).thenReturn(context());
    add("one", "0.01", VaultData.DenominationType.COIN);
    add("five", "0.05", VaultData.DenominationType.COIN);
    add("ten", "0.10", VaultData.DenominationType.COIN);
    add("unit", "1.00", VaultData.DenominationType.COIN);
    add("note", "100.00", VaultData.DenominationType.BANKNOTE);
    add("large", "1000.00", VaultData.DenominationType.BANKNOTE);
    when(vaults.denominations(any(), eq("USD")))
        .thenAnswer(
            i ->
                new VaultData.Denominations(
                    "VAULT:8", "USD", List.copyOf(usd), VaultData.Status.AVAILABLE));
    when(vaults.denominations(any(), eq("JPY")))
        .thenReturn(
            new VaultData.Denominations(
                "VAULT:8",
                "JPY",
                List.of(
                    new VaultData.Denomination(
                        20L,
                        "yen",
                        "JPY",
                        new BigDecimal("1000"),
                        VaultData.DenominationType.BANKNOTE,
                        true)),
                VaultData.Status.AVAILABLE));
    service = new VaultDepositPreviewService(security, vaults, validation);
  }

  @AfterAll
  static void closeValidators() {
    VALIDATORS.close();
  }

  private static VaultData.Context context() {
    return new VaultData.Context(
        new VaultData.Branch(8L, "Branch"),
        new VaultData.Vault("VAULT:8", 8L, "Vault", VaultData.Status.UNKNOWN),
        DATE,
        List.of(
            new VaultData.Currency("USD", "Dollar", 2), new VaultData.Currency("JPY", "Yen", 0)),
        new VaultData.Capabilities(true, true, true, true, true, false),
        new VaultData.Readiness(false, List.of()));
  }

  private void add(String id, String value, VaultData.DenominationType type) {
    usd.add(
        new VaultData.Denomination(
            (long) usd.size() + 1, id, "USD", new BigDecimal(value), type, true));
  }

  private static String request(String rows) {
    return "{\"officeId\":8,\"businessDate\":\"2026-10-27\",\"currencies\":[{\"currencyCode\":\"USD\",\"denominations\":["
        + rows
        + "]}]}";
  }

  private static String line(String id, String quantity) {
    return "{\"denominationId\":\"" + id + "\",\"quantity\":" + quantity + "}";
  }

  @Test
  void exactBanknoteCoinAndGeneralTransactionBreakdowns() {
    var result =
        service.preview(
            request(
                line("note", "2")
                    + ","
                    + line("one", "3")
                    + ","
                    + line("five", "1")
                    + ","
                    + line("ten", "2")
                    + ","
                    + line("unit", "1")
                    + ","
                    + line("large", "1")));
    var currency = result.currencies().getFirst();
    assertEquals(new BigDecimal("1200.00"), currency.banknotesTotal());
    assertEquals(new BigDecimal("1.28"), currency.coinsTotal());
    assertEquals(new BigDecimal("1201.28"), currency.totalCash());
    assertEquals(
        new BigDecimal("0.03"),
        currency.coins().stream()
            .filter(l -> l.denominationId().equals("one"))
            .findFirst()
            .orElseThrow()
            .subtotal());
    assertEquals("VAULT", currency.generalTransaction().perspective());
    assertEquals(currency.totalCash(), currency.generalTransaction().inflows().cash());
    assertEquals(new BigDecimal("0.00"), currency.generalTransaction().outflows().cash());
    assertFalse(currency.generalTransaction().inflows().checks().supported());
    assertNull(currency.generalTransaction().inflows().checks().amount());
    assertNull(currency.generalTransaction().outflows().vouchers().amount());
    assertEquals(VaultDepositPreviewData.ValidationStatus.VALID_PREVIEW, result.validationStatus());
    assertEquals(VaultDepositPreviewData.MovementStatus.PROPOSED, result.movementStatus());
  }

  @Test
  void noFinancialApprovalInventedForMathematicallyValidPreview() {
    var result = service.preview(request(line("note", "1")));
    assertFalse(result.executionReadiness().executionAllowed());
    assertEquals(
        VaultDepositPreviewData.ReadinessStatus.BLOCKED_BY_POLICY,
        result.executionReadiness().status());
    assertEquals(VaultData.Status.UNAVAILABLE, result.executionReadiness().sourcePolicy());
    assertEquals(VaultData.Status.UNAVAILABLE, result.executionReadiness().accountingPolicy());
    assertEquals(
        Set.of(
            "SOURCE_POLICY_UNAVAILABLE",
            "ACCOUNTING_POLICY_UNAVAILABLE",
            "VAULT_STATUS_UNKNOWN",
            "EXECUTION_NOT_IMPLEMENTED"),
        new HashSet<>(
            result.executionReadiness().blockers().stream()
                .map(VaultDepositPreviewData.Blocker::code)
                .toList()));
    verify(vaults, never()).inventory(any(), any());
  }

  @Test
  void multiCurrencyResultsAreIndependentSortedAndStable() {
    var body = JsonParser.parseString(request(line("note", "1"))).getAsJsonObject();
    body.getAsJsonArray("currencies")
        .add(
            JsonParser.parseString(
                "{\"currencyCode\":\"JPY\",\"denominations\":[{\"denominationId\":\"yen\",\"quantity\":2}]}"));
    var result = service.preview(body.toString());
    assertEquals(
        List.of("JPY", "USD"),
        result.currencies().stream().map(VaultDepositPreviewData.Currency::currencyCode).toList());
    assertEquals(new BigDecimal("2000"), result.currencies().getFirst().totalCash());
    assertEquals(new BigDecimal("100.00"), result.currencies().getLast().totalCash());
    assertEquals(result, service.preview(body.toString()));
  }

  @Test
  void zeroRowsAreValidatedThenOmittedAndCaseAliasesCannotDoubleCount() {
    var result = service.preview(request(line("note", "0") + "," + line("unit", "1")));
    assertTrue(result.currencies().getFirst().banknotes().isEmpty());
    assertEquals(new BigDecimal("1.00"), result.currencies().getFirst().totalCash());
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.preview(request(line("note", "1") + "," + line(" NOTE ", "2"))));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.preview(request(line("inactive", "0") + "," + line("note", "1"))));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "-1",
        "1.5",
        "1.0",
        "null",
        "true",
        "\"1\"",
        "1000000001",
        "9223372036854775808",
        "1e1000000"
      })
  void invalidQuantityTypesRangesAndOverflowRejected(String quantity) {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.preview(request(line("note", quantity))));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "{\"denominationId\":\"note\",\"quantity\":0}",
        "{\"denominationId\":\"missing\",\"quantity\":1}",
        "{\"denominationId\":\"yen\",\"quantity\":1}",
        "{\"denominationId\":\"note\",\"quantity\":1},{\"denominationId\":\"note\",\"quantity\":1}"
      })
  void emptyZeroUnsupportedMismatchedAndDuplicateSelectionsRejected(String rows) {
    assertThrows(PlatformApiDataValidationException.class, () -> service.preview(request(rows)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"value\":100}",
        "{\"userId\":1}",
        "{\"executionAllowed\":true}",
        "{\"glAccountId\":1}",
        "{\"sourceCashierId\":1}",
        "{\"totalCash\":100}"
      })
  void clientCannotOverrideServerOwnedFields(String addition) {
    var body = JsonParser.parseString(request(line("note", "1"))).getAsJsonObject();
    JsonParser.parseString(addition)
        .getAsJsonObject()
        .entrySet()
        .forEach(e -> body.add(e.getKey(), e.getValue()));
    assertThrows(PlatformApiDataValidationException.class, () -> service.preview(body.toString()));
  }

  @Test
  void clientDenominationValueAndUnknownNestedFieldsRejected() {
    var raw = request(line("note", "1")).replace("\"quantity\":1", "\"quantity\":1,\"value\":0.01");
    var error = assertThrows(PlatformApiDataValidationException.class, () -> service.preview(raw));
    assertTrue(error.getErrors().getFirst().getParameterName().endsWith(".value"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-10-26", "2026-10-28", "2026-02-30", "not-a-date"})
  void invalidBusinessDatesRejected(String date) {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.preview(request(line("note", "1")).replace("2026-10-27", date)));
  }

  @Test
  void authorizationPrecedesParsingAndPhaseOneEnforcesOfficeTenantScope() {
    doThrow(new AccessDeniedException("denied"))
        .when(user)
        .validateHasPermissionTo(VaultReadPlatformService.PERMISSION);
    assertThrows(AccessDeniedException.class, () -> service.preview("malformed"));
    verifyNoInteractions(vaults);
    doNothing().when(user).validateHasPermissionTo(anyString());
    when(vaults.context("8")).thenThrow(new AccessDeniedException("wrong branch"));
    assertThrows(AccessDeniedException.class, () -> service.preview(request(line("note", "1"))));
    doThrow(new VaultNotFoundException()).when(vaults).context("8");
    assertThrows(VaultNotFoundException.class, () -> service.preview(request(line("note", "1"))));
  }

  @Test
  void currenciesMustBeEnabledAndUniqueIgnoringCase() {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.preview(request(line("note", "1")).replace("USD", "EUR")));
    var body = JsonParser.parseString(request(line("note", "1"))).getAsJsonObject();
    var duplicate = body.getAsJsonArray("currencies").get(0).deepCopy().getAsJsonObject();
    duplicate.addProperty("currencyCode", "usd");
    body.getAsJsonArray("currencies").add(duplicate);
    assertThrows(PlatformApiDataValidationException.class, () -> service.preview(body.toString()));
  }

  @Test
  void excessiveTotalsAndInvalidConfiguredPrecisionAreNotRounded() {
    add("huge", "10000", VaultData.DenominationType.BANKNOTE);
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.preview(request(line("huge", "1000000000"))));
    add("overprecision", "0.001", VaultData.DenominationType.COIN);
    assertThrows(
        PlatformInternalServerException.class,
        () -> service.preview(request(line("overprecision", "1"))));
  }

  @Test
  void absentCatalogAndAmbiguousIdentifierConfigurationCannotYieldSuccessfulPreview() {
    usd.clear();
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.preview(request(line("note", "1"))));
    add("note", "100", VaultData.DenominationType.BANKNOTE);
    add("NOTE", "200", VaultData.DenominationType.BANKNOTE);
    assertThrows(
        PlatformInternalServerException.class, () -> service.preview(request(line("note", "1"))));
  }

  @Test
  void beanValidationLimitsCollectionsAndQuantityEvenForTypedInternalCallers() {
    var row = new VaultDepositPreviewRequest.Quantity("note", -1L);
    var request =
        new VaultDepositPreviewRequest(
            0L,
            "2026-10-27",
            List.of(new VaultDepositPreviewRequest.Currency("USD", Collections.nCopies(1001, row))),
            null);
    assertThrows(PlatformApiDataValidationException.class, () -> validation.validate(request));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "[]",
        "{",
        "{}",
        "{\"currencies\":null}",
        "{\"businessDate\":3,\"currencies\":[]}"
      })
  void malformedRequestsAreStructured(String json) {
    assertThrows(PlatformApiDataValidationException.class, () -> service.preview(json));
  }
}
