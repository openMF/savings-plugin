package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CashAllocationRequest(
    String idempotencyKey,
    CashAllocationType operationType,
    Long officeId,
    Long sourceCashierId,
    Long destinationCashierId,
    LocalDate businessDate,
    String currencyCode,
    BigDecimal amount,
    List<BaseTellerDenominationData> denominations,
    String note) {}
