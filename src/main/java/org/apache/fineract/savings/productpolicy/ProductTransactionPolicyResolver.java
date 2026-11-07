/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductTransactionPolicyData.*;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reuses immutable BE-02 versions. Missing bindings never exempt configured accounts. */
@Service
@RequiredArgsConstructor
public class ProductTransactionPolicyResolver {
  private final JdbcTemplate jdbc;
  private final ProductPolicyWorkflow workflow;
  private final ProductPolicyCoreSnapshot core;

  @Transactional(propagation = Propagation.MANDATORY)
  public Resolved resolve(SavingsAccount account) {
    if (ThreadLocalContextUtil.getTenant() == null)
      throw new org.springframework.security.access.AccessDeniedException(
          "Authenticated tenant required.");
    long product = account.productId();
    var rows =
        jdbc.queryForList(
            "SELECT a.product_id,b.savings_product_id,b.version,b.purpose,p.savings_product_id AS"
                + " configured FROM m_savings_account a LEFT JOIN m_savings_account_policy b ON"
                + " b.savings_account_id=a.id LEFT JOIN m_savings_product_policy p ON"
                + " p.savings_product_id=a.product_id WHERE a.id=?",
            account.getId());
    if (rows.size() != 1 || ((Number) rows.getFirst().get("product_id")).longValue() != product)
      return rejected(product, null, "account.binding.invalid");
    var row = rows.getFirst();
    Long boundProduct = number(row.get("savings_product_id"));
    Long boundVersion = number(row.get("version"));
    if (boundProduct != null && boundProduct != product)
      return rejected(product, boundVersion, "account.binding.invalid");
    if (row.get("configured") == null) {
      return boundProduct == null
          ? new Resolved(product, null, null, allow())
          : rejected(product, boundVersion, "policy.missing");
    }
    if (boundVersion == null) return rejected(product, null, "account.binding.missing");
    if (ProductPolicyWorkflow.today() == null)
      return rejected(product, boundVersion, "business.date.missing");
    // Shares BE-02's product enrollment/approval lock. No unbounded history reconciliation here.
    workflow.lock(product);
    ProductPolicyWorkflowData.Version active;
    try {
      active = workflow.resolve(product, ProductPolicyWorkflow.today());
    } catch (com.google.gson.JsonParseException | IllegalArgumentException e) {
      return rejected(product, boundVersion, "policy.invalid");
    }
    if (active == null) return rejected(product, boundVersion, "policy.not.effective");
    if (active.version() != boundVersion)
      return rejected(product, boundVersion, "policy.transition.unsupported");
    if (active.coreSettings() == null
        || active.policy() == null
        || active.policy().purpose() == null
        || !active.policy().purpose().name().equals(row.get("purpose"))
        || active.checkerId() == null
        || !Objects.equals(row.get("savings_product_id"), product))
      return rejected(product, boundVersion, "policy.invalid");
    if (!ProductPolicyWorkflow.JSON.toJsonTree(active.coreSettings()).equals(core.capture(product)))
      return rejected(product, boundVersion, "core.snapshot.changed");
    var settings = ProductPolicyWorkflow.JSON.toJsonTree(active.coreSettings()).getAsJsonObject();
    if (!settings.has("currency_code")
        || settings.get("currency_code").isJsonNull()
        || !settings.has("currency_digits")
        || settings.get("currency_digits").isJsonNull()
        || !Objects.equals(
            settings.get("currency_code").getAsString(), account.getCurrency().getCode())
        || settings.get("currency_digits").getAsInt()
            != account.getCurrency().getDigitsAfterDecimal())
      return rejected(product, boundVersion, "currency.mismatch");
    return new Resolved(product, boundVersion, active.policy(), allow());
  }

  private static Long number(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private static Resolved rejected(long product, Long version, String code) {
    return new Resolved(product, version, null, unsupported(code, "policyVersion"));
  }
}
