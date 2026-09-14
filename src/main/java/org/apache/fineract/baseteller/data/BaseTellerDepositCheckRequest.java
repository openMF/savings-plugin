package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record BaseTellerDepositCheckRequest(
    String checkType,
    String bank,
    String checkNumber,
    BigDecimal amount,
    BaseTellerCheckClearingStatus clearingStatus,
    String accountNumber,
    String routingCode) {}
