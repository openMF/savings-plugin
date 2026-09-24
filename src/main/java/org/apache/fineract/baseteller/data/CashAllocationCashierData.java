package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record CashAllocationCashierData(
    Long cashierId,
    Long tellerId,
    String tellerName,
    Long staffId,
    String cashierName,
    boolean headCashierSource,
    BigDecimal currentBalance) {}
