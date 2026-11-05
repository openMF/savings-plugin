/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.RatePropagationData.*;

import com.google.gson.JsonElement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Conservative review decisions; missing contract rules are never interpreted as permission. */
final class RatePropagationEligibility {
  private RatePropagationEligibility() {}

  static final List<String> ACCOUNT_COLUMNS =
      List.of(
          "currency_code",
          "currency_digits",
          "nominal_annual_interest_rate",
          "interest_compounding_period_enum",
          "interest_posting_period_enum",
          "interest_calculation_type_enum",
          "interest_calculation_days_in_year_type_enum");

  static Eligibility evaluate(AccountSnapshot a, Source target, LocalDate cutoffDate) {
    if (a.savingsProductId() != target.savingsProductId())
      return new Eligibility("EXCLUDED", "ACCOUNT_PRODUCT_CHANGED");
    if (a.status() != 300) return new Eligibility("EXCLUDED", "ACCOUNT_NOT_ACTIVE");
    if (a.depositType() != 100) return new Eligibility("EXCLUDED", "INCOMPATIBLE_DEPOSIT_TYPE");
    if (a.subStatus() != 0)
      return new Eligibility("BLOCKED", "ACCOUNT_RESTRICTION_REVIEW_REQUIRED");
    if (a.createdAt() == null || a.activatedOn() == null || a.activatedOn().isAfter(cutoffDate))
      return new Eligibility("BLOCKED", "MISSING_OR_FUTURE_ACCOUNT_METADATA");
    if (a.previousPolicyVersion() == null
        || !"APPROVED".equals(a.previousPolicyStatus())
        || a.previousPolicy() == null
        || a.previousCore() == null)
      return new Eligibility("BLOCKED", "MISSING_APPROVED_ACCOUNT_POLICY_BINDING");
    if (a.previousPolicy().interestMode() != target.policy().interestMode())
      return new Eligibility("EXCLUDED", "INCOMPATIBLE_INTEREST_MODE");
    for (String key : ACCOUNT_COLUMNS)
      if (!equivalent(a.accountSettings().get(key), a.previousCore().get(key)))
        return new Eligibility("EXCLUDED", "ACCOUNT_RATE_OR_CALCULATION_EXCEPTION");
    if (a.conflictingPlan()) return new Eligibility("BLOCKED", "CONFLICTING_RATE_PLAN");
    return new Eligibility("BLOCKED", "CONTRACTUAL_REPRICING_POLICY_UNAPPROVED");
  }

  static boolean equivalent(JsonElement a, JsonElement b) {
    if (a == null || b == null || a.isJsonNull() || b.isJsonNull()) return false;
    if (a.isJsonPrimitive()
        && b.isJsonPrimitive()
        && a.getAsJsonPrimitive().isNumber()
        && b.getAsJsonPrimitive().isNumber())
      return new BigDecimal(a.getAsString()).compareTo(new BigDecimal(b.getAsString())) == 0;
    return a.equals(b);
  }
}
