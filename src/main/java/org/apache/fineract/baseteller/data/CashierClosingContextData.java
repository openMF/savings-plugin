package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CashierClosingContextData(
    LocalDate businessDate,
    Long officeId,
    String officeName,
    Long tellerId,
    String tellerName,
    Long cashierId,
    String cashierName,
    String currencyCode,
    BigDecimal openingBalance,
    BigDecimal cashInflows,
    BigDecimal cashOutflows,
    BigDecimal previousSettlements,
    BigDecimal expectedAmount,
    List<CashierCheckData> eligibleChecks,
    CashManagementStatus status) {}
