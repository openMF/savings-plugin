package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransactionHistoryItemData(
    String historyId,
    OffsetDateTime transactionDate,
    String operation,
    BigDecimal inflow,
    BigDecimal outflow,
    String currencyCode,
    Integer decimalPlaces,
    String concept,
    String status,
    String reference,
    Long tellerId,
    Long clientId) {}
