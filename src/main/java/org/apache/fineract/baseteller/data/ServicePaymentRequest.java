package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.util.List;

public record ServicePaymentRequest(
    String idempotencyKey,
    ServicePaymentPayerType payerType,
    Long clientId,
    String payerName,
    Long serviceId,
    String serviceReference,
    BigDecimal baseAmount,
    String currencyCode,
    String businessDate,
    Long paymentTypeId,
    List<BaseTellerDenominationData> denominations) {}
