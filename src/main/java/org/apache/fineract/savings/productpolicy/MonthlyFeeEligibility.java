/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.MonthlyFeeData.*;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Uses BE-02 immutable versions and bindings; never substitutes a draft or current balance. */
@Component
@RequiredArgsConstructor
public class MonthlyFeeEligibility {
  private final JdbcTemplate jdbc;
  private final ProductPolicyWorkflow workflow;
  private final ProductPolicyCoreSnapshot core;

  @Transactional(propagation = Propagation.MANDATORY)
  public Decision evaluate(long accountId, YearMonth month) {
    MonthlyFeeAssessmentService.tenant();
    MonthlyFeeAssessmentService.businessDate();
    var rows =
        jdbc.queryForList(
            "SELECT a.product_id,a.status_enum,a.sub_status_enum,a.deposit_type_enum,"
                + "a.activatedon_date,a.closedon_date,a.currency_code,a.currency_digits,b.savings_product_id"
                + " AS bound_product,b.version AS bound_version,b.purpose,p.savings_product_id AS"
                + " configured FROM m_savings_account a LEFT JOIN m_savings_account_policy b ON"
                + " b.savings_account_id=a.id LEFT JOIN m_savings_product_policy p ON"
                + " p.savings_product_id=a.product_id WHERE a.id=?",
            accountId);
    if (rows.size() != 1) return blocked(null, "ACCOUNT_MISSING");
    var row = rows.getFirst();
    long product = ((Number) row.get("product_id")).longValue();
    Long bound = ProductPolicyWorkflow.number(row.get("bound_version"));
    if (row.get("configured") == null && bound == null)
      return new Decision(null, null, null, null, Outcome.NOT_ELIGIBLE, "LEGACY_NATIVE_BEHAVIOR");
    if (row.get("configured") == null) return blocked(bound, "POLICY_MISSING");
    if (bound == null
        || !Objects.equals(product, ProductPolicyWorkflow.number(row.get("bound_product"))))
      return blocked(bound, "ACCOUNT_POLICY_BINDING_INVALID");
    // Serializes with existing BE-02 authorization. No policy service is duplicated.
    workflow.lock(product);
    try {
      var first = workflow.resolve(product, month.atDay(1));
      var last = workflow.resolve(product, month.atEndOfMonth());
      var current = workflow.resolve(product, ProductPolicyWorkflow.today());
      if (first == null || last == null)
        return blocked(bound, "POLICY_NOT_EFFECTIVE_FOR_FULL_PERIOD");
      if (first.version() != last.version()
          || last.version() != bound
          || current == null
          || current.version() != bound) return blocked(bound, "POLICY_TRANSITION_UNAPPROVED");
      var p = last.policy();
      if (last.checkerId() == null
          || p == null
          || p.purpose() == null
          || p.feeApplicable() == null
          || p.minimumMonthlyAverageBalance() == null
          || p.minimumMonthlyAverageBalance().signum() < 0
          || !p.purpose().name().equals(row.get("purpose")))
        return blocked(bound, "POLICY_INVALID");
      if (!ProductPolicyWorkflow.JSON.toJsonTree(last.coreSettings()).equals(core.capture(product)))
        return blocked(bound, "CORE_SNAPSHOT_CHANGED");
      var settings = ProductPolicyWorkflow.JSON.toJsonTree(last.coreSettings()).getAsJsonObject();
      if (!Objects.equals(row.get("currency_code"), settings.get("currency_code").getAsString())
          || ((Number) row.get("currency_digits")).intValue()
              != settings.get("currency_digits").getAsInt())
        return blocked(bound, "CURRENCY_MISMATCH");
      if (!ProductTransactionPolicyEvaluator.validMoney(
          p.minimumMonthlyAverageBalance(), ((Number) row.get("currency_digits")).intValue()))
        return blocked(bound, "THRESHOLD_INVALID");
      if (((Number) row.get("deposit_type_enum")).intValue() != 100
          || ((Number) row.get("status_enum")).intValue() != 300
          || row.get("sub_status_enum") == null
          || ((Number) row.get("sub_status_enum")).intValue() != 0)
        return blocked(bound, "ACCOUNT_STATUS_UNSUPPORTED");
      var activation = (java.sql.Date) row.get("activatedon_date");
      if (activation == null
          || activation.toLocalDate().isAfter(month.atDay(1))
          || row.get("closedon_date") != null)
        return blocked(bound, "PARTIAL_OR_INCOMPLETE_HISTORY");
      if (!p.feeApplicable()) {
        if (p.feeChargeId() != null) return blocked(bound, "FEE_CONFIGURATION_INVALID");
        return new Decision(
            bound,
            p.minimumMonthlyAverageBalance(),
            null,
            null,
            Outcome.NO_FEE_CONFIGURED,
            "FEE_DISABLED");
      }
      var charges =
          jdbc.queryForList(
              "SELECT amount FROM m_charge WHERE id=? AND is_active=true"
                  + " AND is_deleted=false AND currency_code='MXN' AND charge_applies_to_enum=2"
                  + " AND charge_time_enum=2 AND charge_calculation_enum=1 AND is_penalty=false",
              p.feeChargeId());
      if (charges.size() != 1
          || !(charges.getFirst().get("amount") instanceof BigDecimal amount)
          || amount.signum() <= 0
          || !ProductTransactionPolicyEvaluator.validMoney(
              amount, ((Number) row.get("currency_digits")).intValue()))
        return new Decision(
            bound,
            p.minimumMonthlyAverageBalance(),
            p.feeChargeId(),
            null,
            Outcome.BLOCKED,
            "FEE_CHARGE_INVALID");
      return new Decision(
          bound,
          p.minimumMonthlyAverageBalance(),
          p.feeChargeId(),
          amount,
          Outcome.BLOCKED,
          "FINANCIAL_RULES_UNAPPROVED");
    } catch (com.google.gson.JsonParseException
        | IllegalArgumentException
        | NullPointerException e) {
      return blocked(bound, "POLICY_SNAPSHOT_INVALID");
    }
  }

  private static Decision blocked(Long version, String reason) {
    return new Decision(version, null, null, null, Outcome.BLOCKED, reason);
  }
}
