package org.apache.fineract.baseteller.service;

import java.math.BigDecimal;

public record ServicePaymentCashAmounts(BigDecimal amountReceived, BigDecimal change) {}
