package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.util.List;

public record BaseTellerReturnedCheckPaymentRequest(
    String idempotencyKey,
    String locale,
    String dateFormat,
    String transactionDate,
    BigDecimal cashReceived,
    String currencyCode,
    Long paymentTypeId,
    String note,
    List<BaseTellerDenominationData> denominations) {}
