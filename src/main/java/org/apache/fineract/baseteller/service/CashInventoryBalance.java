package org.apache.fineract.baseteller.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class CashInventoryBalance {

  private CashInventoryBalance() {}

  public static BigDecimal calculate(
      final BigDecimal initialBalance,
      final BigDecimal accumulatedInflows,
      final BigDecimal accumulatedOutflows,
      final BigDecimal cutOffs,
      final int decimalPlaces) {
    return value(initialBalance)
        .add(value(accumulatedInflows))
        .subtract(value(accumulatedOutflows))
        .subtract(value(cutOffs))
        .setScale(decimalPlaces, RoundingMode.UNNECESSARY);
  }

  private static BigDecimal value(final BigDecimal amount) {
    return amount == null ? BigDecimal.ZERO : amount;
  }
}
