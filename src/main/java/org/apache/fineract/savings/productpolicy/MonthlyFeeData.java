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

/** Diagnostic evidence only. No state represents a native charge, payment or GL confirmation. */
public final class MonthlyFeeData {
  private MonthlyFeeData() {}

  public enum Outcome {
    NOT_ELIGIBLE,
    NO_FEE_CONFIGURED,
    BLOCKED,
    UNKNOWN_OUTCOME
  }

  public record Decision(
      Long policyVersion,
      BigDecimal minimumMonthlyAverageBalance,
      Long feeChargeId,
      BigDecimal configuredFeeAmount,
      Outcome outcome,
      String reason) {}

  public record Run(
      String id,
      String tenant,
      String period,
      LocalDate businessDate,
      String status,
      long cutoffAccountId,
      long afterAccountId,
      long evaluated,
      long blocked,
      long unknown,
      long createdBy,
      LocalDateTime createdAt) {}

  public record Assessment(
      long accountId,
      long productId,
      String period,
      Long policyVersion,
      BigDecimal minimumMonthlyAverageBalance,
      Long feeChargeId,
      BigDecimal configuredFeeAmount,
      Outcome outcome,
      String reason,
      String financialOutcome,
      long assessedBy,
      LocalDateTime recordedAt) {}

  public record Page<T>(List<T> items, long nextAfterId, boolean hasMore) {}

  public record History(List<Run> items, String nextBeforePeriod, boolean hasMore) {}

  public record Reconciliation(
      String runId,
      String status,
      long unknownOutcomes,
      boolean nativeAccountingVerified,
      boolean financialRetryAllowed) {}

  public record Capabilities(
      boolean diagnosticAssessmentSupported,
      boolean approvedAverageCalculationSupported,
      boolean postingSupported,
      boolean collectionSupported,
      boolean diagnosticBatchAdapterSupported,
      boolean productionScheduleSupported,
      boolean accountingReconciliationSupported,
      boolean reversalSupported,
      boolean fullNativeCoverage,
      List<String> blockers) {}

  public static Capabilities capabilities() {
    return new Capabilities(
        true,
        false,
        false,
        false,
        true,
        false,
        false,
        false,
        false,
        List.of(
            "BALANCE_BASIS_AND_HISTORY_UNAPPROVED",
            "FORMULA_AND_DENOMINATOR_UNAPPROVED",
            "PARTIAL_PERIOD_RULES_UNAPPROVED",
            "HISTORICAL_POLICY_SELECTION_UNAPPROVED",
            "CUTOFF_AND_SCHEDULE_UNAPPROVED",
            "DUE_DATE_AND_COLLECTION_UNAPPROVED",
            "EXEMPTIONS_AND_CORRECTIONS_UNAPPROVED",
            "BE06_FEE_DEBIT_COVERAGE_UNSUPPORTED",
            "NATIVE_ACCOUNTING_NOT_VERIFIED"));
  }
}
