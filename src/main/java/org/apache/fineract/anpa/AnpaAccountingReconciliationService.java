/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Reconciliation differences are reported; never allocated to regular income to force a balance.
 */
@Component
public class AnpaAccountingReconciliationService {
  List<Reconciliation> reconcile(Loan loan, LocalDate asOf, List<AccountBucket> buckets) {
    return java.util.Arrays.stream(Category.values())
        .map(
            category -> {
              BigDecimal nativeAmount =
                  switch (category) {
                    case PRINCIPAL -> loan.currentOutstanding().principal();
                    case INTEREST -> loan.currentOutstanding().interest();
                    case FEE_COMMISSION -> loan.currentOutstanding().fees();
                    case PENALTY -> loan.currentOutstanding().penalties();
                    case ITBMS, OTHER -> null;
                  };
              var balance =
                  buckets.stream()
                      .filter(
                          b ->
                              b.category() == category
                                  && b.currentClassification()
                                      == Classification.NATIVE_UNSEGREGATED)
                      .map(AccountBucket::netGlReceivable)
                      .filter(java.util.Objects::nonNull)
                      .findFirst()
                      .orElse(null);
              var difference =
                  asOf.equals(loan.businessDate()) && balance != null && nativeAmount != null
                      ? nativeAmount.subtract(balance)
                      : null;
              return new Reconciliation(
                  category,
                  nativeAmount,
                  balance,
                  difference,
                  difference == null
                      ? ReconciliationStatus.UNVERIFIED
                      : difference.signum() == 0
                          ? ReconciliationStatus.MATCHED_CURRENT_GL_ONLY
                          : ReconciliationStatus.DIFFERENCE);
            })
        .toList();
  }

  /** Native portions and journals remain distinct from approved expense expectations. */
  List<WriteOffReconciliation> writeOffs(
      Evidence e, ExpenseAttribution expected, Preview original, String assessmentId) {
    return e.transactions().stream()
        .filter(
            t ->
                t.type()
                    == org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType.WRITEOFF
                        .getValue())
        .map(t -> writeOff(e, t, expected, original, assessmentId))
        .toList();
  }

