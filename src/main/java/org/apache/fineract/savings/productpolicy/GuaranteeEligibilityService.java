/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.GuaranteeSweepData.*;
import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;

import java.math.BigDecimal;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Native snapshots and BE-08 bindings only. Purpose never establishes loan-linked authorization.
 */
@Service
@RequiredArgsConstructor
public class GuaranteeEligibilityService {
  private final JdbcTemplate jdbc;
  private final GuaranteeSweepAccess access;
  private final SavingsAccountRepositoryWrapper accounts;
  private final ProductTransactionPolicyResolver resolver;
  private final ProductTransactionPolicyEvaluator restrictions;

  @Transactional(propagation = Propagation.MANDATORY)
  public Decision evaluate(
      long client, long loan, long savings, BigDecimal amount, Operation operation) {
    int clientStatus = access.client(client);
    var loans =
        jdbc.queryForList(
            "SELECT"
                + " l.loan_status_id,l.currency_code,l.currency_digits,l.total_outstanding_derived,a.overdue_since_date_derived"
                + " AS overdue_since_date FROM m_loan l LEFT JOIN m_loan_arrears_aging a ON"
                + " a.loan_id=l.id WHERE l.id=? AND l.client_id=? AND l.group_id IS NULL",
            loan,
            client);
    if (loans.size() != 1) throw invalid("loanId", "Loan is unavailable for this client.");
    var l = loans.getFirst();
    int status = ((Number) l.get("loan_status_id")).intValue();
    String currency = (String) l.get("currency_code");
    int digits = ((Number) l.get("currency_digits")).intValue();
    BigDecimal outstanding = (BigDecimal) l.get("total_outstanding_derived");
    if (operation == Operation.APPLY
        && savings > 0
        && (amount == null
            || amount.signum() <= 0
            || !ProductTransactionPolicyEvaluator.validMoney(amount, digits)))
      throw invalid(
          "amount", "A positive exact amount within the loan currency precision is required.");
    var reasons = new ArrayList<String>();
    if (clientStatus != 300) reasons.add("CLIENT_NOT_ACTIVE");
    if (outstanding == null || outstanding.signum() < 0) reasons.add("LOAN_OBLIGATIONS_UNVERIFIED");
    if (operation == Operation.APPLY && status != 300) reasons.add("LOAN_NOT_ACTIVE");
    if (operation == Operation.APPLY
        && amount != null
        && outstanding != null
        && amount.compareTo(outstanding) > 0) reasons.add("REQUEST_EXCEEDS_NATIVE_OUTSTANDING");
    if (operation == Operation.RELEASE
        && (status != 600 || outstanding == null || outstanding.signum() != 0))
      reasons.add("LOAN_SETTLEMENT_NOT_VERIFIED");
    var overdue = (java.sql.Date) l.get("overdue_since_date");
    if (operation == Operation.APPLY
        && (overdue == null || !overdue.toLocalDate().isBefore(ProductPolicyWorkflow.today())))
      reasons.add("NO_NATIVE_PAST_DUE_EVIDENCE");
    Long version = null;
    BigDecimal withdrawable = null;
    String sourcePurpose = null, sourceCurrency = null;
    Integer sourceDigits = null;
    if (savings > 0) {
      var sources =
          jdbc.queryForList(
              "SELECT a.product_id,a.status_enum,a.sub_status_enum,a.deposit_type_enum FROM"
                  + " m_savings_account a JOIN m_client c ON c.id=a.client_id JOIN m_office o ON"
                  + " o.id=c.office_id WHERE a.id=? AND a.client_id=? AND a.group_id IS NULL AND"
                  + " o.hierarchy LIKE ?",
              savings,
              client,
              access.hierarchy() + "%");
      if (sources.size() != 1)
        throw invalid("savingsAccountId", "Source is unavailable for this client.");
      var row = sources.getFirst();
      var account = accounts.findOneWithNotFoundDetection(savings);
      sourceCurrency = account.getCurrency().getCode();
      sourceDigits = account.getCurrency().getDigitsAfterDecimal();
      if (!Long.valueOf(client).equals(account.clientId())
          || account.groupId() != null
          || account.productId() != ((Number) row.get("product_id")).longValue())
        throw invalid(
            "savingsAccountId", "Native source identity conflicts with persisted ownership.");
      if (!currency.equals(account.getCurrency().getCode())
          || digits != account.getCurrency().getDigitsAfterDecimal())
        reasons.add("CURRENCY_CONVERSION_UNSUPPORTED");
      if (((Number) row.get("status_enum")).intValue() != 300
          || row.get("sub_status_enum") == null
          || ((Number) row.get("sub_status_enum")).intValue() != 0
          || ((Number) row.get("deposit_type_enum")).intValue() != 100
          || !account.isActive()
          || !account.allowWithdrawal()) reasons.add("SOURCE_LIFECYCLE_OR_BLOCK_UNSUPPORTED");
      var resolved = resolver.resolve(account);
      version = resolved.version();
      if (!resolved.decision().allowed()) reasons.add(resolved.decision().code());
      var policy = resolved.policy();
      if (policy != null && policy.purpose() != null) sourcePurpose = policy.purpose().name();
      if (policy == null || version == null) reasons.add("APPROVED_SOURCE_POLICY_UNAVAILABLE");
      else {
        if (policy.purpose() == ProductPolicyData.Purpose.MAIN) {
          if (jdbc.queryForObject(
                      "SELECT COUNT(*) FROM m_savings_main_account_audit WHERE client_id=?"
                          + " AND savings_account_id=? AND action='BIND'",
                      Long.class,
                      client,
                      savings)
                  != 1
              || jdbc.queryForObject(
                      "SELECT COUNT(*) FROM m_savings_main_account_audit WHERE savings_account_id=?"
                          + " AND action='RETIRE'",
                      Long.class,
                      savings)
                  != 0) reasons.add("BE08_MAIN_RELATIONSHIP_UNAVAILABLE");
        } else if (policy.purpose() != ProductPolicyData.Purpose.GUARANTEED)
          reasons.add("SOURCE_PURPOSE_UNSUPPORTED");
        if (amount != null) {
          var decision =
              restrictions.evaluate(
                  policy,
                  ProductTransactionPolicyData.Operation.WITHDRAWAL,
                  amount,
                  account.getCurrency().getDigitsAfterDecimal(),
                  ProductPolicyWorkflow.today(),
                  ProductPolicyWorkflow.today());
          if (!decision.allowed()) reasons.add(decision.code());
        }
      }
      withdrawable = account.getWithdrawableBalance();
      if (withdrawable == null
          || !ProductTransactionPolicyEvaluator.validMoney(withdrawable, sourceDigits))
        reasons.add("NATIVE_WITHDRAWABLE_BALANCE_UNVERIFIED");
      else if (amount != null
          && currency.equals(sourceCurrency)
          && digits == sourceDigits
          && amount.compareTo(withdrawable) > 0)
        reasons.add("INSUFFICIENT_NATIVE_WITHDRAWABLE_FUNDS");
    } else reasons.add("NO_APPROVED_SOURCE_SELECTED");
    // BE-08 has no approved persisted guarantee relationship. Proposals cannot authorize funds,
    // establish shared-fund priority, or prove synchronization with ordinary repayments/reversals.
    reasons.addAll(BLOCKERS);
    return new Decision(
        client,
        loan,
        savings,
        ProductPolicyWorkflow.today(),
        currency,
        digits,
        version,
        amount,
        status,
        overdue == null ? null : overdue.toLocalDate(),
        outstanding,
        sourcePurpose,
        sourceCurrency,
        sourceDigits,
        withdrawable,
        null,
        State.BLOCKED_PENDING_POLICY,
        reasons.stream().distinct().toList());
  }
}
