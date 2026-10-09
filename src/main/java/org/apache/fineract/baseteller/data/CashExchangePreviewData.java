package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.util.List;

public record CashExchangePreviewData(
    Long cashierId,
    String currencyCode,
    BigDecimal receivedAmount,
    BigDecimal deliveredAmount,
    boolean balanced,
    BigDecimal netMonetaryEffect,
    List<CashExchangeDenominationData> receivedDenominations,
    List<CashExchangeDenominationData> deliveredDenominations) {}
