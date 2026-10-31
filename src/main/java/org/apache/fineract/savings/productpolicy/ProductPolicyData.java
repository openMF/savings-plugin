/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.math.BigDecimal;
import java.util.List;

/** Product metadata only. No policy in this contract authorizes a financial operation. */
public final class ProductPolicyData {
  private ProductPolicyData() {}

  public enum Classification {
    SIGHT,
    INTEREST_BEARING_SIGHT,
    SAVINGS
  }

  public enum Purpose {
    MAIN,
    GUARANTEED,
    PLANNED_MAIN,
    POCKET
  }

  public enum Multiplicity {
    SINGLE,
    UNLIMITED
  }

  public enum Freezing {
    NONE,
    TERM,
    LOAN_RELEASE
  }

  public enum InterestMode {
    NONE,
    FIXED,
    TIERED,
    REFERENCE_RATE
  }

  public enum Status {
    DRAFT,
    UNCONFIGURED
  }

  public record Policy(
      Classification classification,
      Purpose purpose,
      Boolean withdrawalsAllowed,
      Boolean depositsAllowed,
      Boolean liquidGuarantee,
      BigDecimal maximumBalance,
      BigDecimal minimumMonthlyAverageBalance,
      Boolean feeApplicable,
      Long feeChargeId,
      Freezing freezing,
      Integer freezingTermDays,
      Boolean directDebitForLoanPayment,
      Multiplicity multiplicity,
      String accountDesignation,
      Boolean additionalPaymentReference,
      String paymentReferencePartner,
      InterestMode interestMode) {}

  public record WriteRequest(Long expectedRevision, Policy policy) {}

  public record Configuration(
      Long savingsProductId,
      Long revision,
      Status status,
      boolean operational,
      Policy policy,
      Capabilities capabilities) {}

  public record Capabilities(
      boolean draftConfiguration,
      boolean activation,
      boolean financialEnforcement,
      boolean clabeGeneration,
      List<Classification> classifications,
      List<Purpose> purposes,
      List<Multiplicity> multiplicities,
      List<Freezing> freezingModes,
      List<InterestMode> interestModes,
      List<String> pendingFeatures) {}

  public static Capabilities capabilities() {
    return new Capabilities(
        true,
        false,
        false,
        false,
        List.of(Classification.values()),
        List.of(Purpose.values()),
        List.of(Multiplicity.values()),
        List.of(Freezing.values()),
        List.of(InterestMode.values()),
        List.of(
            "APPROVAL_AND_ACTIVATION",
            "TRANSACTION_RESTRICTIONS",
            "BALANCE_LIMITS",
            "FROZEN_FUNDS",
            "INTEREST_CALCULATION",
            "RATE_PROPAGATION",
            "MONTHLY_AVERAGE_FEES",
            "GUARANTEE_ALLOCATION_AND_SWEEPS",
            "PAYROLL_AND_POCKETS",
            "CONSOLIDATED_ISR",
            "PAYMENT_REFERENCE_GENERATION"));
  }
}
