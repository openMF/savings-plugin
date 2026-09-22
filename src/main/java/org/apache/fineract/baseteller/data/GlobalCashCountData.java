package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record GlobalCashCountData(
    LocalDate businessDate,
    String currencyCode,
    BigDecimal expectedAmount,
    BigDecimal cashTotal,
    BigDecimal checkTotal,
    BigDecimal actualAmount,
    BigDecimal difference,
    List<CashierClosingReceiptData> cashierClosings) {}
