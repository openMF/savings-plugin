package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record CreditPaymentLoanData(
    Long id,
    String accountNo,
    Long clientId,
    String clientName,
    Long officeId,
    String status,
    boolean payable,
    String currencyCode,
    BigDecimal principalOutstanding,
    BigDecimal interestOutstanding,
    BigDecimal feeOutstanding,
    BigDecimal penaltyOutstanding,
    BigDecimal taxOutstanding,
    BigDecimal totalOutstanding,
    BigDecimal principalOverdue,
    BigDecimal interestOverdue,
    BigDecimal feeOverdue,
    BigDecimal penaltyOverdue,
    BigDecimal taxOverdue,
    BigDecimal totalOverdue,
    Object repaymentSchedule,
    Object transactions) {}
