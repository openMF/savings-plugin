/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;
import static org.apache.fineract.savings.productpolicy.ReferenceRateData.*;

import java.time.LocalDate;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Formula edits are edits to the BE-02 policy aggregate, sharing revisions and contributor audit.
 */
@Service
@RequiredArgsConstructor
public class ReferenceRateFormulaService {
  private final ProductPolicyService policies;
  private final ProductPolicyWorkflow workflow;
  private final ReferenceRateService benchmarks;

  @Transactional(readOnly = true)
  public Configuration draft(long product) {
    return configuration(policies.get(product));
  }

  @Transactional
  public Configuration save(long product, String json) {
    workflow.authorize(ProductPolicyService.WRITE);
    var o = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(o, Set.of("expectedRevision", "formula"));
    long revision = ReferenceRateValidation.revision(o);
    var formula = ReferenceRateValidation.formula(o.get("formula"));
    workflow.lock(product);
    var draft = policies.get(product);
    if (draft.policy() == null)
      throw invalid("policy", "Create an eligible product policy draft first.");
    if (draft.revision() != revision) throw new ProductPolicyService.Conflict();
    var p = ProductPolicyWorkflow.JSON.toJsonTree(draft.policy()).getAsJsonObject();
    p.add("referenceRate", ProductPolicyWorkflow.JSON.toJsonTree(formula));
    var body = new com.google.gson.JsonObject();
    body.addProperty("expectedRevision", revision);
    body.add("policy", p);
    return configuration(policies.save(product, body.toString()));
  }

  @Transactional
  public Calculation preview(long product, LocalDate date) {
    workflow.authorize(ProductPolicyService.READ);
    date = ReferenceRateValidation.businessDate(date);
    var policy = workflow.applicable(product, date);
    if (policy == null
        || policy.policy().interestMode() != ProductPolicyData.InterestMode.REFERENCE_RATE
        || policy.policy().referenceRate() == null)
      throw invalid("referenceRate", "No approved applicable reference-rate formula exists.");
    var formula = policy.policy().referenceRate();
    var fixing = benchmarks.resolve(formula.referenceId(), formula.tenor(), date);
    return ReferenceRateCalculator.preview(policy, fixing, date);
  }

  public void requireFinancialCapability() {
    throw invalid(
        "referenceRate",
        "Reference-rate account opening and financial calculation/posting are unavailable pending"
            + " approved policy and verified Fineract integration.");
  }

  private static Configuration configuration(ProductPolicyData.Configuration d) {
    return new Configuration(
        d.savingsProductId(),
        d.revision(),
        d.status().name(),
        d.policy() == null ? null : d.policy().referenceRate(),
        false,
        blockers());
  }
}
