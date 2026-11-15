/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.springframework.stereotype.Component;

/**
 * Deterministic financial calculations, with capture time kept outside the evidence fingerprint.
 */
@Component
@RequiredArgsConstructor
public class AnpaWriteOffPreviewService {
  private final AnpaHistoryService history;
  private final AnpaReceivableClassificationService classifier;
  private final AnpaAccountingReconciliationService reconciliation;

  Eligibility eligibility(Evidence e, List<Reason> reasons) {
    var l = e.loan();
    boolean review =
        l.nativeStatus() == LoanStatus.ACTIVE.getValue()
            || l.nativeStatus() == LoanStatus.CLOSED_WRITTEN_OFF.getValue()
            || l.disbursedOn() != null;
    return new Eligibility(
        l,
        review,
        l.nativeStatus() == LoanStatus.ACTIVE.getValue() && !l.chargedOff(),
        false,
        reasons);
  }

  Preview calculate(String tenant, Evidence e, LocalDate asOf) {
    var l = e.loan();
    var reasons = new LinkedHashSet<Reason>();
    if (l.disbursedOn() == null) reasons.add(Reason.LOAN_NOT_ELIGIBLE);
    if (l.disbursedOn() != null && asOf.isBefore(l.disbursedOn()))
      reasons.add(Reason.WRITE_OFF_DATE_INVALID);
    if (l.accountingRule() != AccountingRuleType.ACCRUAL_PERIODIC.getValue()
        && l.accountingRule() != AccountingRuleType.ACCRUAL_UPFRONT.getValue())
      reasons.add(Reason.ACCOUNTING_RULE_UNSUPPORTED);
    if (!asOf.equals(l.businessDate())) reasons.add(Reason.HISTORICAL_SNAPSHOT_UNAVAILABLE);
    if (e.truncated()) reasons.add(Reason.EVIDENCE_LIMIT_EXCEEDED);
    var h = history.resolve(l, asOf);
    reasons.addAll(h.blockers());
    var classification = classifier.classify(e);
    reasons.addAll(classification.blockers());
    if (e.journals().isEmpty()) reasons.add(Reason.GL_EVIDENCE_INCOMPLETE);
    if (e.journals().stream().anyMatch(j -> !l.currency().equals(j.currency()))
        || e.charges().stream().anyMatch(c -> !l.currency().equals(c.currency())))
      reasons.add(Reason.CURRENCY_MISMATCH);
    var journalTransactionIds = new HashSet<Long>();
    e.journals().forEach(j -> journalTransactionIds.add(j.loanTransactionId()));
    if (e.transactions().stream()
        .anyMatch(
            t ->
                !t.reversed()
                    && t.amount() != null
                    && t.amount().signum() != 0
                    && !journalTransactionIds.contains(t.id())))
      reasons.add(Reason.GL_EVIDENCE_INCOMPLETE);
    // Preserve manual postings as candidates only. A common textual reference is not proof of
    // reclassification.
    var candidates = e.journals().stream().filter(Journal::manualEntry).toList();
    reasons.add(Reason.UNAPPROVED_RECLASSIFICATION_RULE);
    reasons.add(Reason.HISTORICAL_MAPPING_UNAVAILABLE);
    var reconciled = reconciliation.reconcile(l, asOf, classification.buckets());
    if (reconciled.stream().anyMatch(r -> r.status() == ReconciliationStatus.DIFFERENCE))
      reasons.add(Reason.RECONCILIATION_DIFFERENCE);
    reasons.add(Reason.NATIVE_WRITE_OFF_INTEGRATION_UNVERIFIED);
    State state =
        reasons.contains(Reason.RECONCILIATION_DIFFERENCE)
            ? State.RECONCILIATION_REQUIRED
            : reasons.contains(Reason.NPA_HISTORY_UNAVAILABLE)
                    || reasons.contains(Reason.HISTORICAL_SNAPSHOT_UNAVAILABLE)
                    || reasons.contains(Reason.GL_EVIDENCE_INCOMPLETE)
                    || reasons.contains(Reason.EVIDENCE_LIMIT_EXCEEDED)
                ? State.INCOMPLETE_EVIDENCE
                : State.BLOCKED_PENDING_ACCOUNTING_POLICY;
    return new Preview(
        "1",
        tenant,
        asOf,
        Instant.now(),
        l,
        h,
        classification.buckets(),
        reconciled,
        candidates,
        ReclassificationStatus.UNVERIFIED_NO_NATIVE_RECLASSIFICATION_CONTRACT,
        null,
        state,
        false,
        List.copyOf(reasons),
        AnpaSupport.hash(e),
        AnpaSupport.hash(e.mappings()),
        e);
  }

