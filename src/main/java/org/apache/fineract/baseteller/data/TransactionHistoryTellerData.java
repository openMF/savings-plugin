package org.apache.fineract.baseteller.data;

public record TransactionHistoryTellerData(
    Long id, Long tellerId, String code, String name, Long officeId, String officeName) {}
