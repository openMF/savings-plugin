package org.apache.fineract.baseteller.data;

public record BaseTellerCustomerData(
    Long clientId,
    String accountNo,
    String externalId,
    String displayName,
    Long officeId,
    String officeName,
    String status) {}
