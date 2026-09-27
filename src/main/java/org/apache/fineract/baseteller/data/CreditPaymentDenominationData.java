package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record CreditPaymentDenominationData(
    String identifier, String currencyCode, BigDecimal value, String type) {}
