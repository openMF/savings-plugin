package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.util.List;

public record BaseTellerFundingData(
    BaseTellerFundingType type,
    BigDecimal amount,
    String currencyCode,
    Long paymentTypeId,
    List<BaseTellerDenominationData> denominations,
    BaseTellerCheckData check) {}
