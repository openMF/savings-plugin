/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;
import static org.apache.fineract.savings.productpolicy.ReferenceRateData.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Pure exact nominal annual percentage arithmetic. No rounding, yield, accrual or posting. */
public final class ReferenceRateCalculator {
  private ReferenceRateCalculator() {}

  public static Calculation preview(
      ProductPolicyWorkflowData.Version policy, Fixing fixing, LocalDate date) {
    if (policy == null
        || date == null
        || fixing == null
        || policy.policy() == null
        || policy.policy().interestMode() != ProductPolicyData.InterestMode.REFERENCE_RATE
        || !(policy.status().equals("APPROVED")
            || policy.status().equals("ACTIVE")
            || policy.status().equals("SUPERSEDED"))
        || policy.effectiveDate().isAfter(date)
        || fixing.status() != Status.APPROVED
        || !fixing.effectiveDate().equals(date))
      throw invalid(
          "referenceRate", "Approved, applicable formula and exact-date approved fixing required.");
    var f = policy.policy().referenceRate();
    ReferenceRateValidation.validate(f);
    if (!f.referenceId().equals(fixing.referenceId()) || !f.tenor().equals(fixing.tenor()))
      throw invalid("tenor", "Formula and fixing identity must match.");
    ReferenceRateValidation.bounded(fixing.annualRatePercent(), "annualRatePercent", 12);
    if (fixing.annualRatePercent().signum() < 0
        || fixing.publicationDate().isAfter(fixing.effectiveDate()))
      throw invalid("annualRatePercent", "Invalid benchmark observation.");
    BigDecimal result =
        f.formulaMode() == FormulaMode.ADDITIVE_SPREAD
            ? fixing.annualRatePercent().add(f.spreadPercentagePoints())
            : fixing.annualRatePercent().multiply(f.multiplier());
    ReferenceRateValidation.bounded(result, "calculatedAnnualRatePercent", 24);
    if (result.signum() < 0)
      throw invalid(
          "calculatedAnnualRatePercent", "Negative effective rates await institutional approval.");
    return new Calculation(
        policy.savingsProductId(),
        policy.version(),
        policy.effectiveDate(),
        f.referenceId(),
        f.referenceType(),
        f.tenor(),
        fixing.version(),
        fixing.publicationDate(),
        fixing.effectiveDate(),
        date,
        fixing.annualRatePercent(),
        f.formulaMode(),
        f.spreadPercentagePoints(),
        f.multiplier(),
        f.reviewFrequency(),
        result,
        "CALCULATED_PREVIEW_UNAPPROVED_FINANCIAL_POLICY",
        false,
        blockers());
  }
}
