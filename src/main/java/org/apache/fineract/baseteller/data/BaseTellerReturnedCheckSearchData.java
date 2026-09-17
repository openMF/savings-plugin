package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BaseTellerReturnedCheckSearchData(
    Long id,
    Long depositCheckDetailId,
    String checkNumber,
    Long clientId,
    String customerName,
    Long savingsAccountId,
    String savingsAccountNo,
    BigDecimal amount,
    String currencyCode,
    LocalDate returnedOnDate,
    BaseTellerReturnedCheckStatus status,
    Long tellerId,
    Long cashierId,
    Long officeId,
    String officeName) {}
