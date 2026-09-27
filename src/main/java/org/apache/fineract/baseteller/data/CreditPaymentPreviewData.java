package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CreditPaymentPreviewData(
    Long loanId,
    CreditPaymentMethod paymentMethod,
    CreditPaymentCheckClassification checkClassification,
    String currencyCode,
    LocalDate businessDate,
    BigDecimal paymentAmount,
    BigDecimal tenderAmount,
    BigDecimal changeAmount,
    BigDecimal principalOutstanding,
    BigDecimal interestOutstanding,
    BigDecimal feeOutstanding,
    BigDecimal penaltyOutstanding,
    BigDecimal taxOutstanding,
    Object nativeRepaymentTemplate,
    List<BaseTellerDenominationData> denominations,
    boolean postsRepaymentOnConfirmation) {}
