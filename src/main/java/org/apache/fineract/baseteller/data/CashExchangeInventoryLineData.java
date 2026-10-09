package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record CashExchangeInventoryLineData(
    String denominationId, String type, BigDecimal value, Long availableQuantity) {}
