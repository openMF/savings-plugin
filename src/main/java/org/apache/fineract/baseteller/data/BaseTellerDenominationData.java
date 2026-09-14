package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record BaseTellerDenominationData(
    String denominationId, BigDecimal value, Long quantity) {}
