/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;
import static org.apache.fineract.savings.productpolicy.RatePropagationData.*;

import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolves immutable BE-02/03/04 approvals, with no draft or mutable current-rate fallback. */
@Component
@RequiredArgsConstructor
public class RatePropagationSources {
  private final ProductPolicyWorkflow workflow;
  private final JdbcTemplate jdbc;
  private final ProductPolicyCoreSnapshot core;
  private final ReferenceRateService benchmarks;

  Source resolve(long product, Plan plan) {
    if (jdbc.queryForList(
            "SELECT id FROM m_savings_product WHERE id=? AND deposit_type_enum=100",
            Long.class,
            product)
        .isEmpty()) throw new ProductPolicyService.NotFound();
    var v = workflow.resolve(product, plan.effectiveDate());
    if (v == null
        || v.version() != plan.policyVersion()
        || v.checkerId() == null
        || v.decidedAt() == null)
      throw invalid(
          "policyVersion", "Select the approved policy applicable on the target effective date.");
    var row = workflow.row(product, plan.policyVersion());
    if (!"APPROVED".equals(row.get("status")))
      throw invalid("policyVersion", "Approved source required.");
    var snapshot = JsonParser.parseString((String) row.get("core_json")).getAsJsonObject();
    if (!snapshot.equals(core.capture(product))) throw new ProductPolicyService.Conflict();
    var p = v.policy();
    BigDecimal nominal = null;
    ReferenceRateData.Fixing fixing = null;
    switch (p.interestMode()) {
      case FIXED -> {
        nominal = snapshot.get("nominal_annual_interest_rate").getAsBigDecimal();
        InterestMatrixValidation.amount(nominal, 6, "nominalAnnualInterestRate");
      }
      case TIERED ->
          InterestMatrixValidation.validate(
              p.interestMatrix(), snapshot.get("currency_digits").getAsInt(), p.maximumBalance());
      case REFERENCE_RATE -> {
        if (p.referenceRate() == null)
          throw invalid("referenceRate", "Approved formula snapshot required.");
        fixing =
            benchmarks.resolve(
                p.referenceRate().referenceId(), p.referenceRate().tenor(), plan.effectiveDate());
        nominal =
            ReferenceRateCalculator.preview(v, fixing, plan.effectiveDate())
                .calculatedAnnualRatePercent();
      }
      default ->
          throw invalid("interestMode", "A rate-bearing approved configuration is required.");
    }
    return new Source(
        product,
        v.version(),
        v.effectiveDate(),
        v.makerId(),
        v.checkerId(),
        v.decidedAt(),
        p,
        snapshot,
        fixing,
        nominal);
  }

  void revalidate(Batch b) {
    var current = resolve(b.savingsProductId(), new Plan(b.policyVersion(), b.effectiveDate()));
    if (!Objects.equals(
        b.sourceHash(), RatePropagationValidation.hash(RatePropagationJson.JSON.toJson(current))))
      throw new ProductPolicyService.Conflict();
  }

  String classification(Source s) {
    var previous = workflow.resolve(s.savingsProductId(), s.policyEffectiveDate().minusDays(1));
    if (s.policy().interestMode() != ProductPolicyData.InterestMode.FIXED
        || previous == null
        || previous.policy().interestMode() != ProductPolicyData.InterestMode.FIXED)
      return "CONTRACT_REVIEW_REQUIRED";
    var old =
        JsonParser.parseString(
                (String) workflow.row(s.savingsProductId(), previous.version()).get("core_json"))
            .getAsJsonObject()
            .get("nominal_annual_interest_rate")
            .getAsBigDecimal();
    int cmp = s.nominalAnnualRatePreview().compareTo(old);
    return cmp > 0 ? "NOMINAL_INCREASE" : cmp < 0 ? "NOMINAL_REDUCTION" : "NOMINAL_UNCHANGED";
  }
}
