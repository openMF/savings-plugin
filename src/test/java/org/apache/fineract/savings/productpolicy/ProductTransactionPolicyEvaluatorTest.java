/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductTransactionPolicyData.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductTransactionPolicyEvaluatorTest {
  final ProductTransactionPolicyEvaluator evaluator = new ProductTransactionPolicyEvaluator();
  static final LocalDate DAY = LocalDate.of(2026, 11, 6);

  static ProductPolicyData.Policy policy(String field, Object value) {
    var json =
        JsonParser.parseString(ProductPolicyValidationTest.JSON)
            .getAsJsonObject()
            .getAsJsonObject("policy");
    if (field != null) json.add(field, ProductPolicyWorkflow.JSON.toJsonTree(value));
    return ProductPolicyWorkflow.JSON.fromJson(json, ProductPolicyData.Policy.class);
  }

  Decision evaluate(ProductPolicyData.Policy p, Operation op) {
    return evaluator.evaluate(p, op, new BigDecimal("1.01"), 2, DAY, DAY);
  }

  @Test
  void flagsAreIndependentAndNoPrivilegedBypassExists() {
    assertTrue(evaluate(policy(null, null), Operation.WITHDRAWAL).allowed());
    assertEquals(
        "withdrawals.disabled",
        evaluate(policy("withdrawalsAllowed", false), Operation.WITHDRAWAL).code());
    assertEquals(
        "withdrawals.disabled",
        evaluate(policy("withdrawalsAllowed", false), Operation.FORCED_WITHDRAWAL).code());
    assertEquals(
        "deposits.disabled", evaluate(policy("depositsAllowed", false), Operation.DEPOSIT).code());
    assertEquals(
        Outcome.UNSUPPORTED_ENFORCEMENT, evaluate(policy(null, null), Operation.DEPOSIT).outcome());
  }

  @Test
  void freezeAndSpecialPurposesNeverInferAReleaseOrAValidMainAccountTransfer() {
    for (String freeze : List.of("TERM", "LOAN_RELEASE"))
      assertEquals(
          "freezing.unsupported",
          evaluate(policy("freezing", freeze), Operation.WITHDRAWAL).code());
    for (String purpose : List.of("GUARANTEED", "PLANNED_MAIN", "POCKET"))
      assertEquals(
          "purpose.unsupported", evaluate(policy("purpose", purpose), Operation.WITHDRAWAL).code());
    for (String mode : List.of("TIERED", "REFERENCE_RATE"))
      assertEquals(
          "interest.unsupported",
          evaluate(policy("interestMode", mode), Operation.WITHDRAWAL).code());
    assertTrue(evaluate(policy("interestMode", "FIXED"), Operation.WITHDRAWAL).allowed());
  }

  @Test
  void missingOrCorruptPoliciesNeverBecomeLegacyExemptions() {
    assertEquals("policy.missing", evaluate(null, Operation.WITHDRAWAL).code());
    assertEquals(
        "policy.invalid",
        evaluate(policy("withdrawalsAllowed", null), Operation.WITHDRAWAL).code());
    assertEquals(
        "policy.invalid",
        evaluate(policy("maximumBalance", new BigDecimal("1.001")), Operation.WITHDRAWAL).code());
    assertEquals("operation.unsupported", evaluate(policy(null, null), null).code());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "0.001", "10000000000000", "1e999"})
  void invalidAmountsAreNotRounded(String value) {
    assertEquals(
        "amount.invalid",
        evaluator
            .evaluate(policy(null, null), Operation.WITHDRAWAL, new BigDecimal(value), 2, DAY, DAY)
            .code());
  }

  @Test
  void businessDateIsRequiredAndHistoricalSemanticsRemainBlocked() {
    for (LocalDate date : List.of(DAY.minusDays(1), DAY.plusDays(1)))
      assertEquals(
          "transaction.date.unsupported",
          evaluator
              .evaluate(policy(null, null), Operation.WITHDRAWAL, BigDecimal.ONE, 2, DAY, date)
              .code());
    assertEquals(
        "transaction.date.unsupported",
        evaluator
            .evaluate(policy(null, null), Operation.WITHDRAWAL, BigDecimal.ONE, 2, null, DAY)
            .code());
  }

  @Test
  void exactProjectionIsUsefulButDoesNotGrantCreditExecution() {
    assertTrue(
        evaluator
            .projectedBalance(
                new BigDecimal("9000"), new BigDecimal("999.99"), new BigDecimal("10000"), 2)
            .allowed());
    assertTrue(
        evaluator
            .projectedBalance(
                new BigDecimal("9000"), new BigDecimal("1000"), new BigDecimal("10000"), 2)
            .allowed());
    assertEquals(
        "maximum.balance.exceeded",
        evaluator
            .projectedBalance(
                new BigDecimal("9000"), new BigDecimal("1000.01"), new BigDecimal("10000"), 2)
            .code());
    assertTrue(
        evaluator
            .projectedBalance(
                new BigDecimal("9999999999998.99"),
                new BigDecimal("0.01"),
                new BigDecimal("9999999999999.00"),
                2)
            .allowed());
    assertEquals(
        "amount.invalid",
        evaluator
            .projectedBalance(BigDecimal.ZERO, new BigDecimal("0.001"), BigDecimal.TEN, 2)
            .code());
    assertTrue(
        evaluator
            .projectedBalance(BigDecimal.ZERO, new BigDecimal("0.001"), BigDecimal.TEN, 3)
            .allowed());
    assertEquals(
        "maximum.balance.enforcement.unsupported",
        evaluate(policy(null, null), Operation.DEPOSIT).code());
  }

  @Test
  void capabilityAndActivationRemainFailClosed() {
    assertThrows(
        org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException.class,
        ProductTransactionPolicyEvaluator::requireActivationSupported);
    var c = ProductPolicyData.capabilities();
    assertFalse(c.financialEnforcement());
    assertTrue(
        c.pendingFeatures()
            .containsAll(List.of("TRANSACTION_RESTRICTIONS", "BALANCE_LIMITS", "FROZEN_FUNDS")));
    assertFalse(c.transactionControls().maximumBalanceEnforcement());
    assertFalse(c.transactionControls().configuredActivation());
  }
}
