package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record BaseTellerReturnedCheckDetailData(
    Long id,
    Long depositId,
    Long depositCheckDetailId,
    String originalReceiptNumber,
    String checkType,
    String bank,
    String checkNumber,
    Long clientId,
    String customerName,
    Long savingsAccountId,
    String savingsAccountNo,
    BigDecimal amount,
    String currencyCode,
    LocalDate returnedOnDate,
    String returnReason,
    BaseTellerReturnedCheckStatus status,
    Long tellerId,
    Long cashierId,
    Long officeId,
    String officeName,
    Long settlementId,
    String settlementReceiptNumber,
    OffsetDateTime settledOnUtc) {}
