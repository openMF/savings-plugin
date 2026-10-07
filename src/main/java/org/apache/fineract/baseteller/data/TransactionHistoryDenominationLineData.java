package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record TransactionHistoryDenominationLineData(
    String currencyCode,
    String denominationId,
    BigDecimal denominationValue,
    Long quantity,
    BigDecimal amount,
    String denominationType) {}
