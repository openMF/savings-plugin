package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record ServicePaymentQuoteRequest(
    ServicePaymentPayerType payerType,
    Long clientId,
    String payerName,
    Long serviceId,
    String serviceReference,
    BigDecimal baseAmount,
    String currencyCode) {}
