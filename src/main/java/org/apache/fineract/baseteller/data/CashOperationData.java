package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record CashOperationData(
    Long id,
    String reference,
    CashOperationType transactionType,
    LocalDate businessDate,
    String currencyCode,
    BigDecimal amount,
    BigDecimal cashTotal,
    BigDecimal checkTotal,
    Long officeId,
    Long tellerId,
    Long cashierId,
    String cashierName,
    Long actorId,
    String actorUsername,
    String description,
    CashManagementStatus status,
    OffsetDateTime createdOn) {}
