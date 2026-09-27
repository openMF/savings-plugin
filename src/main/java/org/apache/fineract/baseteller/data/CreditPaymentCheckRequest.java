package org.apache.fineract.baseteller.data;

public record CreditPaymentCheckRequest(
    Long bankId,
    String checkType,
    String checkNumber,
    String accountNumber,
    String routingCode,
    CreditPaymentCheckClassification classification) {}
