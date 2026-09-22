package org.apache.fineract.baseteller.data;

import java.time.LocalDate;
import java.util.List;

public record CashOperationRequest(
    String idempotencyKey,
    CashOperationType transactionType,
    Long cashierId,
    LocalDate businessDate,
    String currencyCode,
    List<BaseTellerDenominationData> denominations,
    List<Long> checkIds,
    String description) {}
