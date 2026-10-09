package org.apache.fineract.baseteller.data;

import java.util.List;

public record CashExchangeInventoryData(
    Long cashierId,
    String currencyCode,
    int decimalPlaces,
    List<CashExchangeInventoryLineData> denominations) {}
