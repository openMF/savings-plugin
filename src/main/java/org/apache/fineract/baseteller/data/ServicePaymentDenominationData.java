package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record ServicePaymentDenominationData(String identifier, BigDecimal value, String type) {}
