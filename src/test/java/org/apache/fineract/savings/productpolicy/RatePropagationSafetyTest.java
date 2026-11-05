/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.RatePropagationData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.*;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class RatePropagationSafetyTest {
  static final LocalDate DAY = LocalDate.of(2026, 11, 5);

  static ProductPolicyData.Policy policy(String mode) {
    return new ProductPolicyValidation()
        .parse(
            ProductPolicyValidationTest.JSON
                .replace("SIGHT", "INTEREST_BEARING_SIGHT")
                .replace("NONE\"}}", mode + "\"}}"))
        .policy();
  }

  static JsonObject settings() {
    var j = new JsonObject();
    j.addProperty("currency_code", "MXN");
    j.addProperty("currency_digits", 2);
    for (String key : RatePropagationEligibility.ACCOUNT_COLUMNS)
      if (!j.has(key)) j.addProperty(key, 6);
    return j;
  }

  static Source source(String mode) {
    return new Source(
        1, 2, DAY, 1, 2L, Instant.EPOCH, policy(mode), settings(), null, new BigDecimal("6"));
  }

  static AccountSnapshot account(
      int status,
      int type,
      int sub,
      Long binding,
      String approval,
      ProductPolicyData.Policy p,
      JsonObject core,
      JsonObject actual,
      boolean conflict) {
    return new AccountSnapshot(
        9,
        1,
        status,
        type,
        sub,
        DAY.minusDays(10).atStartOfDay(),
        DAY.minusDays(9),
        new BigDecimal("6"),
        binding,
        approval,
        p,
        core,
        actual,
        conflict);
  }

  @ParameterizedTest
  @ValueSource(ints = {100, 200, 400, 500, 600, 700, 800})
  void nonactiveAccountsAreExcluded(int status) {
    var a = account(status, 100, 0, 1L, "APPROVED", policy("FIXED"), settings(), settings(), false);
    assertEquals(
        "ACCOUNT_NOT_ACTIVE",
        RatePropagationEligibility.evaluate(a, source("FIXED"), DAY).reason());
  }

  @Test
  void unknownContractsNeverBecomeEligibilityOrFinancialEntitlements() {
    var a = account(300, 100, 0, 1L, "APPROVED", policy("FIXED"), settings(), settings(), false);
    var e = RatePropagationEligibility.evaluate(a, source("FIXED"), DAY);
    assertEquals("BLOCKED", e.decision());
    assertEquals("CONTRACTUAL_REPRICING_POLICY_UNAPPROVED", e.reason());
    var executor = new DisabledAccountRateTransitionExecutor();
    assertFalse(executor.financialExecutionSupported());
    for (String mode : new String[] {"FIXED", "TIERED", "REFERENCE_RATE"}) {
      var result = executor.execute("stable-operation", a, source(mode), DAY);
      assertEquals("BLOCKED", result.outcome());
      assertNull(result.confirmedOperationId());
      assertFalse(capabilities().financialExecutionSupported());
    }
    assertEquals("UNKNOWN_OUTCOME", executor.reconcile("unknown-operation").outcome());
    assertNull(executor.reconcile("unknown-operation").confirmedOperationId());
  }

  @Test
  void accountExceptionsAndMissingMetadataFailClosed() {
    var s = source("FIXED");
    assertEquals(
        "INCOMPATIBLE_DEPOSIT_TYPE",
        RatePropagationEligibility.evaluate(
                account(
                    300, 200, 0, 1L, "APPROVED", policy("FIXED"), settings(), settings(), false),
                s,
                DAY)
            .reason());
    assertEquals(
        "ACCOUNT_RESTRICTION_REVIEW_REQUIRED",
        RatePropagationEligibility.evaluate(
                account(
                    300, 100, 100, 1L, "APPROVED", policy("FIXED"), settings(), settings(), false),
                s,
                DAY)
            .reason());
    assertEquals(
        "MISSING_APPROVED_ACCOUNT_POLICY_BINDING",
        RatePropagationEligibility.evaluate(
                account(300, 100, 0, null, null, null, null, settings(), false), s, DAY)
            .reason());
    assertEquals(
        "INCOMPATIBLE_INTEREST_MODE",
        RatePropagationEligibility.evaluate(
                account(300, 100, 0, 1L, "APPROVED", policy("NONE"), settings(), settings(), false),
                s,
                DAY)
            .reason());
    var override = settings();
    override.addProperty("nominal_annual_interest_rate", 9);
    assertEquals(
        "EXCLUDED",
        RatePropagationEligibility.evaluate(
                account(300, 100, 0, 1L, "APPROVED", policy("FIXED"), settings(), override, false),
                s,
                DAY)
            .decision());
    assertEquals(
        "CONFLICTING_RATE_PLAN",
        RatePropagationEligibility.evaluate(
                account(300, 100, 0, 1L, "APPROVED", policy("FIXED"), settings(), settings(), true),
                s,
                DAY)
            .reason());
  }

  @Test
  void durableJsonRoundTripPreservesExactDecimalAndTime() {
    var s = source("FIXED");
    String encoded = RatePropagationJson.JSON.toJson(s);
    assertEquals(
        encoded,
        RatePropagationJson.JSON.toJson(RatePropagationJson.JSON.fromJson(encoded, Source.class)));
    assertTrue(encoded.contains("2026-11-05"));
    var legacy = account(300, 100, 0, null, null, null, null, settings(), false);
    var restored =
        RatePropagationJson.JSON.fromJson(
            RatePropagationJson.JSON.toJson(legacy), AccountSnapshot.class);
    assertNull(restored.previousCore());
    assertEquals(
        "MISSING_APPROVED_ACCOUNT_POLICY_BINDING",
        RatePropagationEligibility.evaluate(restored, s, DAY).reason());
  }

  @Test
  void pinnedFineractRejectsOrdinaryActiveAccountRateModification() {
    var account = mock(SavingsAccount.class, CALLS_REAL_METHODS);
    ReflectionTestUtils.setField(account, "status", 300);
    ReflectionTestUtils.setField(account, "nominalAnnualInterestRate", new BigDecimal("6"));
    JsonCommand command = null; // The active-state guard must reject before reading any command.
    assertThrows(
        org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException.class,
        () -> account.modifyApplication(command, new java.util.HashMap<>()));
    assertEquals(new BigDecimal("6"), account.getNominalAnnualInterestRate());
  }
}
