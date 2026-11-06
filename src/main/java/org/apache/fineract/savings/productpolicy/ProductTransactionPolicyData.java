/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;

/** Internal decisions are not posting receipts or authority to override native controls. */
public final class ProductTransactionPolicyData {
  private ProductTransactionPolicyData() {}

  public enum Operation {
    DEPOSIT,
    WITHDRAWAL,
    FORCED_WITHDRAWAL
  }

  public enum Outcome {
    ALLOW,
    POLICY_DENIED,
    UNSUPPORTED_ENFORCEMENT
  }

  public record Decision(Outcome outcome, String code, String field) {
    boolean allowed() {
      return outcome == Outcome.ALLOW;
    }

    void requireAllowed() {
      if (!allowed())
        throw new PlatformApiDataValidationException(
            "error.msg.savings.product.transaction." + code,
            "Product transaction control: " + code + ". No Operations exception is available.",
            field);
    }
  }

  public record Resolved(
      long productId, Long version, ProductPolicyData.Policy policy, Decision decision) {}

  /** No customer names, request bodies, monetary balances, or credentials. */
  public record Evidence(
      String correlationId,
      String tenant,
      Long actorId,
      long accountId,
      long productId,
      Long policyVersion,
      Operation operation,
      String outcome,
      String code,
      LocalDate businessDate,
      Long nativeTransactionId) {}

  public record Capabilities(
      boolean decisionEvaluation,
      String depositEventVeto,
      String withdrawalEventVeto,
      boolean maximumBalanceEnforcement,
      boolean freezingLifecycle,
      boolean operationsExceptions,
      boolean universalNativeCoverage,
      boolean configuredActivation,
      List<String> blockers) {}

  public static Capabilities capabilities() {
    return new Capabilities(
        true,
        "PARTIALLY_ENFORCED",
        "PARTIALLY_ENFORCED",
        false,
        false,
        false,
        false,
        false,
        List.of(
            "NATIVE_ADJUSTMENT_AND_REVERSAL_HAVE_NO_VALIDATION_EVENT",
            "MAXIMUM_BALANCE_BASIS_AND_COMPLETE_CREDIT_COVERAGE_UNAPPROVED",
            "RESTRICTION_VERSION_TRANSITION_AND_BACKDATING_UNAPPROVED",
            "OPERATIONS_EXCEPTION_CONTRACT_UNAPPROVED",
            "FREEZING_AND_SPECIAL_PURPOSE_LIFECYCLE_UNSUPPORTED"));
  }

  static Decision allow() {
    return new Decision(Outcome.ALLOW, "eligible", null);
  }

  static Decision deny(String code, String field) {
    return new Decision(Outcome.POLICY_DENIED, code, field);
  }

  static Decision unsupported(String code, String field) {
    return new Decision(Outcome.UNSUPPORTED_ENFORCEMENT, code, field);
  }
}
