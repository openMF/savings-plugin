/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ConsolidatedIsrData.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConsolidatedIsrCalculationTest {
  static final LocalDate DAY = LocalDate.of(2026, 11, 1);
  static final Period PERIOD = new Period(DAY, DAY);

  static Account account(long id, String currency, String balance, Long main) {
    return new Account(
        id,
        1,
        1,
        1,
        100,
        300,
        0,
        DAY,
        null,
        currency,
        2,
        1L,
        "SAVINGS",
        main == null ? "MAIN" : "POCKET",
        main,
        true,
        1L,
        2,
        new BigDecimal(balance),
        "hash",
        true,
        false,
        List.of(Reason.ISR_POLICY_NOT_APPROVED));
  }

  static Transaction transaction(
      long id, long account, int type, String amount, boolean reversed, boolean reversal) {
    return new Transaction(
        id, account, type, DAY, new BigDecimal(amount), reversed, reversal, null, null);
  }

  @AfterEach
  void clear() {
    ThreadLocalContextUtil.reset();
  }

  @Test
  void exactObservationsCountMainAndPocketOnceAndNeverSumFundingTransferAmounts() {
    var accounts =
        List.of(account(1, "MXN", "100.000001", null), account(2, "MXN", "23.999999", 1L));
    var result =
        ConsolidatedIsrBalanceService.aggregate(
                accounts,
                List.of(
                    transaction(1, 1, 2, "24", false, false),
                    transaction(2, 2, 1, "24", false, false),
                    transaction(3, 1, 3, "0.100001", false, false),
                    transaction(4, 2, 3, "0.200002", false, false),
                    transaction(5, 1, 18, "0.010001", false, false)),
                PERIOD)
            .getFirst();
    assertEquals(2, result.accountCount());
    assertEquals(0, new BigDecimal("124").compareTo(result.observedLedgerBalance()));
    assertEquals(new BigDecimal("0.300003"), result.nativePostedInterest());
    assertEquals(new BigDecimal("0.010001"), result.nativeWithheldTax());
  }

  @Test
  void currenciesStaySeparateAndZeroIsOnlyAnObservedNativeTotal() {
    var result =
        ConsolidatedIsrBalanceService.aggregate(
            List.of(account(1, "USD", "5", null), account(2, "MXN", "0", null)), List.of(), PERIOD);
    assertEquals(
        List.of("MXN", "USD"), result.stream().map(CurrencyObservation::currency).toList());
    assertEquals(BigDecimal.ZERO, result.getFirst().observedLedgerBalance());
    assertEquals(new BigDecimal("5"), result.getLast().observedLedgerBalance());
  }

  @Test
  void reversedTaxAndInterestAndReversalRowsAreExcludedFromObservedTotals() {
    var result =
        ConsolidatedIsrBalanceService.aggregate(
                List.of(account(1, "MXN", "0", null)),
                List.of(
                    transaction(1, 1, 3, "10", true, false),
                        transaction(2, 1, 18, "1", true, false),
                    transaction(3, 1, 3, "10", false, true),
                        transaction(4, 1, 18, "1", false, true),
                    transaction(5, 1, 3, "9", false, false),
                        transaction(6, 1, 18, "0.9", false, false)),
                PERIOD)
            .getFirst();
    assertEquals(new BigDecimal("9"), result.nativePostedInterest());
    assertEquals(new BigDecimal("0.9"), result.nativeWithheldTax());
  }

  @Test
  void duplicateOrForeignNativeSourcesCannotInflateTotals() {
    var a = account(1, "MXN", "10", null);
    assertThrows(
        IllegalArgumentException.class,
        () -> ConsolidatedIsrBalanceService.aggregate(List.of(a, a), List.of(), PERIOD));
    var t = transaction(1, 2, 3, "10", false, false);
    assertThrows(
        IllegalArgumentException.class,
        () -> ConsolidatedIsrBalanceService.aggregate(List.of(a), List.of(t), PERIOD));
    var own = transaction(1, 1, 3, "10", false, false);
    assertThrows(
        IllegalArgumentException.class,
        () -> ConsolidatedIsrBalanceService.aggregate(List.of(a), List.of(own, own), PERIOD));
  }

  @Test
  void noFormulaOrProfileCanProduceAnAmountEvenForKnownZeroBalances() {
    var accounts = new ArrayList<>(List.of(account(1, "MXN", "0", null)));
    var sources =
        new Sources(accounts, List.of(), List.of(), List.of(), true, List.of(), "fingerprint");
    accounts.clear();
    var profile = new Profile(1, "one", 2, null, null, null, null, null, "UNVERIFIED", List.of());
    var result = ConsolidatedIsrCalculator.blocked(1, DAY, PERIOD, Instant.EPOCH, profile, sources);
    assertEquals(1, result.sources().accounts().size());
    assertEquals("BLOCKED_PENDING_POLICY", result.state());
    assertNull(result.taxableBase());
    assertNull(result.calculatedIsr());
    assertNull(result.actualWithheldAmount());
    assertTrue(result.allocations().isEmpty());
    assertTrue(result.nativeReferences().isEmpty());
    assertTrue(result.blockers().containsAll(BLOCKERS));
    String json = RatePropagationJson.JSON.toJson(result);
    assertEquals(result, RatePropagationJson.JSON.fromJson(json, Preview.class));
    assertTrue(json.contains("\"calculatedIsr\":null"));
    assertFalse(capabilities().calculation());
    assertFalse(capabilities().withholdingExecution());
    assertFalse(capabilities().accountingIntegrationVerified());
    assertFalse(capabilities().consolidatedBalanceRetrieval());
  }

  @ParameterizedTest
  @CsvSource({"2026-11-02,2026-11-01", "2026-11-01,2026-11-02", "2024-01-01,2026-11-01"})
  void rejectsUnsupportedDiagnosticWindows(String from, String through) {
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            ConsolidatedIsrValidation.period(
                new Period(LocalDate.parse(from), LocalDate.parse(through)), DAY));
  }

  @Test
  void clientAmountsApproversAndTaxRatesAreRejectedAsUnknownFields() {
    for (String json :
        List.of("{\"taxableBase\":1}", "{\"approvedBy\":1}", "{\"rate\":0.1}", "[]", "null"))
      assertThrows(
          PlatformApiDataValidationException.class,
          () ->
              ConsolidatedIsrValidation.parse(
                  json, Save.class, Set.of("idempotencyKey", "period", "supersedes", "reason")));
  }

  @Test
  void proposalDatesAndSpecificationEvidenceHaveNoImplicitApproval() {
    ProductPolicyWorkflowIntegrationTest.setDay(DAY);
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            ConsolidatedIsrRuleResolver.validate(
                new RuleDefinition(
                    "XX",
                    "UNCONFIRMED",
                    "MXN",
                    DAY.minusDays(1),
                    DAY,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            ConsolidatedIsrRuleResolver.validate(
                new RuleDefinition(
                    "XX",
                    "UNCONFIRMED",
                    "MXN",
                    DAY,
                    DAY,
                    "reference",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)));
    assertDoesNotThrow(
        () ->
            ConsolidatedIsrRuleResolver.validate(
                new RuleDefinition(
                    "XX",
                    "UNCONFIRMED",
                    "MXN",
                    DAY,
                    DAY,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }
}
