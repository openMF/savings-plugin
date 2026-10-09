package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record CashExchangeData(
    Long id,
    String receiptNumber,
    String status,
    LocalDate businessDate,
    Long officeId,
    Long tellerId,
    Long cashierId,
    String tellerName,
    String currencyCode,
    BigDecimal receivedAmount,
    BigDecimal deliveredAmount,
    Long processedBy,
    String processedByUsername,
    OffsetDateTime processedAt,
    List<CashExchangeDenominationData> receivedDenominations,
    List<CashExchangeDenominationData> deliveredDenominations) {}
