/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import java.math.BigDecimal;
import java.util.List;

/** Exact pricing metadata. None of these records authorize a financial transaction. */
public final class InvestmentRateData {
  private InvestmentRateData() {}

  public enum ValidityMode {
    IMMEDIATE,
    SCHEDULED
  }

  public enum CampaignType {
    NEW_INVESTMENT_OFFER,
    CALENDAR_PREFERENTIAL,
    FINAL_RENEWAL_PERIOD
  }

  public enum Status {
    DRAFT,
    APPROVED_SCHEDULED,
    APPROVED_EFFECTIVE,
    SUPERSEDED
  }

  public enum SelectionStatus {
    SELECTED_CONFIGURATION_ONLY,
    NO_APPLICABLE_RATE
  }

  /** Lower inclusive; upper exclusive, except an explicitly inclusive final product maximum. */
  public record AmountBand(BigDecimal lower, BigDecimal upper, boolean upperInclusive) {}

  /** Both bounds inclusive; only policy-eligible increments within the band can be selected. */
  public record TermBand(int minimumDays, int maximumDays) {}

  public record Cell(int amountBand, int termBand, BigDecimal annualRatePercent) {}

  public record Matrix(
      String currencyCode,
      List<AmountBand> amountBands,
      List<TermBand> termBands,
      List<Cell> cells) {
    public Matrix {
      if (amountBands != null) amountBands = List.copyOf(amountBands);
      if (termBands != null) termBands = List.copyOf(termBands);
      if (cells != null) cells = List.copyOf(cells);
    }
  }

  /** Dates are ISO business dates in the configured tenant zone. */
  public record EffectiveValidity(ValidityMode mode, String effectiveDate) {}

  /** Applicability is the matrix coverage. Campaign precedence is deliberately unapproved. */
  public record CampaignMetadata(
      String campaignId, String name, CampaignType type, String startDate, String endDate) {}

  public record WriteRequest(
      Long expectedRevision,
      Matrix matrix,
      EffectiveValidity effectiveValidity,
      CampaignMetadata campaignMetadata) {}

  public record PublishRequest(Long expectedRevision) {}

  public record Capabilities(
      boolean rateConfigurationSupported,
      boolean rateSchedulingSupported,
      boolean rateHistorySupported,
      boolean rateLookupSupported,
      boolean ratePublicationSupported,
      boolean independentReviewRequired,
      boolean campaignPublicationSupported,
      boolean nativeChartIntegrationSupported,
      boolean financialExecutionSupported) {}

  public record Template(
      long productId,
      InvestmentProductData.InvestmentType investmentType,
      String currencyCode,
      int currencyDigits,
      int maximumAmountBands,
      int maximumTermBands,
      int maximumCells,
      int rateDecimalPlaces,
      String amountBoundarySemantics,
      String termBoundarySemantics,
      String timezone,
      String businessDate,
      List<ValidityMode> validityModes,
      List<CampaignType> campaignTypes,
      String readPermission,
      String managePermission,
      String publishPermission,
      Capabilities capabilities,
      List<String> blockers) {}

  public record Configuration(
      long productId,
      InvestmentProductData.InvestmentType investmentType,
      long revision,
      Long version,
      Status status,
      String currencyCode,
      String effectiveDate,
      String effectiveInstant,
      String timezone,
      WriteRequest configuration,
      long policyRevision,
      InvestmentProductData.Policy policySnapshot,
      InvestmentProductData.NativeProduct nativeSnapshot,
      long makerId,
      String createdAtUtc,
      Long checkerId,
      String approvedAtUtc,
      Long previousVersion,
      Capabilities capabilities,
      List<String> blockers) {}

  public record ValidationResult(
      long productId,
      long revision,
      boolean valid,
      boolean publishable,
      WriteRequest configuration,
      List<String> blockers) {}

  public record Selection(
      long productId,
      InvestmentProductData.InvestmentType investmentType,
      String currencyCode,
      BigDecimal investmentAmount,
      int termDays,
      String asOfInstant,
      BigDecimal annualRatePercent,
      Long version,
      AmountBand amountBand,
      TermBand termBand,
      String effectiveDate,
      String effectiveInstant,
      String timezone,
      CampaignType campaignType,
      SelectionStatus status,
      Capabilities capabilities,
      List<String> blockers) {}

  public record CellChange(
      AmountBand amountBand, TermBand termBand, BigDecimal previousRate, BigDecimal newRate) {}

  public record Audit(
      long productId,
      long revision,
      Long version,
      String action,
      long actorId,
      String recordedAtUtc,
      Long previousVersion,
      String effectiveInstant,
      WriteRequest previousConfiguration,
      WriteRequest newConfiguration,
      List<CellChange> cellChanges) {}

  public static Capabilities capabilities() {
    return new Capabilities(true, true, true, true, true, true, false, false, false);
  }

  public static List<String> financialBlockers() {
    return List.of(
        "NATIVE_RATE_CHART_INTEGRATION_UNVERIFIED",
        "FINANCIAL_EXECUTION_UNAVAILABLE",
        "PRODUCT_POLICY_DRAFT_ONLY");
  }
}
