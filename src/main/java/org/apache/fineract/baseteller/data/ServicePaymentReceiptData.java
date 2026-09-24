package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record ServicePaymentReceiptData(
    Long transactionId,
    String receiptNumber,
    String status,
    LocalDate businessDate,
    Long officeId,
    String officeName,
    Long tellerId,
    String tellerName,
    Long cashierId,
    String cashierName,
    Long operatorId,
    String operatorName,
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
    BigDecimal totalPaid,
    BigDecimal amountReceived,
    BigDecimal change,
    String currencyCode,
    Long cashierTransactionId,
    String accountingTransactionId,
    OffsetDateTime createdOnUtc,
    OffsetDateTime completedOnUtc,
    List<BaseTellerDenominationData> denominations) {}
