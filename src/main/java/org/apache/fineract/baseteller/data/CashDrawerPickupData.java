package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.util.List;

public record CashDrawerPickupData(
    Long id,
    String reference,
    String status,
    String businessDate,
    Long officeId,
    String officeName,
    Long sourceCashierId,
    String sourceName,
    Long destinationCashierId,
    String destinationName,
    Long authorizedByUserId,
    String authorizedByUsername,
    String authorizedAt,
    String completedAt,
    List<Currency> currencies) {
  public record Currency(
      String currencyCode,
      BigDecimal cash,
      BigDecimal checks,
      BigDecimal vouchers,
      BigDecimal totalToCollect,
      List<CashExchangeDenominationData> cashDenominations,
      List<Check> selectedChecks) {}
  public record Check(
      String source,
      Long id,
      String date,
      String bank,
      String reference,
      String client,
      String currencyCode,
      BigDecimal amount,
      String status) {}
}
