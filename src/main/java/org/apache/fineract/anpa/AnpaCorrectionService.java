/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the Mozilla Public License, v. 2.0. If a copy of the MPL
 * was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;

import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Verification and correction proposals only. No native financial writer is a dependency. */
@Service
@RequiredArgsConstructor
@Transactional(
    transactionManager = "jdbcTransactionManager",
    readOnly = true,
    isolation = Isolation.REPEATABLE_READ)
public class AnpaCorrectionService {
  private final AnpaAccountingAccess access;
  private final AnpaAccountingEvidenceService evidence;
  private final AnpaAccountingPolicyService policies;
  private final AnpaWriteOffPreviewService preview;
  private final AnpaAccountingReconciliationService reconciliation;
  private final AnpaAssessmentAuditService assessments;
  private final AnpaControlStore store;

  public CorrectionReview get(long loanId, String assessmentId) {
    access.authorize(AnpaAccountingAccess.RECONCILE);
    return calculate(evidence.loan(loanId), assessmentId);
  }

  public ReclassificationEvidence reclassification(long loanId) {
    access.authorize(AnpaAccountingAccess.READ);
    var loan = evidence.loan(loanId);
    return reclassifications(evidence.collect(loan, loan.businessDate()));
  }

  public List<ControlRecord> list(long loanId, String kind, long after, int limit) {
    access.authorize(AnpaAccountingAccess.RECONCILE);
    access.authorize(AnpaAccountingAccess.AUDIT);
    evidence.loan(loanId);
    return store.list(loanId, kind, after, limit);
  }

  public ControlRecord record(long loanId, String id, String kind) {
    access.authorize(AnpaAccountingAccess.RECONCILE);
    access.authorize(AnpaAccountingAccess.AUDIT);
    evidence.loan(loanId);
    var record = store.get(loanId, id);
    if (!record.kind().equals(kind))
      throw new jakarta.ws.rs.NotFoundException("Record kind not found.");
    return record;
  }

  @Transactional(
      transactionManager = "jdbcTransactionManager",
      isolation = Isolation.REPEATABLE_READ)
  public ControlRecord assess(long loanId, String json) {
    long actor = access.authorize(AnpaAccountingAccess.CREATE);
    access.authorize(AnpaAccountingAccess.RECONCILE);
    var loan = evidence.loan(loanId);
    var request =
        AnpaSupport.object(
            json, Set.of("idempotencyKey", "assessmentId", "supersedesId", "reason"));
    String key = AnpaSupport.key(request);
    String hash = AnpaSupport.controlHash(List.of(loanId, actor, "RECONCILIATION", request));
    var replay = store.replay(loanId, key, hash);
    if (replay != null) return replay;
    String previous = supersedes(loanId, request, "RECONCILIATION");
    String original =
        request.has("assessmentId")
            ? AnpaSupport.uuid(AnpaSupport.text(request, "assessmentId"))
            : null;
    var result = calculate(loan, original);
    return store.append(
        loan,
        actor,
        "RECONCILIATION",
        key,
        hash,
        previous,
        null,
        null,
        previous,
        result.sourceFingerprint(),
        request,
        result);
  }

