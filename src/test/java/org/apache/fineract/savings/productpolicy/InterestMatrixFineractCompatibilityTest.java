/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.*;
import org.apache.fineract.portfolio.savings.*;
import org.apache.fineract.portfolio.savings.domain.interest.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Uses the real pinned Fineract JAR financial primitives, not a substitute formula or posting. */
class InterestMatrixFineractCompatibilityTest {
  static final String TENANT = "be03-fixed-regression";

  @BeforeEach
  void tenant() {
    var tenant = mock(FineractPlatformTenant.class);
    when(tenant.getTenantIdentifier()).thenReturn(TENANT);
    ThreadLocalContextUtil.setTenant(tenant);
    MoneyHelper.initializeTenantRoundingMode(TENANT, java.math.RoundingMode.HALF_EVEN.ordinal());
  }

  @AfterEach
  void clear() {
    MoneyHelper.clearCacheForTenant(TENANT);
    ThreadLocalContextUtil.reset();
  }

  @ParameterizedTest
  @CsvSource({
    "360,MONTHLY,0.06,5.00",
    "365,MONTHLY,0.06,4.93",
    "366,MONTHLY,0.06,4.92",
    "360,DAILY,0.06,5.01",
    "365,DAILY,0.06,4.94",
    "365,MONTHLY,0,0.00"
  })
  void fixedRateEntityAndBatchPostingPeriodsRemainEquivalent(
      int days, SavingsCompoundingInterestPeriodType compounding, String rate, String expected) {
    var start = LocalDate.of(2026, 1, 1);
    var end = start.plusDays(29);
    var period = LocalDateInterval.create(start, end);
    var currency = new MonetaryCurrency("MXN", 2, 1);
    var balance = Money.of(currency, new BigDecimal("1000"));
    var zero = Money.zero(currency);
    var entity =
        PostingPeriod.createFrom(
            period,
            balance,
            List.of(),
            currency,
            compounding,
            SavingsInterestCalculationType.DAILY_BALANCE,
            new BigDecimal(rate),
            days,
            end,
            List.of(),
            false,
            zero,
            false,
            false,
            1);
    var batch =
        PostingPeriod.createFromDTO(
            period,
            balance,
            List.of(),
            currency,
            compounding,
            SavingsInterestCalculationType.DAILY_BALANCE,
            new BigDecimal(rate),
            days,
            end,
            List.of(),
            false,
            zero,
            false,
            BigDecimal.ZERO,
            zero,
            false,
            1,
            false);
    for (var nativePeriod : List.of(entity, batch)) {
      nativePeriod.calculateInterest(new CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO));
      assertEquals(new BigDecimal(expected), nativePeriod.getInterestEarned().getAmount());
      assertEquals(end.plusDays(1), nativePeriod.getDateOfPostingTransaction());
    }
    // No SavingsAccountTransaction, journal, or accounting service is written by this test.
  }

  @Test
  void installedVersionIncludesDailyAndMonthlyPostingOptions() {
    assertEquals(
        SavingsPostingInterestPeriodType.DAILY, SavingsPostingInterestPeriodType.fromInt(1));
    assertEquals(
        SavingsPostingInterestPeriodType.MONTHLY, SavingsPostingInterestPeriodType.fromInt(4));
  }
}
