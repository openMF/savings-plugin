package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record TransactionHistoryTotalsData(
    String currencyCode,
    Integer decimalPlaces,
    BigDecimal totalInflows,
    BigDecimal totalOutflows,
    BigDecimal total) {}
