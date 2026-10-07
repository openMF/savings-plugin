package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransactionHistoryDetailData(
    String historyId,
    OffsetDateTime transactionDate,
    TransactionHistoryClientData client,
    String operation,
    String concept,
    String reference,
    TransactionHistoryTellerData teller,
    String status,
    String currencyCode,
    Integer decimalPlaces,
    BigDecimal cashReceived,
    BigDecimal checksReceived,
    BigDecimal change,
    BigDecimal adjustment,
    BigDecimal total,
    TransactionHistoryCancellationData cancellation,
    boolean receiptSupported) {}
