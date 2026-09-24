package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record CashAllocationReceiptData(
    Long id,
    String reference,
    CashAllocationType operationType,
    CashManagementStatus status,
    LocalDate businessDate,
    Long officeId,
    String officeName,
    Long initiatedBy,
    String initiatedByUsername,
    Long sourceCashierId,
    String source,
    Long destinationCashierId,
    String destination,
    String currencyCode,
    BigDecimal totalAmount,
    BigDecimal sourceBalanceBefore,
    BigDecimal sourceBalanceAfter,
    BigDecimal destinationBalanceBefore,
    BigDecimal destinationBalanceAfter,
    Long sourceCashierTransactionId,
    Long destinationCashierTransactionId,
    String accountingTransactionId,
    String note,
    OffsetDateTime createdOn,
    OffsetDateTime completedOn,
    List<CashAllocationDenominationData> denominations) {}
