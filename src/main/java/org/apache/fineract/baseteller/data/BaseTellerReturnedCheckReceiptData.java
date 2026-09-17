package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record BaseTellerReturnedCheckReceiptData(
    String receiptNumber,
    BaseTellerReturnedCheckStatus status,
    String failureMessage,
    Long returnedCheckId,
    Long depositCheckDetailId,
    String checkNumber,
    Long clientId,
    String customerName,
    BigDecimal checkAmount,
    BigDecimal cashReceived,
    BigDecimal changeAmount,
    String currencyCode,
    Long tellerId,
    Long cashierId,
    Long cashierTransactionId,
    Long operatorId,
    String operatorName,
    Long officeId,
    String officeName,
    OffsetDateTime createdOnUtc,
    OffsetDateTime completedOnUtc,
    List<BaseTellerDenominationData> denominations) {}
