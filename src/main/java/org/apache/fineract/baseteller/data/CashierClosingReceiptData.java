package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record CashierClosingReceiptData(
    Long id,
    String reference,
    LocalDate businessDate,
    Long officeId,
    String officeName,
    Long tellerId,
    String tellerName,
    Long cashierId,
    String cashierName,
    String currencyCode,
    BigDecimal cashTotal,
    BigDecimal checkTotal,
    BigDecimal expectedAmount,
    BigDecimal actualAmount,
    BigDecimal difference,
    CashDifferenceType differenceType,
    Long authorizedBy,
    String authorizedByUsername,
    OffsetDateTime authorizedOn,
    CashManagementStatus status,
    List<BaseTellerDenominationData> denominations,
    List<CashierCheckData> checks) {}
