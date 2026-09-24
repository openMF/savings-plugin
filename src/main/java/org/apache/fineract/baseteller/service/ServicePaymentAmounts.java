package org.apache.fineract.baseteller.service;

import java.math.BigDecimal;

public record ServicePaymentAmounts(
    BigDecimal baseAmount,
    BigDecimal commission,
    BigDecimal commissionVat,
    BigDecimal totalToPay) {}
