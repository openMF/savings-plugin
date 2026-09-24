package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CashAllocationPreviewData(
    CashAllocationType operationType,
    LocalDate businessDate,
    Long officeId,
    String currencyCode,
    String source,
    String destination,
    BigDecimal cashInflow,
    BigDecimal cashOutflow,
    BigDecimal checksInflow,
    BigDecimal checksOutflow,
    BigDecimal vouchersInflow,
    BigDecimal vouchersOutflow,
    BigDecimal authoritativeTotal,
    List<CashAllocationDenominationData> denominations) {}
