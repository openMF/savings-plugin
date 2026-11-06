/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Binds an account to one approved snapshot; throws before the enclosing transaction commits. */
@Service
@RequiredArgsConstructor
public class ProductPolicyOpeningEnforcement {
  private final ProductPolicyWorkflow workflow;
  private final ProductPolicyCoreSnapshot core;
  private final JdbcTemplate jdbc;
  private final ProductPolicyValidation validation;

  @Transactional(propagation = Propagation.MANDATORY)
  public void validate(SavingsAccount account, String permission) {
    workflow.authorize(permission);
    long product = account.productId();
    // Serialize metadata enrollment with opening, including legacy deposit product types.
    jdbc.queryForList(
        "SELECT id FROM m_savings_product WHERE id=? FOR UPDATE", Long.class, product);
    var associations =
        jdbc.queryForList(
            "SELECT savings_product_id,version FROM m_savings_account_policy WHERE"
                + " savings_account_id=?",
            account.getId());
    if (!associations.isEmpty()
        && ((Number) associations.getFirst().get("savings_product_id")).longValue() != product)
      throw ProductPolicyValidation.invalid(
          "productId", "An account cannot switch away from its approved product policy.");
    boolean configured =
        jdbc.queryForObject(
                "SELECT COUNT(*) FROM m_savings_product_policy WHERE savings_product_id=?",
                Long.class,
                product)
            > 0;
    if (!configured) return; // Legacy products retain native behavior.
    workflow.lock(product);
    workflow.reconcile(product, ProductPolicyWorkflow.today());
    var bindings =
        jdbc.queryForList(
            "SELECT version FROM m_savings_account_policy WHERE savings_account_id=?",
            Long.class,
            account.getId());
    var version =
        bindings.isEmpty()
            ? workflow.resolve(product, ProductPolicyWorkflow.today())
            : workflow.version(product, bindings.getFirst(), ProductPolicyWorkflow.today());
    if (version == null || !version.status().equals("ACTIVE"))
      throw ProductPolicyValidation.invalid(
          "productId", "This configured product has no effective approved policy.");
    if (!ProductPolicyWorkflow.JSON
        .toJsonTree(version.coreSettings())
        .equals(core.capture(product)))
      throw ProductPolicyValidation.invalid(
          "productId",
          "Core product settings changed outside the approved snapshot; a new policy approval is"
              + " required.");
    core.validate(product, version.policy(), validation);
    core.validateAccount(
        account, ProductPolicyWorkflow.JSON.toJsonTree(version.coreSettings()).getAsJsonObject());
    var policy = version.policy();
    if (policy.interestMode() == ProductPolicyData.InterestMode.TIERED
        || policy.interestMode() == ProductPolicyData.InterestMode.REFERENCE_RATE)
      throw ProductPolicyValidation.invalid(
          "interestMode", "Account opening awaits the configured interest calculation engine.");
    if (policy.freezing() != ProductPolicyData.Freezing.NONE)
      throw ProductPolicyValidation.invalid(
          "freezing", "Account opening awaits the configured resource-freezing workflow.");
    if (account.clientId() == null || account.groupId() != null)
      throw ProductPolicyValidation.invalid(
          "clientId", "Configured products require an individual client.");
    // These purposes need loan/corporate orchestration supplied by later PRs. Never pretend that
    // merely recording the metadata establishes the guarantee or the corporate agreement.
    if (policy.purpose() == ProductPolicyData.Purpose.GUARANTEED
        || policy.purpose() == ProductPolicyData.Purpose.PLANNED_MAIN)
      throw ProductPolicyValidation.invalid(
          "purpose", "This purpose requires a dedicated loan or corporate opening workflow.");
    if (account.isActive()) ProductTransactionPolicyEvaluator.requireActivationSupported();
    jdbc.queryForList(
        "SELECT id FROM m_client WHERE id=? FOR UPDATE", Long.class, account.clientId());
    if (policy.multiplicity() == ProductPolicyData.Multiplicity.SINGLE) {
      long others =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM m_savings_account a LEFT JOIN m_savings_account_policy b ON"
                  + " b.savings_account_id=a.id WHERE a.client_id=? AND a.id<>? AND a.status_enum"
                  + " IN (100,200,300) AND (a.product_id=? OR b.purpose=?)",
              Long.class,
              account.clientId(),
              account.getId(),
              product,
              policy.purpose().name());
      if (others > 0)
        throw ProductPolicyValidation.invalid(
            "multiplicity",
            "An open or pending account already exists for this product or purpose.");
      jdbc.update(
          "DELETE FROM m_savings_policy_opening_slot WHERE client_id=? AND purpose=? AND"
              + " savings_account_id IN (SELECT id FROM m_savings_account WHERE status_enum NOT IN"
              + " (100,200,300))",
          account.clientId(),
          policy.purpose().name());
      var slots =
          jdbc.queryForList(
              "SELECT savings_account_id FROM m_savings_policy_opening_slot WHERE client_id=? AND"
                  + " purpose=?",
              Long.class,
              account.clientId(),
              policy.purpose().name());
      if (slots.isEmpty()) {
        try {
          jdbc.update(
              "INSERT INTO m_savings_policy_opening_slot(client_id,purpose,savings_account_id)"
                  + " VALUES (?,?,?)",
              account.clientId(),
              policy.purpose().name(),
              account.getId());
        } catch (org.springframework.dao.DuplicateKeyException e) {
          throw ProductPolicyValidation.invalid(
              "multiplicity", "A concurrent opening already reserved this client and purpose.");
        }
      } else if (!slots.getFirst().equals(account.getId()))
        throw ProductPolicyValidation.invalid(
            "multiplicity", "An opening reservation already exists for this client and purpose.");
    }
    if (bindings.isEmpty())
      jdbc.update(
          "INSERT INTO"
              + " m_savings_account_policy(savings_account_id,savings_product_id,version,purpose,bound_at)"
              + " VALUES (?,?,?,?,?)",
          account.getId(),
          product,
          version.version(),
          policy.purpose().name(),
          org.apache.fineract.infrastructure.core.service.DateUtils.getAuditLocalDateTime());
  }
}
