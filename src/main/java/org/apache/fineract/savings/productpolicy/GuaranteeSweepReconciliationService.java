/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.GuaranteeSweepData.*;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Factual native-reference checks. Balanced journal totals alone never verify sweep accounting. */
@Service
@RequiredArgsConstructor
public class GuaranteeSweepReconciliationService {
  private final JdbcTemplate jdbc;
  private final GuaranteeSweepAccess access;
  private final GuaranteeSweepExecutionService executions;
  private final GuaranteeSweepAuditService audit;

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','RECONCILE_GUARANTEE_SWEEP')")
  @Transactional
  public Reconciliation inspect(long client, long loan, String id) {
    long actor = access.authorize(GuaranteeSweepAccess.RECONCILE);
    access.client(client);
    executions.requireLoan(client, loan);
    var receipt = executions.find(client, loan, id);
    if (receipt.nativeLoanTransactionId() == null
        && receipt.nativeSavingsTransactionId() == null
        && receipt.nativeTransferId() == null
        && receipt.state() == State.BLOCKED_PENDING_POLICY) {
      var result =
          new Reconciliation(id, "NOT_ATTEMPTED", false, false, false, false, false, false, false);
      audit.recordReconciliation(receipt, result, actor);
      return result;
    }
    boolean references = false,
        loanReversed = false,
        savingsReversed = false,
        present = false,
        balanced = false;
    if (receipt.nativeLoanTransactionId() != null
        && receipt.nativeSavingsTransactionId() != null
        && receipt.nativeTransferId() != null) {
      var loans =
          jdbc.queryForList(
              "SELECT amount,is_reversed FROM m_loan_transaction WHERE id=? AND loan_id=? AND"
                  + " transaction_type_enum=?",
              Long.valueOf(receipt.nativeLoanTransactionId()),
              loan,
              LoanTransactionType.REPAYMENT.getValue());
      var savings =
          jdbc.queryForList(
              "SELECT amount,is_reversed FROM m_savings_account_transaction WHERE id=? AND"
                  + " savings_account_id=? AND transaction_type_enum=2",
              Long.valueOf(receipt.nativeSavingsTransactionId()),
              receipt.savingsAccountId());
      var transfers =
          jdbc.queryForList(
              "SELECT id FROM m_account_transfer_transaction WHERE account_transfer_details_id=?"
                  + " AND from_savings_transaction_id=? AND to_loan_transaction_id=? AND"
                  + " is_reversed=false",
              Long.valueOf(receipt.nativeTransferId()),
              Long.valueOf(receipt.nativeSavingsTransactionId()),
              Long.valueOf(receipt.nativeLoanTransactionId()));
      if (loans.size() == 1 && savings.size() == 1) {
        loanReversed = Boolean.TRUE.equals(loans.getFirst().get("is_reversed"));
        savingsReversed = Boolean.TRUE.equals(savings.getFirst().get("is_reversed"));
        references =
            transfers.size() == 1
                && !loanReversed
                && !savingsReversed
                && receipt.appliedAmount() != null
                && receipt.appliedAmount().compareTo((BigDecimal) loans.getFirst().get("amount"))
                    == 0
                && receipt.appliedAmount().compareTo((BigDecimal) savings.getFirst().get("amount"))
                    == 0;
      }
      var journals =
          jdbc.queryForList(
              "SELECT currency_code,loan_transaction_id,savings_transaction_id,COUNT(*) AS"
                  + " entries,SUM(CASE WHEN type_enum=1 THEN amount WHEN type_enum=2 THEN -amount"
                  + " ELSE 0 END) AS net,SUM(CASE WHEN type_enum IN (1,2) THEN 0 ELSE 1 END) AS"
                  + " invalid FROM acc_gl_journal_entry WHERE (loan_transaction_id=? OR"
                  + " savings_transaction_id=?) AND reversed=false GROUP BY"
                  + " currency_code,loan_transaction_id,savings_transaction_id",
              Long.valueOf(receipt.nativeLoanTransactionId()),
              Long.valueOf(receipt.nativeSavingsTransactionId()));
      present =
          journals.stream().anyMatch(r -> r.get("loan_transaction_id") != null)
              && journals.stream().anyMatch(r -> r.get("savings_transaction_id") != null);
      balanced =
          present
              && journals.stream()
                  .allMatch(
                      r ->
                          receipt.currencyCode().equals(r.get("currency_code"))
                              && ((Number) r.get("entries")).longValue() >= 2
                              && ((Number) r.get("invalid")).longValue() == 0
                              && ((BigDecimal) r.get("net")).signum() == 0);
    }
    // No automatic allocation update, release, reversal or financial retry. Actual native sweep
    // mapping and policy/reversal synchronization must be verified before enabling such behavior.
    var result =
        new Reconciliation(
            id,
            "RECONCILIATION_REQUIRED",
            references,
            loanReversed,
            savingsReversed,
            present,
            balanced,
            false,
            false);
    audit.recordReconciliation(receipt, result, actor);
    return result;
  }
}
