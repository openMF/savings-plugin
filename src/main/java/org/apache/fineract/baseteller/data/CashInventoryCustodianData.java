package org.apache.fineract.baseteller.data;

public record CashInventoryCustodianData(
    String key,
    CashInventoryCustodianType type,
    Long resourceId,
    String code,
    String name) {}
