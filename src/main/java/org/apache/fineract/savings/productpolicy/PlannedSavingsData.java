/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** BE-10 metadata and diagnostic contracts. None of these records is a financial authorization. */
public final class PlannedSavingsData {
  private PlannedSavingsData() {}

  public enum FrequencyUnit {
    DAYS,
    WEEKS,
    MONTHS
  }

  public enum ShortMonthRule {
    SKIP,
    LAST_DAY
  }

  public enum ArrangementStatus {
    CONFIGURATION_ONLY,
    CANCELLED
  }

  public enum ReviewKind {
    MATURITY,
    EARLY_WITHDRAWAL,
    EMPLOYEE_EXIT,
    CLOSURE
  }

  public static final List<String> BLOCKERS =
      List.of(
          "EMPLOYER_AUTHORIZATION_AND_EMPLOYEE_CONSENT_INTEGRATION_UNAVAILABLE",
          "PLANNED_OPENING_AND_MULTIPLICITY_RULES_UNAPPROVED",
          "CONTRIBUTION_EXECUTION_AND_MATCHING_RULES_UNAPPROVED",
          "FREEZING_ADJUSTMENT_REVERSAL_AND_CLOSURE_COVERAGE_UNVERIFIED",
          "MATURITY_EXIT_INTEREST_PENALTY_AND_SETTLEMENT_RULES_UNAPPROVED");

  public record Capabilities(
      boolean arrangementConfiguration,
      boolean scheduleProjection,
      boolean pocketRelationships,
      boolean nativeAccountCreation,
      boolean payrollExecution,
      boolean contributionScheduler,
      boolean frozenFundRelease,
      boolean maturitySettlement,
      boolean pocketTransfer,
      boolean accountClosure,
      List<String> blockers) {}

  public static Capabilities capabilities() {
    return new Capabilities(
        true, true, true, false, false, false, false, false, false, false, BLOCKERS);
  }

  public record Configuration(
      long savingsProductId,
      long policyVersion,
      long employerClientId,
      String arrangementReference,
      BigDecimal contributionAmount,
      FrequencyUnit frequencyUnit,
      int frequencyInterval,
      ShortMonthRule shortMonthRule,
      LocalDate startDate,
      int termDays,
      LocalDate maturityDate,
      ArrangementStatus status) {}

  public record Arrangement(
      String id,
      long clientId,
      long revision,
      Configuration configuration,
      Long savingsAccountId,
      String state,
      boolean financialExecutionEnabled,
      List<String> blockers) {}

  public record Contribution(
      long revision,
      int sequence,
      LocalDate dueDate,
      BigDecimal expectedAmount,
      Long nativeTransactionId,
      String status,
      boolean executionEnabled) {}

  public record NativeAccount(
      long savingsAccountId,
      long clientId,
      long savingsProductId,
      Long policyVersion,
      String purpose,
      String currency,
      int currencyDigits,
      int nativeStatus,
      int nativeSubStatus,
      BigDecimal balance,
      BigDecimal withdrawableBalance,
      BigDecimal nativeInterestEarned,
      BigDecimal nativeInterestPosted,
      String interestMode,
      LocalDate businessDate,
      List<String> blockers) {}

  public record Pocket(
      long savingsAccountId,
      long clientId,
      long mainSavingsAccountId,
      long revision,
      long policyVersion,
      long mainPolicyVersion,
      String name,
      String relationshipStatus,
      NativeAccount nativeAccount) {}

  public record Eligibility(
      String state, boolean executionEnabled, LocalDate businessDate, List<String> blockers) {}

  public record Review(
      String idempotencyKey,
      String arrangementId,
      long revision,
      ReviewKind kind,
      LocalDate businessDate,
      String state,
      boolean executionEnabled,
      Long nativeTransactionId,
      List<String> blockers) {}
}
