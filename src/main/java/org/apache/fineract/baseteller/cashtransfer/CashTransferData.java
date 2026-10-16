package org.apache.fineract.baseteller.cashtransfer;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.Cashier;

/** Immutable historical snapshots; currencies are never combined into one amount. */
public record CashTransferData(
    Long id,
    String reference,
    String operation,
    String status,
    String businessDate,
    Cashier source,
    Cashier destination,
    Long createdBy,
    String createdByUsername,
    String createdAt,
    String completedAt,
    boolean balanced,
    List<CurrencyTotal> inflowsByCurrency,
    List<CurrencyTotal> outflowsByCurrency,
    List<Currency> currencies) {
  public record CurrencyTotal(String currencyCode, BigDecimal amount) {}

  public record Denomination(
      String denominationId,
      String denominationType,
      BigDecimal denominationValue,
      long quantity,
      BigDecimal amount) {}

  public record Currency(
      String currencyCode,
      int decimalPlaces,
      BigDecimal total,
      List<Denomination> denominations,
      Long sourceCashierTransactionId,
      Long destinationCashierTransactionId) {}

  public record AvailableDenomination(
      String denominationId,
      String currencyCode,
      BigDecimal denominationValue,
      String denominationType,
      long availableQuantity) {}

  public record AvailableCurrency(
      String currencyCode, int decimalPlaces, List<AvailableDenomination> denominations) {}

  public record Context(
      String businessDate,
      Cashier source,
      List<Cashier> destinations,
      List<AvailableCurrency> currencies,
      List<String> permissions) {}
}
