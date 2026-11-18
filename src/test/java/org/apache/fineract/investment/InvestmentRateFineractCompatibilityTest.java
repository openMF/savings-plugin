/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChartFields;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChartSlabFields;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.junit.jupiter.api.Test;

/** Executes the pinned binary's boundary semantics; this is not proof of financial integration. */
class InvestmentRateFineractCompatibilityTest {
  @Test
  void nativeSlabsCombineInclusiveAmountAndTermConditionsAndSixDecimalRates() throws Exception {
    var rate = new BigDecimal("8.123456");
    var slab =
        InterestRateChartSlabFields.createNew(
            "Synthetic",
            SavingsPeriodFrequencyType.DAYS,
            1,
            90,
            new BigDecimal("100"),
            new BigDecimal("1000"),
            rate,
            "MXN");
    assertTrue(slab.isAmountBetween(new BigDecimal("1000.00")));
    assertTrue(slab.isPeriodBetween(90));
    assertFalse(slab.isPeriodBetween(91));
    assertEquals(rate, slab.annualInterestRate());
    var field = InterestRateChartSlabFields.class.getDeclaredField("annualInterestRate");
    var column = field.getAnnotation(jakarta.persistence.Column.class);
    assertEquals(6, column.scale());
    assertEquals(19, column.precision());
  }

  @Test
  void nativeChartDatesAreInclusiveAndOpenEndedChartsAreCappedAtBusinessDate() {
    ThreadLocalContextUtil.setBusinessDates(
        new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 11, 17))));
    try {
      var chart =
          InterestRateChartFields.createNew(
              "Synthetic", null, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 17), true);
      assertTrue(chart.isApplicableChartFor(LocalDate.of(2026, 11, 1)));
      assertTrue(chart.isApplicableChartFor(LocalDate.of(2026, 11, 17)));
      assertFalse(chart.isApplicableChartFor(LocalDate.of(2026, 11, 18)));
      var open =
          InterestRateChartFields.createNew("Open", null, LocalDate.of(2026, 11, 1), null, true);
      assertFalse(open.isApplicableChartFor(LocalDate.of(2026, 11, 18)));
    } finally {
      ThreadLocalContextUtil.reset();
    }
  }
}
