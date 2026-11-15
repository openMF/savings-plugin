/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Diagnostic contracts. Null monetary values mean unknown, never an assumed zero. */
public final class AnpaAccountingData {
  private AnpaAccountingData() {}

  public enum Category {
    PRINCIPAL,
    INTEREST,
    FEE_COMMISSION,
    ITBMS,
    PENALTY,
    OTHER
  }

  public enum Classification {
    REGULAR,
    NPA,
    NATIVE_UNSEGREGATED,
    UNKNOWN
  }

  public enum MappingStatus {
    NATIVE_UNSEGREGATED,
    VERIFIED_SOURCE_EVIDENCE,
    MISSING,
    AMBIGUOUS,
    INVALID_ACCOUNT,
    UNCLASSIFIED_SOURCE,
    UNAPPROVED_TAX_SEMANTICS,
    NATIVE_MAPPING_UNAVAILABLE,
    APPROVED_SEGREGATION_UNAVAILABLE
  }

  public enum ReconciliationStatus {
    UNVERIFIED,
    MATCHED_CURRENT_GL_ONLY,
    DIFFERENCE
  }

  public enum ReclassificationStatus {
    UNVERIFIED_NO_NATIVE_RECLASSIFICATION_CONTRACT
  }

  public enum Reason {
    LOAN_NOT_ELIGIBLE,
    ACCOUNTING_RULE_UNSUPPORTED,
    NPA_HISTORY_UNAVAILABLE,
    RECEIVABLE_MAPPING_MISSING,
    NPA_RECEIVABLE_MAPPING_MISSING,
    GL_EVIDENCE_INCOMPLETE,
    UNAPPROVED_RECLASSIFICATION_RULE,
    WRITE_OFF_DATE_INVALID,
    HISTORICAL_SNAPSHOT_UNAVAILABLE,
    CURRENCY_MISMATCH,
    NATIVE_WRITE_OFF_INTEGRATION_UNVERIFIED,
    AMBIGUOUS_MAPPING,
    INVALID_RECEIVABLE_ACCOUNT,
    EVIDENCE_LIMIT_EXCEEDED,
    ORIGIN_ALLOCATION_UNAVAILABLE,
    TAX_TREATMENT_UNAPPROVED,
    RECONCILIATION_DIFFERENCE,
    HISTORICAL_MAPPING_UNAVAILABLE,
    ACCOUNTING_POLICY_MISSING,
    ACCOUNTING_POLICY_AMBIGUOUS,
    ACCOUNTING_POLICY_UNAPPROVED,
    ACCOUNTING_POLICY_INVALID,
    OUTSTANDING_AMOUNT_UNKNOWN,
    RECLASSIFICATION_EVIDENCE_UNVERIFIED,
    NATIVE_ADJUSTMENT_INTEGRATION_UNVERIFIED,
    ORIGINAL_ASSESSMENT_UNAVAILABLE,
    STALE_SOURCE_EVIDENCE,
    REVERSED_WRITE_OFF,
    REVERSAL_JOURNAL_LINEAGE_UNAVAILABLE,
    RECOVERY_AFTER_WRITE_OFF,
    ACCOUNTING_PERIOD_TREATMENT_UNAPPROVED
  }

  public enum State {
    VERIFIED_PREVIEW,
    INCOMPLETE_EVIDENCE,
    BLOCKED_PENDING_ACCOUNTING_POLICY,
    RECONCILIATION_REQUIRED
  }

  public record Capabilities(
      boolean eligibilityDiagnostics,
      boolean historicalNpaEvidence,
      boolean receivableClassification,
      boolean feeAndTaxClassification,
      boolean writeOffPreview,
      boolean historicalAssessments,
      boolean accountingReconciliation,
      boolean nativeWriteOffInterceptionVerified,
      boolean financialWriteOffCorrection,
      CorrectionCapabilities correctionControls) {}

  public record Template(
      String nativeVersion,
      Capabilities capabilities,
      List<Category> categories,
      List<Reason> blockerCodes,
      String historicalEvidenceAvailability) {}

  public record Balances(
      BigDecimal principal,
      BigDecimal interest,
      BigDecimal fees,
      BigDecimal penalties,
      BigDecimal total) {}

  public record Loan(
      long loanId,
      Long clientId,
      Long groupId,
      long productId,
      long officeId,
      int nativeStatus,
      boolean currentNpa,
      boolean chargedOff,
      int accountingRule,
      String currency,
      int currencyDigits,
      LocalDate submittedOn,
      LocalDate disbursedOn,
      LocalDate writtenOffOn,
      LocalDate businessDate,
      Balances currentOutstanding) {}

