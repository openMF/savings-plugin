/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;

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
}
