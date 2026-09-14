package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record BaseTellerAccountSummaryData(
    Long accountId,
    String accountNo,
    Long productId,
    String productName,
    String currencyCode,
    String status,
    BigDecimal balance) {}
