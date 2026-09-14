package org.apache.fineract.baseteller.data;

import com.google.gson.JsonObject;

public record BaseTellerSavingsOpeningRequest(
    String idempotencyKey,
    Long clientId,
    Long productId,
    JsonObject savingsAccount,
    Boolean approve,
    Boolean activate,
    String locale,
    String dateFormat,
    String transactionDate,
    BaseTellerFundingData initialFunding) {}
