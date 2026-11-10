/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Diagnostic contracts. Null guarantee amounts mean unknown, never permission to spend. */
public final class GuaranteeSweepData {
  private GuaranteeSweepData() {}

  public enum Operation {
    APPLY,
    RELEASE
  }

  public enum State {
    PENDING,
    PROCESSING,
    BLOCKED_PENDING_POLICY,
    SKIPPED,
    FAILED,
    RECONCILIATION_REQUIRED
  }

  public static final List<String> BLOCKERS =
      List.of(
          "BE08_APPROVED_GUARANTEE_RELATIONSHIPS_UNAVAILABLE",
          "ALLOCATION_SHARED_FUNDS_AND_REPAYMENT_RULES_UNAPPROVED",
          "COLLECTIONS_AUTHORIZATION_RULES_UNAPPROVED",
          "MONTH_END_BOUNDARY_AND_DELINQUENCY_RULES_UNAPPROVED",
          "SETTLEMENT_AND_RELEASE_RULES_UNAPPROVED",
          "BE06_GUARANTEED_FINANCIAL_ROUTE_UNSUPPORTED",
          "NATIVE_REVERSAL_AND_RESERVATION_SYNCHRONIZATION_UNVERIFIED",
          "NATIVE_SWEEP_ACCOUNTING_UNVERIFIED");

  public record Capabilities(
      boolean eligibilityDiagnostics,
      boolean durableDiagnosticBatch,
      boolean manualSweep,
      boolean guaranteeAllocation,
      boolean guaranteeRelease,
      boolean automaticSweep,
      boolean financialEnforcement,
      List<String> blockers) {}

  public static Capabilities capabilities() {
    return new Capabilities(true, true, false, false, false, false, false, BLOCKERS);
  }

  public record Decision(
      long clientId,
      long loanId,
      long savingsAccountId,
      LocalDate businessDate,
      String currencyCode,
      int currencyDigits,
      Long policyVersion,
      BigDecimal requestedAmount,
      int loanStatus,
      LocalDate nativeOverdueSince,
      BigDecimal loanOutstanding,
      String sourcePurpose,
      String sourceCurrencyCode,
      Integer sourceCurrencyDigits,
      BigDecimal nativeWithdrawableBalance,
      BigDecimal availableGuaranteeFunds,
      State state,
      List<String> reasons) {}

  public record Request(
      String idempotencyKey, long savingsAccountId, BigDecimal amount, String reason) {}

  public record Receipt(
      String id,
      String tenant,
      long clientId,
      long loanId,
      long savingsAccountId,
      Operation operation,
      String mode,
      State state,
      LocalDate businessDate,
      Long policyVersion,
      BigDecimal requestedAmount,
      BigDecimal appliedAmount,
      String currencyCode,
      long actorId,
      String reasonCode,
      String nativeSavingsTransactionId,
      String nativeLoanTransactionId,
      String nativeTransferId,
      Decision evaluation) {}

  public record Submission(Receipt receipt, boolean duplicate) {}

  public record Run(
      String id,
      String tenant,
      String period,
      LocalDate businessDate,
      State state,
      String officeHierarchy,
      long cutoffLoanId,
      long afterLoanId,
      long evaluated) {}

  public record Source(
      long savingsAccountId, String purpose, long policyVersion, int nativeStatus) {}

  public record LoanSummary(long loanId, int nativeStatus, String currencyCode) {}

  public record Audit(
      long id,
      String executionId,
      long actorId,
      LocalDate businessDate,
      String event,
      String reasonCode,
      Decision evaluation,
      Reconciliation reconciliation,
      LocalDateTime recordedAt) {}

  public record Allocation(
      long id,
      long savingsAccountId,
      long policyVersion,
      String currencyCode,
      String state,
      BigDecimal proposedAmount,
      BigDecimal reservedAmount,
      BigDecimal consumedAmount,
      BigDecimal releasedAmount) {}

  public record RunOutcome(
      long loanId, String executionId, State state, String reasonCode, Decision evaluation) {}

  public record Reconciliation(
      String executionId,
      String status,
      boolean nativeReferencesMatch,
      boolean repaymentReversed,
      boolean savingsReversed,
      boolean journalEntriesPresent,
      boolean journalEntriesBalanced,
      boolean nativeAccountingVerified,
      boolean financialRetryAllowed) {}
}