  /**
   * Evaluates outstanding, independently verified origin allocations, never lifetime accruals. The
   * pinned adapter supplies no VERIFIED_SOURCE_EVIDENCE buckets, so unknowns stay null.
   */
  ExpenseAttribution expense(Loan loan, List<AccountBucket> buckets, PolicyResolution policy) {
    var rows = new ArrayList<ExpenseCategory>();
    var all = new LinkedHashSet<Reason>();
    BigDecimal principal = null;
    for (var category : Category.values()) {
      if (category == Category.OTHER) continue;
      var reasons = new LinkedHashSet<Reason>();
      BigDecimal outstanding =
          switch (category) {
            case PRINCIPAL -> loan.currentOutstanding().principal();
            case INTEREST -> loan.currentOutstanding().interest();
            case FEE_COMMISSION -> loan.currentOutstanding().fees();
            case PENALTY -> loan.currentOutstanding().penalties();
            case ITBMS, OTHER -> null;
          };
      if (!policy.explicitlyApproved()) reasons.add(Reason.ACCOUNTING_POLICY_UNAPPROVED);
      if (!policy.mappingsValidated()) reasons.add(Reason.ACCOUNTING_POLICY_INVALID);
      var confirmed =
          buckets.stream()
              .filter(
                  b ->
                      b.category() == category
                          && b.mappingStatus() == MappingStatus.VERIFIED_SOURCE_EVIDENCE)
              .toList();
      if (confirmed.isEmpty()) reasons.add(Reason.ORIGIN_ALLOCATION_UNAVAILABLE);
      BigDecimal regular = BigDecimal.ZERO,
          npa = BigDecimal.ZERO,
          reclassified = BigDecimal.ZERO,
          eligible = BigDecimal.ZERO,
          excluded = BigDecimal.ZERO,
          sum = BigDecimal.ZERO;
      var journalIds = new HashSet<Long>();
      for (var bucket : confirmed) {
        var amount = bucket.netGlReceivable();
        if (amount == null || amount.signum() < 0) {
          reasons.add(Reason.OUTSTANDING_AMOUNT_UNKNOWN);
          continue;
        }
        try {
          amount = amount.setScale(loan.currencyDigits(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
          reasons.add(Reason.CURRENCY_MISMATCH);
          continue;
        }
        if (bucket.journalIds().isEmpty()
            || bucket.accountIds().size() != 1
            || bucket.transactionIds().isEmpty()
            || bucket.journalIds().stream().anyMatch(id -> !journalIds.add(id)))
          reasons.add(Reason.GL_EVIDENCE_INCOMPLETE);
        sum = sum.add(amount);
        if (bucket.historicalOrigin() == Classification.REGULAR) regular = regular.add(amount);
        else if (bucket.historicalOrigin() == Classification.NPA) npa = npa.add(amount);
        else if (category != Category.PRINCIPAL) reasons.add(Reason.NPA_HISTORY_UNAVAILABLE);
        var rules =
            policy.definition() == null
                ? List.<PolicyRule>of()
                : policy.definition().rules().stream()
                    .filter(
                        r ->
                            r.category() == category
                                && r.historicalOrigin() == bucket.historicalOrigin()
                                && r.currentClassification() == bucket.currentClassification()
                                && bucket.accountIds().contains(r.sourceAccountId())
                                && (r.nativeMappingId() == null
                                    || bucket.mappingIds().contains(r.nativeMappingId())))
                    .toList();
        if (rules.size() != 1) {
          reasons.add(Reason.ACCOUNTING_POLICY_INVALID);
          continue;
        }
        var rule = rules.getFirst();
        Boolean allowed = rule.expenseEligible();
        if (bucket.historicalOrigin() == Classification.NPA
            && bucket.currentClassification() == Classification.REGULAR) {
          reclassified = reclassified.add(amount);
          allowed = rule.reclassifiedExpenseEligible();
          if (allowed == null) reasons.add(Reason.UNAPPROVED_RECLASSIFICATION_RULE);
        }
        if (category == Category.ITBMS && rule.taxComponentId() == null)
          reasons.add(Reason.TAX_TREATMENT_UNAPPROVED);
        if (allowed == null) reasons.add(Reason.ACCOUNTING_POLICY_INVALID);
        else if (allowed) eligible = eligible.add(amount);
        else excluded = excluded.add(amount);
      }
      // Tax is deliberately not inferred from fees. A verified tax allocation must identify its
      // own outstanding basis and its relationship to the native fee total before it can be used.
      if (outstanding == null) reasons.add(Reason.OUTSTANDING_AMOUNT_UNKNOWN);
      else {
        try {
          outstanding = outstanding.setScale(loan.currencyDigits(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
          reasons.add(Reason.CURRENCY_MISMATCH);
        }
        if (outstanding.signum() < 0 || (!confirmed.isEmpty() && sum.compareTo(outstanding) != 0))
          reasons.add(Reason.RECONCILIATION_DIFFERENCE);
      }
      boolean known = reasons.isEmpty();
      rows.add(
          new ExpenseCategory(
              category,
              outstanding,
              known ? regular : null,
              known ? npa : null,
              known ? reclassified : null,
              known ? eligible : null,
              known ? excluded : null,
              List.copyOf(reasons)));
      if (category == Category.PRINCIPAL && known) principal = eligible;
      all.addAll(reasons);
    }
    var receivables = rows.stream().filter(r -> r.category() != Category.PRINCIPAL).toList();
    boolean complete = receivables.stream().allMatch(r -> r.eligible() != null);
    BigDecimal eligible =
        complete
            ? receivables.stream()
                .map(ExpenseCategory::eligible)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
            : null;
    BigDecimal excluded =
        complete
            ? receivables.stream()
                .map(ExpenseCategory::excluded)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
            : null;
    all.add(Reason.NATIVE_WRITE_OFF_INTEGRATION_UNVERIFIED);
    return new ExpenseAttribution(
        List.copyOf(rows),
        principal,
        eligible,
        excluded,
        principal == null || eligible == null ? null : principal.add(eligible),
        false,
        List.copyOf(all));
  }
}
