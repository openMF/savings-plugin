/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import java.math.BigDecimal;
import java.util.List;

/** Immutable, non-binding gross projections; none of these records authorizes execution. */
public final class InvestmentSimulationData {
  private InvestmentSimulationData() {}

  public static final String ENGINE_VERSION = "BE03-native-periods-v1/fineract-0.0.1628-bc1c598";
  public static final int MAX_REINVESTMENTS = 10;
  public static final int MAX_HORIZON_DAYS = 3650;
  public static final int MAX_SCHEDULE_PERIODS = 240;

  public enum Status {
    COMPLETE_GROSS_PROJECTION,
    PARTIAL_PROJECTION,
    CALCULATION_BLOCKED,
    NO_APPLICABLE_RATE
  }

  public enum FundingMode {
    QUOTE_ONLY,
    VERIFY_AVAILABLE_FUNDS
  }

  public record FundingContext(FundingMode mode) {}

  public record Request(
      long productId,
      BigDecimal investmentAmount,
      int termDays,
      String startDate,
      boolean capitalizationEnabled,
      InvestmentProductData.InterestPayoutMode interestPayoutMode,
      boolean automaticReinvestment,
      int requestedReinvestmentCount,
      FundingContext fundingContext,
      String requestKey) {}

  /** Native financial settings are read separately so old BE-01/02 snapshots stay compatible. */
  public record NativeSettings(
      Integer calculationType,
      Integer daysInYear,
      Integer compoundingPeriod,
      Integer postingPeriod,
      BigDecimal minimumBalanceForInterest,
      Integer financialYearBeginningMonth,
      Boolean postingAtCurrentPeriodEnd,
      String roundingMode,
      Integer monetaryMathPrecision) {}

  public record Convention(
      NativeSettings nativeSettings,
      int currencyDigits,
      Integer currencyMultiple,
      String rateInterpretation,
      String accrualInterval,
      String maturityRule,
      String paymentCalendar,
      String roundingStage,
      String renewalTreatment,
      String calculationVersion) {}

  /** Accrual is [start, end); payment and principal return are separate projected events. */
  public record Period(
      int periodNumber,
      String periodStartDate,
      String periodEndDateExclusive,
      String nativePostingDate,
      BigDecimal grossInterest,
      BigDecimal interestCarriedIntoCompounding,
      BigDecimal interestCarriedOutOfCompounding) {}

  public record Payment(
      int paymentNumber,
      String paymentDate,
      BigDecimal interestPayout,
      BigDecimal capitalizedOnRenewal,
      BigDecimal principalReturned,
      String status) {}

  public record Cycle(
      int cycleNumber,
      String startDate,
      String maturityDate,
      BigDecimal openingPrincipal,
      InvestmentRateData.Configuration publishedRateSnapshot,
      InvestmentRateData.Selection rateSelection,
      Status status,
      BigDecimal grossInterest,
      BigDecimal projectedMaturityValue,
      BigDecimal interestPayoutTotal,
      BigDecimal capitalizedOnRenewal,
      BigDecimal nextPrincipal,
      List<Period> accrualSchedule,
      List<Payment> paymentSchedule,
      List<String> blockers) {}

  public record FundingAssessment(
      FundingMode requestedMode, String status, boolean balanceVerified, boolean fundsReserved) {}

  public record Capabilities(
      boolean grossCalculation,
      boolean taxIncluded,
      boolean netYieldAvailable,
      boolean financialExecutionSupported,
      boolean nativeContractRateGuaranteed,
      boolean customerAssociationSupported,
      boolean availableFundsVerificationSupported,
      boolean historicalSnapshotsSupported) {}

  public record Result(
      String simulationId,
      Status status,
      Request request,
      InvestmentProductData.InvestmentType investmentType,
      String currencyCode,
      Long policyRevision,
      InvestmentProductData.Policy policySnapshot,
      InvestmentProductData.NativeProduct nativeProductSnapshot,
      Convention calculationConvention,
      List<Cycle> cycles,
      BigDecimal grossInterest,
      BigDecimal projectedMaturityValue,
      String finalMaturityDate,
      FundingAssessment fundingAssessment,
      List<String> warnings,
      List<String> blockers,
      Capabilities capabilities,
      long actorId,
      String tenantIdentifier,
      String businessDate,
      String createdAtUtc) {}

  public record Template(
      String createPermission,
      String readPermission,
      String historyPermission,
      int maximumReinvestments,
      int maximumHorizonDays,
      int maximumSchedulePeriods,
      List<Integer> supportedDayBases,
      List<Integer> supportedCompoundingPeriods,
      List<Integer> supportedPostingPeriods,
      List<String> requiredConventions,
      List<String> restrictions,
      Capabilities capabilities) {}

  public record HistoryEntry(
      String simulationId,
      long productId,
      Status status,
      String createdAtUtc,
      String calculationVersion) {}

  public static Capabilities capabilities() {
    return new Capabilities(true, false, false, false, false, false, false, true);
  }
}
