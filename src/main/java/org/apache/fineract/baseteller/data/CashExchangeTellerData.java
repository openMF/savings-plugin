package org.apache.fineract.baseteller.data;

public record CashExchangeTellerData(
    Long id, Long tellerId, String code, String name, Long officeId) {}