  @Transactional(
      transactionManager = "jdbcTransactionManager",
      isolation = Isolation.REPEATABLE_READ)
  public ControlRecord propose(long loanId, String json) {
    long actor = access.authorize(AnpaAccountingAccess.PROPOSE);
    access.authorize(AnpaAccountingAccess.RECONCILE);
    access.authorize(AnpaAccountingAccess.AUDIT);
    var loan = evidence.loan(loanId);
    var request =
        AnpaSupport.object(
            json,
            Set.of("idempotencyKey", "reconciliationId", "reason", "supersedesId", "operation"));
    String key = AnpaSupport.key(request);
    String reason = AnpaSupport.text(request, "reason");
    AdjustmentKind operation = AdjustmentKind.JOURNAL_ADJUSTMENT;
    if (request.has("operation")) {
      try {
        operation = AdjustmentKind.valueOf(AnpaSupport.text(request, "operation"));
      } catch (IllegalArgumentException ex) {
        throw AnpaSupport.invalid("operation", "Supported proposal operation required.");
      }
    }
    String reviewId = AnpaSupport.uuid(AnpaSupport.text(request, "reconciliationId"));
    String hash = AnpaSupport.controlHash(List.of(loanId, actor, "ADJUSTMENT_PROPOSAL", request));
    var replay = store.replay(loanId, key, hash);
    if (replay != null) return replay;
    String previous = supersedes(loanId, request, "ADJUSTMENT_PROPOSAL");
    var saved = store.get(loanId, reviewId);
    if (!saved.kind().equals("RECONCILIATION"))
      throw AnpaSupport.invalid("reconciliationId", "Saved reconciliation required.");
    var prior = AnpaSupport.JSON.fromJson(saved.result(), CorrectionReview.class);
    var current = calculate(loan, prior.originalAssessmentId());
    var blockers = new LinkedHashSet<>(current.blockers());
    if (!prior.sourceFingerprint().equals(current.sourceFingerprint())
        || !AnpaSupport.hash(prior.policy()).equals(AnpaSupport.hash(current.policy())))
      blockers.add(Reason.STALE_SOURCE_EVIDENCE);
    blockers.add(Reason.NATIVE_ADJUSTMENT_INTEGRATION_UNVERIFIED);
    blockers.add(Reason.ACCOUNTING_PERIOD_TREATMENT_UNAPPROVED);
    var proposal =
        new AdjustmentProposal(
            "ADJUSTMENT_BLOCKED",
            operation,
            reason,
            reviewId,
            previous,
            false,
            current,
            List.copyOf(blockers));
    return store.append(
        loan,
        actor,
        "ADJUSTMENT_PROPOSAL",
        key,
        hash,
        reviewId,
        null,
        null,
        previous,
        current.sourceFingerprint(),
        request,
        proposal);
  }

  private String supersedes(long loanId, com.google.gson.JsonObject request, String kind) {
    if (!request.has("supersedesId")) {
      if (request.has("reason") && kind.equals("RECONCILIATION"))
        throw AnpaSupport.invalid(
            "supersedesId", "Correction reason requires a superseded reconciliation.");
      return null;
    }
    var id = AnpaSupport.uuid(AnpaSupport.text(request, "supersedesId"));
    AnpaSupport.text(request, "reason");
    access.authorize(AnpaAccountingAccess.AUDIT);
    var prior = store.get(loanId, id);
    if (!prior.kind().equals(kind))
      throw AnpaSupport.invalid("supersedesId", "Same record kind and loan required.");
    return id;
  }

  private CorrectionReview calculate(Loan loan, String assessmentId) {
    var sources = evidence.collect(loan, loan.businessDate());
    Preview original =
        assessmentId == null ? null : assessments.get(loan.loanId(), assessmentId).result();
    var current = preview.calculate(access.tenant(), sources, loan.businessDate());
    var policy =
        policies.resolve(sources, original == null ? loan.businessDate() : original.asOf());
    var attribution =
        preview.expense(
            original == null ? loan : original.loan(),
            original == null ? current.receivables() : original.receivables(),
            policy);
    var writeOffs = reconciliation.writeOffs(sources, attribution, original, assessmentId);
    var reasons = new LinkedHashSet<>(current.blockers());
    reasons.addAll(policy.blockers());
    reasons.addAll(attribution.blockers());
    writeOffs.forEach(w -> reasons.addAll(w.blockers()));
    if (assessmentId == null) reasons.add(Reason.ORIGINAL_ASSESSMENT_UNAVAILABLE);
    var period = evidence.accountingPeriod(loan);
    return new CorrectionReview(
        loan.loanId(),
        loan.businessDate(),
        Instant.now(),
        assessmentId,
        policy,
        attribution,
        writeOffs,
        reclassifications(sources),
        new CorrectionCapabilities(
            AnpaAccountingPolicyService.complete(policy),
            false,
            false,
            false,
            true,
            true,
            true,
            false,
            false,
            List.copyOf(reasons)),
        AnpaSupport.hash(List.of(current.sourceFingerprint(), policy, period)),
        current.mappingFingerprint(),
        sources,
        period,
        List.copyOf(reasons));
  }

  private static ReclassificationEvidence reclassifications(Evidence e) {
    return new ReclassificationEvidence(
        "UNVERIFIED_CANDIDATES_ONLY",
        e.journals().stream().filter(Journal::manualEntry).toList(),
        List.of(
            Reason.RECLASSIFICATION_EVIDENCE_UNVERIFIED,
            Reason.UNAPPROVED_RECLASSIFICATION_RULE,
            Reason.NPA_HISTORY_UNAVAILABLE));
  }
}
