package org.apache.fineract.baseteller.data;

public record BaseTellerCheckData(
    String checkType,
    String bank,
    String checkNumber,
    String accountNumber,
    String routingCode) {}
