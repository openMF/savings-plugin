package org.apache.fineract.baseteller.foreigncurrency;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.Cashier;

/** Draft configuration and incomplete previews. None of these DTOs represents an executed trade. */
public final class FxData {
  private FxData() {}

  public enum Operation {
    PURCHASE,
    SALE
  }

  public enum Direction {
    INFLOW,
    OUTFLOW
  }

  public record BranchInput(
      Long officeId, Long expectedVersion, BigDecimal buyPoints, BigDecimal sellPoints) {}

  public record RateRequest(
      String effectiveDate,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal dailyBaseRate,
      List<BranchInput> branches,
      String previewToken) {}

  public record Branch(Long id, String name) {}

  public record Currency(String code, String name, int decimalPlaces) {}

  public record Capabilities(
      boolean publicationEnabled,
      boolean executionEnabled,
      boolean checksSupported,
      boolean vouchersSupported,
      List<String> blockers) {}

  public record RateContext(
      Long operatorId,
      String businessDate,
      List<Branch> branches,
      List<Currency> currencies,
      String nationalCurrency,
      String rateConvention,
      String pointsConvention,
      Capabilities capabilities) {}

  public record RateDraft(
      Long id,
      Long officeId,
      String officeName,
      String effectiveDate,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal dailyBaseRate,
      BigDecimal buyPoints,
      BigDecimal sellPoints,
      long version,
      String status,
      Long createdBy,
      String createdAt) {}

  public record RateChange(Branch branch, RateDraft previous, BranchInput proposed) {}

  public record RatePreview(
      Long operatorId,
      String effectiveDate,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal dailyBaseRate,
      List<RateChange> changes,
      String previewToken,
      String status,
      Capabilities capabilities) {}

  public record RateAudit(
      Long id,
      Long draftId,
      Long previousDraftId,
      Long operatorId,
      String action,
      String recordedAt,
      RateDraft previous,
      RateDraft proposed) {}

  public record Customer(
      Long id, String number, String displayName, Long officeId, boolean active) {}

  public record Quantity(String denominationId, BigDecimal quantity) {}

  public record Denomination(
      String id, String currencyCode, String type, BigDecimal value, long availableQuantity) {}

  public record CashLine(
      String id,
      String currencyCode,
      String type,
      BigDecimal value,
      long quantity,
      BigDecimal amount) {}

  public record CashLeg(
      String currencyCode,
      Direction direction,
      BigDecimal denominationTotal,
      List<CashLine> denominations) {}

  public record TellerContext(
      Long operatorId,
      String businessDate,
      Cashier cashier,
      List<Currency> currencies,
      String nationalCurrency,
      List<RateDraft> rateDrafts,
      List<String> permissions,
      Capabilities capabilities) {}

  public record TradeRequest(
      Long clientId,
      Long identifierId,
      String reference,
      String quoteCurrency,
      BigDecimal usdAmount,
      Long rateDraftId,
      Long rateVersion,
      List<Quantity> usdDenominations,
      List<Quantity> quoteDenominations) {}

  public record TradePreview(
      Operation operation,
      String status,
      boolean executable,
      boolean valuationValidated,
      Customer customer,
      Long identifierId,
      String reference,
      Cashier cashier,
      String businessDate,
      String quotedAt,
      BigDecimal usdAmount,
      String quoteCurrency,
      String nationalCurrency,
      BigDecimal appliedRate,
      BigDecimal calculatedNationalAmount,
      RateDraft candidateRateDraft,
      CashLeg usdCash,
      CashLeg quoteCash,
      Capabilities capabilities) {}
}
