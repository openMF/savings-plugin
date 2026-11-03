/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrative facade over the existing policy aggregate; never participates in core posting. */
@Service
@RequiredArgsConstructor
public class InterestMatrixService {
  private final ProductPolicyService policies;
  private final ProductPolicyWorkflow workflow;
  private final ProductPolicyCoreSnapshot core;
  private final ProductPolicyValidation validation;

  @Transactional(readOnly = true)
  public InterestMatrixData.Configuration draft(long product) {
    var draft = policies.get(product);
    return configuration(draft);
  }

  @Transactional
  public InterestMatrixData.Configuration save(long product, String json) {
    workflow.authorize(ProductPolicyService.WRITE);
    var request = request(json);
    // Every policy/matrix writer uses the authoritative product lock and the SAME revision.
    workflow.lock(product);
    var draft = policies.get(product);
    var body = body(draft, request);
    return configuration(policies.save(product, body));
  }

  @Transactional
  public InterestMatrixData.Configuration validate(long product, String json) {
    workflow.authorize(ProductPolicyService.WRITE);
    var request = request(json);
    workflow.lock(product);
    var draft = policies.get(product);
    var parsed = validation.parse(body(draft, request));
    core.validate(product, parsed.policy(), validation);
    return new InterestMatrixData.Configuration(
        product,
        draft.revision(),
        "VALIDATED_METADATA",
        false,
        parsed.policy().interestMatrix(),
        InterestMatrixData.blockers());
  }

  private static InterestMatrixData.WriteRequest request(String json) {
    var object = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(object, Set.of("expectedRevision", "matrix"));
    ProductPolicyValidation.integer(object.get("expectedRevision"), "expectedRevision");
    return new InterestMatrixData.WriteRequest(
        object.get("expectedRevision").getAsLong(),
        InterestMatrixValidation.parse(object.get("matrix")));
  }

  private static String body(
      ProductPolicyData.Configuration draft, InterestMatrixData.WriteRequest request) {
    if (draft.policy() == null)
      throw invalid("policy", "Create an eligible product policy draft first.");
    if (!request.expectedRevision().equals(draft.revision()))
      throw new ProductPolicyService.Conflict();
    var policy = ProductPolicyWorkflow.JSON.toJsonTree(draft.policy()).getAsJsonObject();
    policy.add("interestMatrix", ProductPolicyWorkflow.JSON.toJsonTree(request.matrix()));
    var body = new com.google.gson.JsonObject();
    body.addProperty("expectedRevision", request.expectedRevision());
    body.add("policy", policy);
    return ProductPolicyWorkflow.JSON.toJson(body);
  }

  private static InterestMatrixData.Configuration configuration(
      ProductPolicyData.Configuration draft) {
    return new InterestMatrixData.Configuration(
        draft.savingsProductId(),
        draft.revision(),
        draft.status().name(),
        false,
        draft.policy() == null ? null : draft.policy().interestMatrix(),
        InterestMatrixData.blockers());
  }

  /**
   * Resolves approved historical configuration using its immutable core precision, never today's
   * draft.
   */
  @Transactional
  public InterestMatrixData.Selection selectForReview(
      long product, BigDecimal balance, LocalDate date) {
    var version = workflow.applicable(product, date);
    if (version == null
        || version.policy().interestMode() != ProductPolicyData.InterestMode.TIERED
        || version.policy().interestMatrix() == null)
      throw invalid(
          "interestMatrix", "No approved applicable tiered matrix exists for this business date.");
    int digits = ((Number) version.coreSettings().get("currency_digits")).intValue();
    var matrix = version.policy().interestMatrix();
    InterestMatrixValidation.validate(matrix, digits, version.policy().maximumBalance());
    InterestMatrixValidation.amount(balance, digits, "balance");
    if (balance.compareTo(version.policy().maximumBalance()) > 0)
      throw invalid("balance", "Balance exceeds the reviewed policy coverage.");
    for (int i = 0; i < matrix.brackets().size(); i++) {
      var b = matrix.brackets().get(i);
      if (balance.compareTo(b.lowerBalance()) >= 0
          && (b.upperBalance() == null || balance.compareTo(b.upperBalance()) <= 0))
        return new InterestMatrixData.Selection(
            product,
            version.version(),
            date == null ? ProductPolicyWorkflow.today() : date,
            (String) version.coreSettings().get("currency_code"),
            digits,
            i,
            b,
            "CONFIGURATION_ONLY_UNAPPROVED_SEMANTICS",
            false);
    }
    throw invalid(
        "balance", "No bracket covers this balance; nominal rate fallback is prohibited.");
  }

  /** Explicit fail-closed boundary for any future financial caller. */
  public void requireFinancialCapability() {
    throw invalid(
        "interestMatrix",
        "Tiered financial calculation/posting is unavailable: approved slab semantics and full"
            + " Fineract execution coverage are required.");
  }
}
