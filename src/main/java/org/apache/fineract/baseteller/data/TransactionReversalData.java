package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class TransactionReversalData {
  private TransactionReversalData() {}

  public record Cashier(Long id, Long tellerId, Long officeId, Long staffId, String name) {}

  public record Denomination(String id, BigDecimal value, String type, String currencyCode) {}

  public record Context(
      LocalDate businessDate,
      List<Cashier> cashiers,
      List<TransactionHistoryCurrencyData> currencies,
      List<CreditPaymentBankData> banks,
      List<Denomination> denominations,
      boolean canCreate,
      boolean canAuthorize) {}

  public record Check(
      Long id,
      Long bankId,
      String bank,
      String checkNumber,
      BigDecimal amount,
      String currencyCode,
      boolean eligible) {}

  public record Eligibility(boolean reversible, String reason) {}

  public record Candidate(
      TransactionHistoryItemData original,
      String user,
      String sourceType,
      String operationType,
      BigDecimal amount,
      LocalDate businessDate,
      Eligibility eligibility) {}

  public record Search(List<Candidate> items, long totalFilteredRecords, int offset, int limit) {}

  public record Detail(
      String historyId,
      String sourceType,
      Long sourceId,
      LocalDate businessDate,
      Cashier cashier,
      String user,
      String operation,
      String currencyCode,
      BigDecimal amount,
      BigDecimal cashAmount,
      BigDecimal checkAmount,
      String originalDirection,
      Eligibility eligibility,
      List<Check> checks,
      List<Denomination> denominations,
      List<BaseTellerDenominationData> returnedCashDenominations,
      Long reversalId) {}

  public record Preview(
      Detail original,
      List<BaseTellerDenominationData> cashDenominations,
      List<BaseTellerDenominationData> returnedCashDenominations,
      String cashDirection,
      BigDecimal cashEffect,
      BigDecimal checkEffect,
      String accountingStrategy) {}

  public record Result(
      Long id,
      String historyId,
      String originalHistoryId,
      Long cashierId,
      String currencyCode,
      BigDecimal amount,
      BigDecimal cashEffect,
      BigDecimal checkEffect,
      Long authorizationId,
      Long authorizedBy,
      OffsetDateTime authorizedAt,
      Long reversedBy,
      OffsetDateTime reversedAt,
      String sourceReversalReference,
      String status,
      List<BaseTellerDenominationData> removedCashDenominations,
      List<BaseTellerDenominationData> returnedCashDenominations,
      List<ReversedCheck> checks) {}

  public record ReversedCheck(
      Long sourceCheckId,
      Long bankId,
      String checkNumber,
      BigDecimal amount,
      String currencyCode) {}
}
