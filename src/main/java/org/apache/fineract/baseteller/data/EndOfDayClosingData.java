package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.util.List;

public record EndOfDayClosingData(
    Long id,
    String reference,
    String status,
    String businessDate,
    Long officeId,
    String officeName,
    Long tellerId,
    String tellerName,
    Long cashierId,
    Long cashierStaffId,
    String cashierName,
    Long createdBy,
    Long authorizedBy,
    String authorizedByUsername,
    String authorizedAt,
    String completedAt,
    List<Balance> balancesByCurrency) {
  public record Check(
      String source,
      Long id,
      String date,
      String currencyCode,
      String type,
      String bank,
      String reference,
      String username,
      Long cashierId,
      BigDecimal amount) {}

  public record Balance(
      String currencyCode,
      int decimalPlaces,
      BigDecimal expectedCash,
      BigDecimal expectedChecks,
      BigDecimal expectedVouchers,
      BigDecimal expectedTotal,
      BigDecimal coinTotal,
      BigDecimal billTotal,
      BigDecimal declaredCash,
      BigDecimal selectedChecks,
      BigDecimal vouchers,
      BigDecimal declaredTotal,
      BigDecimal shortage,
      BigDecimal overage,
      String reconciliationStatus,
      List<CashExchangeDenominationData> denominations,
      List<Check> checks,
      Long reconciliationId,
      BigDecimal settledCash) {}

  public record Currency(
      String currencyCode, int decimalPlaces, List<CashExchangeDenominationData> denominations) {}

  public record Cashier(
      Long cashierId,
      Long staffId,
      String cashierName,
      Long tellerId,
      String tellerName,
      Long officeId,
      String officeName,
      boolean closed) {
    public Cashier(
        Long cashierId,
        Long staffId,
        String cashierName,
        Long tellerId,
        String tellerName,
        Long officeId,
        String officeName) {
      this(cashierId, staffId, cashierName, tellerId, tellerName, officeId, officeName, false);
    }
  }

  public record Context(
      String businessDate,
      List<Cashier> cashiers,
      List<Currency> currencies,
      List<Long> completedClosingIds) {}
}
