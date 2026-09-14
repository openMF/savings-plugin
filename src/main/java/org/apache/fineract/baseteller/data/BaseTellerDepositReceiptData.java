package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record BaseTellerDepositReceiptData(
    String receiptNumber,
    BaseTellerDepositStatus status,
    String failureMessage,
    Long clientId,
    String customerName,
    Long savingsAccountId,
    String savingsAccountNo,
    Long savingsProductId,
    String savingsProductName,
    Long savingsTransactionId,
    BaseTellerFundingType fundingType,
    BigDecimal amount,
    String currencyCode,
    BigDecimal resultingBalance,
    Long tellerId,
    Long cashierId,
    Long operatorId,
    String operatorName,
    Long officeId,
    String officeName,
    OffsetDateTime createdOnUtc,
    OffsetDateTime completedOnUtc,
    List<BaseTellerDenominationData> denominations,
    List<BaseTellerDepositCheckData> checks) {}
