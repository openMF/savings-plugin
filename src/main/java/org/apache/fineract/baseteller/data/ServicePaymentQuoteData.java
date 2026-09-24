package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ServicePaymentQuoteData(
    ServicePaymentPayerType payerType,
    Long clientId,
    String clientAccountNo,
    String payerName,
    Long serviceId,
    String serviceCode,
    String serviceName,
    String serviceReference,
    BigDecimal baseAmount,
    BigDecimal commission,
    BigDecimal commissionVat,
    BigDecimal totalToPay,
    String currencyCode,
    LocalDate businessDate) {}
