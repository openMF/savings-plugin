/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Administrative contracts. Approval covers an immutable plan, never financial execution. */
public final class RatePropagationData {
  private RatePropagationData() {}

  public record Plan(long policyVersion, LocalDate effectiveDate) {}

  public record Create(String requestKey, long policyVersion, LocalDate effectiveDate) {}

  public record Action(long expectedRevision, String reason) {}

  public record Capabilities(
      boolean planningSupported,
      boolean authorizationSupported,
      boolean batchPersistenceSupported,
      boolean resumptionSupported,
      boolean financialExecutionSupported,
      boolean tieredFinancialExecutionSupported,
      boolean referenceRateFinancialExecutionSupported,
      List<String> blockers) {}

  public static Capabilities capabilities() {
    return new Capabilities(
        true,
        true,
        true,
        true,
        false,
        false,
        false,
        List.of(
            "FINERACT_EFFECTIVE_DATED_ACCOUNT_REPRICING_UNSUPPORTED",
            "CONTRACTUAL_REPRICING_AND_ACCOUNT_EXCEPTION_POLICY_UNAPPROVED",
            "CUSTOMER_NOTIFICATION_DELIVERY_UNAVAILABLE",
            "ACCOUNT_EXCEPTION_AND_RATE_REDUCTION_RULES_UNAPPROVED",
            "MID_CYCLE_LATE_EXECUTION_AND_FUTURE_SCHEDULE_POLICY_UNAPPROVED"));
  }

  public record Source(
      long savingsProductId,
      long policyVersion,
      LocalDate policyEffectiveDate,
      long policyMakerId,
      Long policyCheckerId,
      Instant policyDecidedAt,
      ProductPolicyData.Policy policy,
      JsonObject coreSettings,
      ReferenceRateData.Fixing benchmarkFixing,
      BigDecimal nominalAnnualRatePreview) {}

  public record Population(
      long totalMatching,
      long totalEligible,
      long totalExcluded,
      long totalBlocked,
      Map<String, Long> reasons) {}

  public record Preview(
      Source source,
      LocalDate effectiveDate,
      LocalDateTime populationCutoff,
      LocalDate populationBusinessDate,
      long cutoffAccountId,
      Population population,
      String snapshotStatus,
      Capabilities capabilities) {}

  public record Counters(
      long totalDiscovered,
      long totalEligible,
      long totalExcluded,
      long populationBlocked,
      long totalPending,
      long totalProcessed,
      long totalApplied,
      long totalUnchanged,
      long totalSkipped,
      long totalBlocked,
      long totalFailed,
      long totalUnknownOutcome,
      Map<String, Long> reasons) {}

  public record Batch(
      String id,
      String tenantId,
      long savingsProductId,
      long policyVersion,
      LocalDate effectiveDate,
      LocalDateTime populationCutoff,
      LocalDate populationBusinessDate,
      long cutoffAccountId,
      Source source,
      String sourceHash,
      String changeClassification,
      String status,
      boolean planningComplete,
      long discoveryCheckpoint,
      String populationHash,
      String authorizedHash,
      long revision,
      long makerId,
      Long submitterId,
      Long checkerId,
      LocalDateTime createdAt,
      LocalDateTime submittedAt,
      LocalDateTime decidedAt,
      LocalDateTime lastProgressAt,
      String reason,
      Counters counters,
      String completionMeaning,
      Capabilities capabilities) {}

  /** Only financial configuration and technical identifiers, never client names or balances. */
  public record AccountSnapshot(
      long accountId,
      long savingsProductId,
      int status,
      int depositType,
      int subStatus,
      LocalDateTime createdAt,
      LocalDate activatedOn,
      BigDecimal nominalAnnualRate,
      Long previousPolicyVersion,
      String previousPolicyStatus,
      ProductPolicyData.Policy previousPolicy,
      JsonObject previousCore,
      JsonObject accountSettings,
      boolean conflictingPlan) {}

  public record Eligibility(String decision, String reason) {}

  public record AccountOutcome(
      long savingsAccountId,
      String operationKey,
      Long previousPolicyVersion,
      long targetPolicyVersion,
      LocalDate targetEffectiveDate,
      String eligibility,
      String eligibilityReason,
      String outcome,
      int attemptCount,
      LocalDateTime processedAt,
      String errorCategory,
      String confirmedOperationId,
      AccountSnapshot snapshot) {}

  public record Audit(
      long id,
      long actorId,
      String action,
      String previousState,
      String newState,
      long revision,
      LocalDateTime recordedAt,
      String detail,
      String snapshotHash) {}

  public record Attempt(
      long id,
      long savingsAccountId,
      int attemptNumber,
      long actorId,
      LocalDateTime recordedAt,
      String outcome,
      String errorCategory) {}

  public record Page<T>(List<T> items, long nextAfterId, boolean hasMore) {}

  public record Blocked(String code, String message, Batch batch) {}
}
