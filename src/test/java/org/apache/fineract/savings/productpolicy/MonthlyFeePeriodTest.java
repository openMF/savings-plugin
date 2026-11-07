/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class MonthlyFeePeriodTest {
  @Test
  void explicitMonthUsesTenantBusinessDateAndRejectsCatchupAndFuture() {
    assertEquals(
        YearMonth.of(2024, 2), MonthlyFeePeriod.forCreation("2024-02", LocalDate.of(2024, 3, 20)));
    assertEquals(
        YearMonth.of(2025, 12), MonthlyFeePeriod.forCreation("2025-12", LocalDate.of(2026, 1, 1)));
    for (String period :
        new String[] {"2026-11", "2026-12", "2026-09", "2026-00", "2026-13", "2026-1", "garbage"})
      assertThrows(
          RuntimeException.class,
          () -> MonthlyFeePeriod.forCreation(period, LocalDate.of(2026, 11, 8)));
    assertThrows(RuntimeException.class, () -> MonthlyFeePeriod.forCreation("2026-10", null));
  }

  @Test
  void financialCapabilitiesRemainDisabled() {
    var c = MonthlyFeeData.capabilities();
    assertTrue(c.diagnosticAssessmentSupported());
    assertFalse(c.approvedAverageCalculationSupported());
    assertFalse(c.postingSupported());
    assertFalse(c.collectionSupported());
    assertFalse(c.productionScheduleSupported());
    assertFalse(c.accountingReconciliationSupported());
    assertFalse(c.reversalSupported());
    assertFalse(ProductPolicyData.capabilities().financialEnforcement());
    assertTrue(ProductPolicyData.capabilities().pendingFeatures().contains("MONTHLY_AVERAGE_FEES"));
  }
}
