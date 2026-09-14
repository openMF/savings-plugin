package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record BaseTellerDepositCheckData(
    String checkType,
    String bank,
    String checkNumber,
    BigDecimal amount,
    BaseTellerCheckClearingStatus clearingStatus,
    String accountNumber,
    String routingCode,
    Long clearingAuthorizedBy,
    String clearingAuthorizedByUsername,
    OffsetDateTime clearingAuthorizedOnUtc) {}
