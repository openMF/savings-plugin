package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record CashExchangeDenominationData(
    String denominationId, String type, BigDecimal value, Long quantity, BigDecimal amount) {}
