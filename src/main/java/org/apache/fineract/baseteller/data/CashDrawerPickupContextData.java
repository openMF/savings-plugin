package org.apache.fineract.baseteller.data;

import java.util.List;

public record CashDrawerPickupContextData(
    String businessDate,
    List<EndOfDayClosingData.Cashier> sourceCashiers,
    List<EndOfDayClosingData.Cashier> destinations,
    List<EndOfDayClosingData.Currency> currencies,
    List<SourceInventory> sourceInventory,
    List<String> permissions) {
  public record SourceInventory(
      Long cashierId,
      String currencyCode,
      List<CashExchangeInventoryLineData> denominations) {}
}