  public record Eligibility(
      Loan loan,
      boolean diagnosticEligible,
      boolean nativeWriteOffCandidate,
      boolean financiallyExecutable,
      List<Reason> blockers) {}

  public record Period(LocalDate from, LocalDate through, Classification state, String source) {}

  public record Transition(LocalDate date, boolean npa, String sourceReference) {}

  public record History(
      boolean currentNpa,
      LocalDate observedOn,
      List<Transition> confirmedTransitions,
      List<Period> knownPeriods,
      List<Period> unknownPeriods,
      List<Reason> blockers) {}

  public record Mapping(
      long mappingId,
      int financialAccountType,
      Long accountId,
      Long chargeId,
      Long paymentType,
      Long chargeOffReason,
      Long writeOffReason,
      Long capitalizedClassification,
      Long buydownClassification,
      Integer accountType,
      Integer accountUsage,
      Boolean disabled) {}

  public record Transaction(
      long id,
      int type,
      LocalDate date,
      LocalDate submittedOn,
      boolean reversed,
      BigDecimal amount,
      BigDecimal principal,
      BigDecimal interest,
      BigDecimal fees,
      BigDecimal penalties,
      LocalDate reversedOn,
      Instant createdAt,
      Instant modifiedAt) {}

  public record Relation(
      long id, long fromTransaction, Long toTransaction, Long toCharge, int type) {}

  public record Journal(
      long id,
      Long loanTransactionId,
      String postingReference,
      long accountId,
      LocalDate date,
      int direction,
      BigDecimal amount,
      String currency,
      boolean reversed,
      Long reversalId,
      boolean manualEntry,
      LocalDate submittedOn,
      Instant createdAt,
      Instant modifiedAt) {}

  public record Charge(
      long id,
      long chargeId,
      boolean penalty,
      boolean active,
      boolean waived,
      BigDecimal amount,
      BigDecimal paid,
      BigDecimal waivedAmount,
      BigDecimal writtenOff,
      BigDecimal outstanding,
      BigDecimal tax,
      String currency) {}

  public record ChargePayment(long id, long transactionId, long loanChargeId, BigDecimal amount) {}

  public record Tax(
      long id,
      long loanChargeId,
      long taxComponentId,
      BigDecimal amount,
      Long debitAccountId,
      Long creditAccountId) {}

  public record Evidence(
      Loan loan,
      List<Mapping> mappings,
      List<Transaction> transactions,
      List<Relation> relations,
      List<Journal> journals,
      List<Charge> charges,
      List<ChargePayment> chargePayments,
      List<Tax> taxes,
      boolean truncated) {}

  public record AccountBucket(
      Category category,
      Classification currentClassification,
      MappingStatus mappingStatus,
      List<Long> mappingIds,
      List<Long> accountIds,
      List<Long> journalIds,
      List<Long> transactionIds,
      BigDecimal netGlReceivable,
      BigDecimal expenseEligible,
      Classification historicalOrigin) {}

  public record Reconciliation(
      Category category,
      BigDecimal currentNativeOutstanding,
      BigDecimal mappedGlBalance,
      BigDecimal difference,
      ReconciliationStatus status) {}

  public record Preview(
      String schemaVersion,
      String tenant,
      LocalDate asOf,
      Instant calculatedAt,
      Loan loan,
      History history,
      List<AccountBucket> receivables,
      List<Reconciliation> reconciliation,
      List<Journal> possibleReclassifications,
      ReclassificationStatus reclassificationStatus,
      BigDecimal proposedReceivableExpense,
      State state,
      boolean financiallyExecutable,
      List<Reason> blockers,
      String sourceFingerprint,
      String mappingFingerprint,
      Evidence evidence) {}

  public record SaveRequest(
      String idempotencyKey, String asOf, String supersedesAssessmentId, String correctionReason) {}

  public record Assessment(
      long sequence,
      String id,
      long loanId,
      long productId,
      long officeId,
      long actorId,
      LocalDate businessDate,
      Instant recordedAt,
      String supersedesAssessmentId,
      String correctionReason,
      Preview result) {}

  public record AssessmentSummary(
      long sequence,
      String id,
      long actorId,
      LocalDate asOf,
      Instant recordedAt,
      String sourceFingerprint) {}

