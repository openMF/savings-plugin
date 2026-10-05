package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CashInventoryBalanceTest {

  @Test
  void openingAndInflowProduceExpectedBalance() {
    assertBalance("50000.00", "20000.00", "0.00", "0.00", "70000.00", 2);
  }

  @Test
  void outflowReducesAvailableInventory() {
    assertBalance("50000.00", "20000.00", "15000.00", "0.00", "55000.00", 2);
  }

  @Test
  void cutoffReducesAvailableInventory() {
    assertBalance("50000.00", "20000.00", "15000.00", "10000.00", "45000.00", 2);
  }

  @Test
  void usesExactBigDecimalArithmetic() {
    assertBalance("0.10", "0.20", "0.00", "0.00", "0.30", 2);
  }

  @Test
  void supportsCurrencySpecificScale() {
    assertBalance("1.125", "2.375", "0.500", "0.250", "2.750", 3);
  }

  @Test
  void rejectsAmountsThatCannotRespectCurrencyPrecision() {
    assertThrows(
        ArithmeticException.class,
        () ->
            CashInventoryBalance.calculate(
                new BigDecimal("1.001"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                2));
  }

  private static void assertBalance(
      final String initial,
      final String inflows,
      final String outflows,
      final String cutoffs,
      final String expected,
      final int scale) {
    assertEquals(
        new BigDecimal(expected),
        CashInventoryBalance.calculate(
            new BigDecimal(initial),
            new BigDecimal(inflows),
            new BigDecimal(outflows),
            new BigDecimal(cutoffs),
            scale));
  }
}
