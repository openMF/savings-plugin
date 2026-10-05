package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CashInventoryData(
    String custodianKey,
    CashInventoryCustodianType custodianType,
    Long resourceId,
    Long userId,
    String userCode,
    String name,
    CashInventoryType inventoryType,
    String currencyCode,
    int decimalPlaces,
    BigDecimal initialBalance,
    BigDecimal accumulatedInflows,
    BigDecimal accumulatedOutflows,
    BigDecimal cutOffs,
    BigDecimal balance,
    OffsetDateTime lastCutOffAt,
    BigDecimal lastCutOffAmount,
    OffsetDateTime asOf) {}
