package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record CreditPaymentReceiptData(
    Long id,
    String receiptNumber,
    CreditPaymentStatus status,
    CreditPaymentMethod paymentMethod,
    Long clientId,
    String clientName,
    Long loanId,
    String loanAccountNo,
    BigDecimal amount,
    BigDecimal tenderAmount,
    BigDecimal changeAmount,
    String currencyCode,
    Long paymentTypeId,
    Long loanTransactionId,
    Long cashierTransactionId,
    BigDecimal principalPortion,
    BigDecimal interestPortion,
    BigDecimal feePortion,
    BigDecimal penaltyPortion,
    BigDecimal overpaymentPortion,
    LocalDate businessDate,
    Long operatorId,
    String operatorName,
    Long officeId,
    Long tellerId,
    Long cashierId,
    String note,
    String failureMessage,
    OffsetDateTime createdOnUtc,
    OffsetDateTime completedOnUtc,
    List<BaseTellerDenominationData> denominations,
    CreditPaymentCheckData check) {}
