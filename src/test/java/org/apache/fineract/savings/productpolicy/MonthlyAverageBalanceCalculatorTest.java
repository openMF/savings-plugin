/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** All calculations here are illustrative; no institutional financial formula is approved. */
class MonthlyAverageBalanceCalculatorTest {
  final MonthlyAverageBalanceCalculator calculator = new MonthlyAverageBalanceCalculator();

  List<MonthlyAverageBalanceCalculator.Observation> days(YearMonth month, String amount) {
    var rows = new ArrayList<MonthlyAverageBalanceCalculator.Observation>();
    for (int i = 1; i <= month.lengthOfMonth(); i++)
      rows.add(
          new MonthlyAverageBalanceCalculator.Observation(month.atDay(i), new BigDecimal(amount)));
    return rows;
  }

  @ParameterizedTest
  @ValueSource(strings = {"2025-02", "2024-02", "2026-04", "2026-01"})
  void variableMonthLengths(String period) {
    var month = YearMonth.parse(period);
    var average = calculator.illustrate(month, days(month, "12.34"));
    assertEquals(month.lengthOfMonth(), average.eligibleDays());
    assertEquals(new BigDecimal("12.340000"), average.display(6, RoundingMode.UNNECESSARY));
    assertEquals(0, average.compareTo(new BigDecimal("12.34")));
    assertTrue(average.compareTo(new BigDecimal("12.35")) < 0);
    assertTrue(average.compareTo(new BigDecimal("12.33")) > 0);
  }

  @Test
  void changingBalanceIncludingZeroAndNegativeDays() {
    var month = YearMonth.of(2026, 4);
    var rows = days(month, "0");
    for (int i = 0; i < 10; i++)
      rows.set(
          i,
          new MonthlyAverageBalanceCalculator.Observation(
              month.atDay(i + 1), new BigDecimal("100.00")));
    for (int i = 20; i < 30; i++)
      rows.set(
          i,
          new MonthlyAverageBalanceCalculator.Observation(
              month.atDay(i + 1), new BigDecimal("-10.00")));
    assertEquals(
        new BigDecimal("30.00"),
        calculator.illustrate(month, rows).display(2, RoundingMode.UNNECESSARY));
  }

  @Test
  void recurringDecimalNeverRoundsBeforeThresholdComparison() {
    var month = YearMonth.of(2026, 4);
    var rows = days(month, "0");
    rows.set(
        0, new MonthlyAverageBalanceCalculator.Observation(month.atDay(1), new BigDecimal("1.00")));
    var average = calculator.illustrate(month, rows);
    assertEquals(new BigDecimal("0.03"), average.display(2, RoundingMode.DOWN));
    assertTrue(average.compareTo(new BigDecimal("0.033333")) > 0);
    assertTrue(average.compareTo(new BigDecimal("0.033334")) < 0);
  }

  @Test
  void incompleteDuplicateUnorderedAndMalformedHistoryFailsClosed() {
    var month = YearMonth.of(2026, 1);
    var rows = days(month, "1");
    assertThrows(
        IllegalArgumentException.class, () -> calculator.illustrate(month, rows.subList(0, 30)));
    rows.set(1, rows.getFirst());
    assertThrows(IllegalArgumentException.class, () -> calculator.illustrate(month, rows));
    rows.set(1, new MonthlyAverageBalanceCalculator.Observation(month.atDay(2), null));
    assertThrows(IllegalArgumentException.class, () -> calculator.illustrate(month, rows));
    assertThrows(
        IllegalArgumentException.class,
        () -> calculator.illustrate(month, days(month, "0.0000001")));
  }

  @Test
  void largeExactValuesAndInvalidThreshold() {
    var month = YearMonth.of(2024, 2);
    var average = calculator.illustrate(month, days(month, "9999999999999.123456"));
    assertEquals(
        new BigDecimal("9999999999999.123456"), average.display(6, RoundingMode.UNNECESSARY));
    assertThrows(IllegalArgumentException.class, () -> average.compareTo(new BigDecimal("-1")));
  }
}
