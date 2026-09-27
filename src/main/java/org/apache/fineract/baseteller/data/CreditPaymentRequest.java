package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.util.List;

public record CreditPaymentRequest(
    String idempotencyKey,
    Long clientId,
    Long loanId,
    CreditPaymentMethod paymentMethod,
    BigDecimal amount,
    String currencyCode,
    Long paymentTypeId,
    String transactionDate,
    String dateFormat,
    String locale,
    String note,
    List<BaseTellerDenominationData> denominations,
    CreditPaymentCheckRequest check) {}
