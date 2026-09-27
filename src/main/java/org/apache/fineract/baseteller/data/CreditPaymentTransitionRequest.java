package org.apache.fineract.baseteller.data;

public record CreditPaymentTransitionRequest(
    String idempotencyKey, String transactionDate, String dateFormat, String locale, String reason) {}
