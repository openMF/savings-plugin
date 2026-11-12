/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Tenant-routed JDBC and native account identities must agree before any relationship is used. */
@Component
@RequiredArgsConstructor
public class PlannedSavingsAccess {
  public static final String READ = "READ_PLANNED_SAVINGS";
  public static final String CONFIGURE = "CONFIGURE_PLANNED_SAVINGS";
  public static final String POCKET = "MANAGE_SAVINGS_POCKET";
  public static final String REVIEW = "REVIEW_PLANNED_SAVINGS";
  private final PlatformSecurityContext security;
  private final JdbcTemplate jdbc;
  private final SavingsAccountRepositoryWrapper accounts;
  private final ProductTransactionPolicyResolver resolver;
  private final ProductPolicyWorkflow workflow;
  private final ProductPolicyCoreSnapshot core;
  private final ProductPolicyValidation validation;

  long authorize(String permission) {
    var user = security.authenticatedUser();
    user.validateHasPermissionTo(permission);
    MainAccountAuditService.requireContext();
    if (user.getOffice() == null || user.getOffice().getHierarchy() == null)
      throw new AccessDeniedException("An office-scoped staff principal is required.");
    return user.getId();
  }

  Map<String, Object> client(long id, boolean lock, boolean active) {
    var rows =
        jdbc.queryForList(
            "SELECT c.status_enum,c.legal_form_enum FROM m_client c JOIN m_office o ON o.id=c.office_id"
                + " WHERE c.id=? AND o.hierarchy LIKE ?"
                + (lock ? " FOR UPDATE" : ""),
            id,
            security.authenticatedUser().getOffice().getHierarchy() + "%");
    if (id <= 0 || rows.size() != 1) throw new ClientNotFoundException(id);
    if (active && number(rows.getFirst(), "status_enum") != 300)
      throw invalid("clientId", "An active client is required for configuration changes.");
    return rows.getFirst();
  }

  ProductPolicyWorkflowData.Version policy(
      long product, long version, ProductPolicyData.Purpose purpose) {
    workflow.lock(product);
    var approved = workflow.resolve(product, ProductPolicyWorkflow.today());
    if (approved == null
        || approved.version() != version
        || !"ACTIVE".equals(approved.status())
        || approved.checkerId() == null
        || approved.policy() == null
        || approved.policy().purpose() != purpose)
      throw invalid(
          "policyVersion",
          "An effective approved policy with the requested purpose/version is required.");
    if (!ProductPolicyWorkflow.JSON
        .toJsonTree(approved.coreSettings())
        .equals(core.capture(product)))
      throw invalid("policyVersion", "Core settings changed outside the approved policy snapshot.");
    core.validate(product, approved.policy(), validation);
    return approved;
  }

  record Account(
      Map<String, Object> row,
      SavingsAccount nativeAccount,
      ProductTransactionPolicyData.Resolved resolved) {
    long id() {
      return nativeAccount.getId();
    }

    long product() {
      return number(row, "product_id");
    }

    int status() {
      return (int) number(row, "status_enum");
    }

    boolean open() {
      return status() == 100 || status() == 200 || status() == 300;
    }
  }

  Account account(long client, long id, boolean lock) {
    var rows =
        jdbc.queryForList(
            "SELECT product_id,client_id,group_id,deposit_type_enum,status_enum,sub_status_enum,"
                + "currency_code,currency_digits,account_balance_derived,total_interest_earned_derived,"
                + "total_interest_posted_derived FROM m_savings_account WHERE id=? AND client_id=?"
                + (lock ? " FOR UPDATE" : ""),
            id,
            client);
    if (rows.size() != 1)
      throw invalid("savingsAccountId", "Account is unavailable for this client.");
    var row = rows.getFirst();
    var nativeAccount = accounts.findOneWithNotFoundDetection(id);
    if (row.get("group_id") != null
        || number(row, "deposit_type_enum") != 100
        || !Long.valueOf(client).equals(nativeAccount.clientId())
        || nativeAccount.groupId() != null
        || nativeAccount.productId() != number(row, "product_id")
        || !Objects.equals(row.get("currency_code"), nativeAccount.getCurrency().getCode())
        || number(row, "currency_digits") != nativeAccount.getCurrency().getDigitsAfterDecimal())
      throw invalid(
          "savingsAccountId",
          "Native individual account identity/currency conflicts with ownership.");
    return new Account(row, nativeAccount, resolver.resolve(nativeAccount));
  }

  void requirePolicy(Account account, ProductPolicyData.Purpose purpose, long version) {
    account.resolved().decision().requireAllowed();
    var policy = account.resolved().policy();
    if (!account.open()
        || policy == null
        || account.resolved().version() == null
        || account.resolved().version() != version
        || policy.purpose() != purpose
        || (purpose == ProductPolicyData.Purpose.POCKET
            && policy.multiplicity() != ProductPolicyData.Multiplicity.UNLIMITED))
      throw invalid(
          "policyVersion",
          "An open/pending native account bound to the requested policy purpose/version is required.");
  }

  PlannedSavingsData.NativeAccount snapshot(Account account, long client) {
    var r = account.resolved();
    var reasons = new ArrayList<String>();
    if (!r.decision().allowed()) reasons.add(r.decision().code());
    if (r.policy() == null) reasons.add("APPROVED_ACCOUNT_POLICY_UNAVAILABLE");
    else {
      if (r.policy().freezing() != ProductPolicyData.Freezing.NONE)
        reasons.add("FREEZING_ENFORCEMENT_UNVERIFIED");
      if (r.policy().interestMode() == ProductPolicyData.InterestMode.TIERED
          || r.policy().interestMode() == ProductPolicyData.InterestMode.REFERENCE_RATE)
        reasons.add("INTEREST_ENGINE_UNAVAILABLE");
    }
    if (account.status() != 300 || number(account.row(), "sub_status_enum") != 0)
      reasons.add("NATIVE_ACCOUNT_NOT_ACTIVE_OR_BLOCKED");
    reasons.add("BE10_FINANCIAL_EXECUTION_DISABLED");
    return new PlannedSavingsData.NativeAccount(
        account.id(),
        client,
        account.product(),
        r.version(),
        r.policy() == null ? null : r.policy().purpose().name(),
        (String) account.row().get("currency_code"),
        (int) number(account.row(), "currency_digits"),
        account.status(),
        (int) number(account.row(), "sub_status_enum"),
        (BigDecimal) account.row().get("account_balance_derived"),
        account.nativeAccount().getWithdrawableBalance(),
        (BigDecimal) account.row().get("total_interest_earned_derived"),
        (BigDecimal) account.row().get("total_interest_posted_derived"),
        r.policy() == null ? null : r.policy().interestMode().name(),
        ProductPolicyWorkflow.today(),
        reasons);
  }

  static long number(Map<String, Object> row, String field) {
    if (!(row.get(field) instanceof Number number))
      throw invalid(field, "Required native metadata is unavailable.");
    return number.longValue();
  }
}
