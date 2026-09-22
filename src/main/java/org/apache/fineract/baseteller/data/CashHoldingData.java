package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CashHoldingData(
    LocalDate businessDate,
    Long officeId,
    Long tellerId,
    Long cashierId,
    String cashierName,
    String currencyCode,
    BigDecimal openingFunds,
    BigDecimal inflows,
    BigDecimal outflows,
    BigDecimal settlementsAndDeposits,
    BigDecimal currentBalance,
    CashManagementStatus status) {}
