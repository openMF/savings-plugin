package org.apache.fineract.baseteller.data;

import java.util.List;

public record CashExchangeContextData(
    List<CashExchangeTellerData> tellers, List<CashExchangeCurrencyData> currencies) {}