  /** Approval is a policy decision, never evidence of native financial integration. */
  public record PolicyRule(
      Category category,
      Classification historicalOrigin,
      Classification currentClassification,
      long sourceAccountId,
      Long nativeMappingId,
      long destinationAccountId,
      Boolean expenseEligible,
      Boolean reclassifiedExpenseEligible,
      Long taxComponentId) {}

  public record PolicyDefinition(
      int version,
      int accountingRule,
      String currency,
      LocalDate effectiveFrom,
      LocalDate effectiveThrough,
      String approvalReference,
      List<PolicyRule> rules) {}

  public record PolicyApproval(
      String policyId,
      long makerId,
      String mappingFingerprint,
      String accountFingerprint,
      String approvalReference) {}

  public record PolicyResolution(
      String policyId,
      PolicyDefinition definition,
      boolean explicitlyApproved,
      boolean mappingsValidated,
      boolean financiallyExecutable,
      List<Reason> blockers) {}

  public record ExpenseCategory(
      Category category,
      BigDecimal outstanding,
      BigDecimal regularOutstanding,
      BigDecimal npaOutstanding,
      BigDecimal reclassifiedNpa,
      BigDecimal eligible,
      BigDecimal excluded,
      List<Reason> blockers) {}

  public record ExpenseAttribution(
      List<ExpenseCategory> categories,
      BigDecimal principalExpense,
      BigDecimal eligibleReceivables,
      BigDecimal excludedReceivables,
      BigDecimal totalExpense,
      boolean financiallyExecutable,
      List<Reason> blockers) {}

  public record CorrectionCapabilities(
      boolean npaAccountingPolicyVerified,
      boolean npaHistoricalAttribution,
      boolean nativeReclassification,
      boolean correctedWriteOffExecution,
      boolean nativeJournalReconciliation,
      boolean legacyLoanReconciliation,
      boolean adjustmentProposalSupport,
      boolean financialAdjustmentExecution,
      boolean reversalIntegration,
      List<Reason> blockers) {}

  public record JournalComparison(
      Category category,
      Long accountId,
      List<Long> journalIds,
      BigDecimal transactionPortion,
      BigDecimal expected,
      BigDecimal actual,
      BigDecimal difference,
      String state,
      List<Reason> blockers) {}

  public record WriteOffReconciliation(
      long transactionId,
      LocalDate writeOffDate,
      boolean reversed,
      List<Long> journalIds,
      List<Long> reversalJournalIds,
      List<Long> recoveryTransactionIds,
      BigDecimal nativeWriteOffAmount,
      BigDecimal actualExpenseDebit,
      BigDecimal expectedExpenseDebit,
      BigDecimal expenseDifference,
      List<JournalComparison> categories,
      String state,
      List<Reason> blockers) {}

  public record ReclassificationEvidence(
      String state, List<Journal> candidates, List<Reason> blockers) {}

  public record AccountingPeriod(
      LocalDate latestClosedThrough,
      boolean calculationDateOpen,
      boolean adjustmentPeriodTreatmentApproved) {}

  public record CorrectionReview(
      long loanId,
      LocalDate calculationDate,
      Instant calculatedAt,
      String originalAssessmentId,
      PolicyResolution policy,
      ExpenseAttribution attribution,
      List<WriteOffReconciliation> writeOffs,
      ReclassificationEvidence reclassification,
      CorrectionCapabilities capabilities,
      String sourceFingerprint,
      String mappingFingerprint,
      Evidence evidence,
      AccountingPeriod accountingPeriod,
      List<Reason> blockers) {}

  public enum AdjustmentKind {
    JOURNAL_ADJUSTMENT,
    NPA_RECLASSIFICATION,
    NPA_RECLASSIFICATION_REVERSAL,
    NATIVE_WRITE_OFF_REVERSAL
  }

  public record AdjustmentProposal(
      String state,
      AdjustmentKind operation,
      String reason,
      String reconciliationId,
      String supersedesId,
      boolean financiallyExecutable,
      CorrectionReview review,
      List<Reason> blockers) {}

  /** An append-only plugin control event; result is typed JSON, not a financial command. */
  public record ControlRecord(
      long sequence,
      String id,
      String kind,
      long loanId,
      long productId,
      long officeId,
      long actorId,
      LocalDate businessDate,
      Instant recordedAt,
      String parentId,
      String sourceFingerprint,
      com.google.gson.JsonElement request,
      com.google.gson.JsonElement result) {}
}
