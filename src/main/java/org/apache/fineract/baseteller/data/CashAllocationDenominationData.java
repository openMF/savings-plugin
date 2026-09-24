package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record CashAllocationDenominationData(
    String identifier,
    String type,
    BigDecimal value,
    Long quantity,
    BigDecimal subtotal) {}
