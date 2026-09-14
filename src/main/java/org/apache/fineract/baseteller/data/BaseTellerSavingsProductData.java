package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record BaseTellerSavingsProductData(
    Long productId,
    String name,
    String currencyCode,
    BigDecimal nominalAnnualInterestRate,
    BigDecimal minRequiredOpeningBalance,
    Boolean allowOverdraft) {}
