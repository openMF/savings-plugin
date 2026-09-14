package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.util.List;

public record BaseTellerDepositFundingData(
    BaseTellerFundingType type,
    BigDecimal amount,
    String currencyCode,
    Long paymentTypeId,
    List<BaseTellerDenominationData> denominations,
    List<BaseTellerDepositCheckRequest> checks) {}