  private WriteOffReconciliation writeOff(
      Evidence e,
      Transaction t,
      ExpenseAttribution expected,
      Preview original,
      String assessmentId) {
    var blockers = new LinkedHashSet<Reason>();
    // Current mappings are useful evidence, but never proof of mappings at the original posting.
    blockers.add(Reason.HISTORICAL_MAPPING_UNAVAILABLE);
    if (assessmentId == null) blockers.add(Reason.ORIGINAL_ASSESSMENT_UNAVAILABLE);
    if (e.truncated()) blockers.add(Reason.EVIDENCE_LIMIT_EXCEEDED);
    if (t.reversed()) blockers.add(Reason.REVERSED_WRITE_OFF);
    var journals =
        e.journals().stream().filter(j -> Objects.equals(j.loanTransactionId(), t.id())).toList();
    var reversalIds =
        journals.stream().map(Journal::reversalId).filter(Objects::nonNull).distinct().toList();
    if (t.reversed() && reversalIds.isEmpty())
      blockers.add(Reason.REVERSAL_JOURNAL_LINEAGE_UNAVAILABLE);
    var reversedIds =
        e.journals().stream()
            .map(Journal::reversalId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
    var active =
        journals.stream().filter(j -> !j.reversed() && !reversedIds.contains(j.id())).toList();
    boolean valid =
        !t.reversed()
            && !e.truncated()
            && !active.isEmpty()
            && active.stream()
                .allMatch(
                    j ->
                        j.amount() != null
                            && j.amount().signum() >= 0
                            && e.loan().currency().equals(j.currency())
                            && !j.manualEntry()
                            && (j.direction() == 1 || j.direction() == 2));
    BigDecimal debit =
        valid
            ? active.stream()
                .filter(j -> j.direction() == 2)
                .map(Journal::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
            : null;
    BigDecimal credit =
        valid
            ? active.stream()
                .filter(j -> j.direction() == 1)
                .map(Journal::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
            : null;
    if (valid && debit.compareTo(credit) != 0) valid = false;
    if (!valid) blockers.add(Reason.GL_EVIDENCE_INCOMPLETE);
    var recoveries =
        e.transactions().stream()
            .filter(
                r ->
                    !r.reversed()
                        && r.type()
                            == org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType
                                .RECOVERY_REPAYMENT
                                .getValue()
                        && (!r.date().isBefore(t.date()))
                        && r.id() > t.id())
            .map(Transaction::id)
            .toList();
    if (!recoveries.isEmpty()) blockers.add(Reason.RECOVERY_AFTER_WRITE_OFF);
    if (original != null
        && (original.asOf().isAfter(t.date())
            || original.loan().loanId() != e.loan().loanId()
            || !original.mappingFingerprint().equals(AnpaSupport.hash(e.mappings()))))
      blockers.add(Reason.STALE_SOURCE_EVIDENCE);
    var rows = new ArrayList<JournalComparison>();
    for (var category :
        List.of(Category.PRINCIPAL, Category.INTEREST, Category.FEE_COMMISSION, Category.PENALTY)) {
      int placeholder =
          switch (category) {
            case PRINCIPAL -> 2;
            case INTEREST -> 7;
            case FEE_COMMISSION -> 8;
            case PENALTY -> 9;
            default -> throw new IllegalStateException();
          };
      BigDecimal portion =
          switch (category) {
            case PRINCIPAL -> t.principal();
            case INTEREST -> t.interest();
            case FEE_COMMISSION -> t.fees();
            case PENALTY -> t.penalties();
            default -> null;
          };
      var maps =
          e.mappings().stream()
              .filter(m -> m.financialAccountType() == placeholder && base(m))
              .toList();
      var reasons = new LinkedHashSet<Reason>();
      Long account = maps.size() == 1 ? maps.getFirst().accountId() : null;
      if (maps.isEmpty()) reasons.add(Reason.RECEIVABLE_MAPPING_MISSING);
      if (maps.size() > 1
          || (account != null
              && e.mappings().stream()
                  .anyMatch(
                      m ->
                          Objects.equals(m.accountId(), account)
                              && m.financialAccountType() != placeholder)))
        reasons.add(Reason.AMBIGUOUS_MAPPING);
      if (maps.size() == 1
          && (!Objects.equals(maps.getFirst().accountType(), 1)
              || !Objects.equals(maps.getFirst().accountUsage(), 1)
              || !Boolean.FALSE.equals(maps.getFirst().disabled())))
        reasons.add(Reason.INVALID_RECEIVABLE_ACCOUNT);
      var lines = active.stream().filter(j -> account != null && j.accountId() == account).toList();
      BigDecimal actual =
          valid && account != null && reasons.isEmpty()
              ? lines.stream()
                  .map(j -> j.direction() == 1 ? j.amount() : j.amount().negate())
                  .reduce(BigDecimal.ZERO, BigDecimal::add)
              : null;
      var difference = actual != null && portion != null ? actual.subtract(portion) : null;
      if (difference != null && difference.signum() != 0)
        reasons.add(Reason.RECONCILIATION_DIFFERENCE);
      rows.add(
          new JournalComparison(
              category,
              account,
              lines.stream().map(Journal::id).toList(),
              portion,
              portion,
              actual,
              difference,
              difference == null
                  ? "MISSING_HISTORICAL_EVIDENCE"
                  : difference.signum() == 0
                      ? "MATCHED_NATIVE_PORTION_CURRENT_MAPPING"
                      : "CURRENT_MAPPING_DIFFERENCE",
              List.copyOf(reasons)));
      blockers.addAll(reasons);
    }
    var expenseMaps =
        e.mappings().stream()
            .filter(
                m ->
                    m.financialAccountType() == 6
                        && m.accountId() != null
                        && Objects.equals(m.accountType(), 5)
                        && Objects.equals(m.accountUsage(), 1)
                        && Boolean.FALSE.equals(m.disabled()))
            .toList();
    var expenseAccounts = expenseMaps.stream().map(Mapping::accountId).distinct().toList();
    var expenses = active.stream().filter(j -> expenseAccounts.contains(j.accountId())).toList();
    BigDecimal actualExpense =
        valid && !expenses.isEmpty()
            ? expenses.stream()
                .map(j -> j.direction() == 2 ? j.amount() : j.amount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
            : null;
    if (actualExpense == null) blockers.add(Reason.GL_EVIDENCE_INCOMPLETE);
    // An unsegregated historical write-off cannot acquire an expected policy amount from today's
    // zero outstanding balance or from lifetime accruals. Native integration must supply that
    // snapshot.
    BigDecimal expectedExpense = null;
    blockers.add(Reason.ORIGIN_ALLOCATION_UNAVAILABLE);
    blockers.add(Reason.NATIVE_WRITE_OFF_INTEGRATION_UNVERIFIED);
    return new WriteOffReconciliation(
        t.id(),
        t.date(),
        t.reversed(),
        journals.stream().map(Journal::id).toList(),
        reversalIds,
        recoveries,
        t.amount(),
        actualExpense,
        expectedExpense,
        null,
        List.copyOf(rows),
        t.reversed() ? "REVERSED_WRITE_OFF" : "MISSING_HISTORICAL_EVIDENCE",
        List.copyOf(blockers));
  }

  private static boolean base(Mapping m) {
    return m.chargeId() == null
        && m.paymentType() == null
        && m.chargeOffReason() == null
        && m.writeOffReason() == null
        && m.capitalizedClassification() == null
        && m.buydownClassification() == null;
  }
}
