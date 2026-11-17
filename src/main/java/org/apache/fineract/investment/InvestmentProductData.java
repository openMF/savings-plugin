/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import java.math.BigDecimal;
import java.util.List;

/** Investment metadata only; native Fineract remains the financial authority. */
public final class InvestmentProductData {
  private InvestmentProductData() {}

  public enum InvestmentType {
    PROMISSORY_NOTE,
    CEDE
  }

  public enum InterestPayoutMode {
    AT_MATURITY,
    MONTHLY,
    CAPITALIZE_ON_RENEWAL
  }

  public enum PrincipalSettlementMode {
    AT_MATURITY
  }

  public enum RenewalRatePolicy {
    RATE_EFFECTIVE_AT_RENEWAL
  }

  public enum PenaltyMode {
    NONE,
    NATIVE,
    VERSIONED_REFERENCE
  }

  public enum Status {
    UNCONFIGURED,
    DRAFT
  }

  public record TermRenewalLimit(Integer termDays, Integer maximumReinvestments) {}

  public record CancellationPolicyReference(String policyCode, Long revision) {}

  public record Policy(
      InvestmentType investmentType,
      String currencyCode,
      Integer minimumTermDays,
      Integer maximumTermDays,
      Integer termMultipleDays,
      BigDecimal minimumInvestmentAmount,
      BigDecimal maximumInvestmentAmount,
      Boolean automaticReinvestmentEnabled,
      Integer maximumReinvestments,
      List<TermRenewalLimit> maximumReinvestmentsByTerm,
      RenewalRatePolicy renewalRatePolicy,
      Boolean capitalizationEligible,
      Boolean capitalizationEnabled,
      InterestPayoutMode interestPayoutMode,
      PrincipalSettlementMode principalSettlementMode,
      Boolean earlyCancellationAllowed,
      PenaltyMode penaltyMode,
      CancellationPolicyReference cancellationPolicyReference,
      Boolean penaltyFreeNonRenewalSupported) {}

  public record WriteRequest(Long expectedRevision, Policy policy) {}

  /** Values as stored in core, including native frequency enum IDs (days=0, weeks=1). */
  public record NativeTerms(
      Integer minimum,
      Integer minimumUnit,
      Integer maximum,
      Integer maximumUnit,
      Integer multiple,
      Integer multipleUnit) {}

  public record NativePenalty(boolean applicable, BigDecimal interest, Integer interestOnType) {}

  public record AccountingMapping(
      long mappingId,
      Integer financialAccountType,
      Long glAccountId,
      boolean disabled,
      Integer classification,
      Integer usage,
      Long paymentType,
      Long chargeId) {}

  public record NativeProduct(
      long productId,
      String name,
      String currencyCode,
      int currencyDigits,
      Integer currencyMultiple,
      NativeTerms terms,
      BigDecimal minimumDepositAmount,
      BigDecimal maximumDepositAmount,
      BigDecimal defaultDepositAmount,
      NativePenalty preClosure,
      int accountingRule,
      boolean dormancyTracking,
      Integer interestPostingPeriod,
      Integer interestCompoundingPeriod,
      boolean withholdTax,
      Long taxGroupId,
      List<AccountingMapping> accountingMappings) {}

  public record Capabilities(
      boolean configurationSupported,
      boolean nativeFixedDepositProductSupported,
      boolean termValidationSupported,
      boolean amountValidationSupported,
      boolean accountingMappingVerificationSupported,
      boolean approvalSupported,
      boolean activationSupported,
      boolean rateManagementSupported,
      boolean investmentOpeningSupported,
      boolean financialExecutionSupported,
      boolean automaticRenewalExecutionSupported,
      boolean earlyCancellationExecutionSupported) {}

  public record Template(
      Capabilities capabilities,
      List<InvestmentType> investmentTypes,
      List<InterestPayoutMode> interestPayoutModes,
      List<PrincipalSettlementMode> principalSettlementModes,
      List<PenaltyMode> penaltyModes,
      List<RenewalRatePolicy> renewalRatePolicies,
      int promissoryNoteMinimumTermDays,
      int promissoryNoteMaximumTermDays,
      int cedeSuggestedMinimumTermDays,
      int cedeTermMultipleDays,
      String readPermission,
      String managePermission,
      List<String> restrictions) {}

  public record Assessment(
      boolean policyValidated,
      boolean accountingMappingValidated,
      boolean nativeConfigurationChanged,
      List<String> blockers) {}

  public record Configuration(
      long productId,
      long revision,
      Status status,
      Policy policy,
      NativeProduct nativeProduct,
      Assessment validation,
      Capabilities capabilities) {}

  public record ValidationResult(
      long productId,
      long currentRevision,
      Policy policy,
      NativeProduct nativeProduct,
      Assessment validation,
      Capabilities capabilities) {}

  public record Audit(
      long productId,
      long previousRevision,
      long revision,
      String operation,
      long actorId,
      String recordedAtUtc,
      String businessDate,
      List<String> changedFields,
      Policy previousConfiguration,
      Policy newConfiguration,
      NativeProduct nativeSnapshot) {}

  public static Capabilities capabilities() {
    return new Capabilities(
        true, true, true, true, true, false, false, false, false, false, false, false);
  }
}
