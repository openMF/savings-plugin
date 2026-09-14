package org.apache.fineract.baseteller.data;

public record BaseTellerDepositRequest(
    String idempotencyKey,
    Long clientId,
    Long savingsAccountId,
    String locale,
    String dateFormat,
    String transactionDate,
    BaseTellerDepositFundingData funding) {}
