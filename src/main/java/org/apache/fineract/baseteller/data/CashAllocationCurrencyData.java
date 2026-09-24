package org.apache.fineract.baseteller.data;

import java.util.List;

public record CashAllocationCurrencyData(
    String code,
    String name,
    String displaySymbol,
    int decimalPlaces,
    List<CashAllocationDenominationData> denominations) {}
