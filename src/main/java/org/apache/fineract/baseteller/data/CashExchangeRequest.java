package org.apache.fineract.baseteller.data;

import java.util.List;

public record CashExchangeRequest(
    Long cashierId,
    String currencyCode,
    List<CashExchangeQuantityData> receivedDenominations,
    List<CashExchangeQuantityData> deliveredDenominations,
    String idempotencyKey) {}
