/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentSimulationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.FixedDepositAccount;
import org.apache.fineract.portfolio.savings.domain.interest.EndOfDayBalance;
import org.apache.fineract.portfolio.savings.service.FixedDepositAccountInterestCalculationServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Executes the exact pinned binaries, without creating an application or invoking posting. */
class InvestmentSimulationFineractCompatibilityTest {
  @BeforeEach
  void context() {
    var tenant = mock(FineractPlatformTenant.class);
    when(tenant.getTenantIdentifier()).thenReturn("be03-native-compatibility");
    ThreadLocalContextUtil.setTenant(tenant);
    MoneyHelper.initializeTenantRoundingMode(
        "be03-native-compatibility", RoundingMode.HALF_EVEN.ordinal());
  }

  @AfterEach
  void clear() {
    MoneyHelper.clearCacheForTenant("be03-native-compatibility");
    ThreadLocalContextUtil.reset();
  }

  @Test
  void nativeFixedDepositDayMaturityUsesStartPlusTermAcrossLeapAndYearBoundaries()
      throws Exception {
    for (var start : List.of(LocalDate.of(2028, 2, 28), LocalDate.of(2026, 12, 31))) {
      for (int days : List.of(1, 30, 90, 365, 1825)) {
        var terms = mock(DepositAccountTermAndPreClosure.class);
        when(terms.depositPeriod()).thenReturn(days);
        when(terms.depositPeriodFrequencyType()).thenReturn(SavingsPeriodFrequencyType.DAYS);
        var account =
            mock(
                FixedDepositAccount.class,
                invocation ->
                    invocation.getMethod().getName().equals("accountSubmittedOrActivationDate")
                        ? start
                        : invocation.callRealMethod());
        var field = FixedDepositAccount.class.getDeclaredField("accountTermAndPreClosure");
        field.setAccessible(true);
        field.set(account, terms);
        assertEquals(start.plusDays(days), account.calculateMaturityDate());
      }
    }
  }

  @Test
  void singlePeriodGrossProjectionMatchesNativeEndOfDayBalanceFixture() {
    var start = LocalDate.of(2026, 1, 1);
    var currency = new MonetaryCurrency("MXN", 2, 1);
    var principal = new BigDecimal("10000");
    var balance = Money.of(currency, principal);
    var nativeBalance = EndOfDayBalance.from(start, balance, balance, 90);
    var unrounded =
        nativeBalance.calculateInterestOnBalance(
            BigDecimal.ZERO,
            new BigDecimal("0.10"),
            365,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    var projected =
        new InvestmentSimulationCalculator()
            .calculate(
                1,
                start,
                90,
                principal,
                InvestmentProductFixtures.nativeProduct(),
                settings(365, 7, 7),
                InvestmentProductData.InterestPayoutMode.AT_MATURITY,
                false,
                published("10"),
                selected("10", principal, 90));
    assertEquals(new BigDecimal("246.58"), Money.of(currency, unrounded).getAmount());
    assertEquals(Money.of(currency, unrounded).getAmount(), projected.grossInterest());
  }

  @Test
  void nativeApproximateMonthQuoteCannotSubstituteForDayBasedMaturity() {
    var nativeApproximation =
        new FixedDepositAccountInterestCalculationServiceImpl(null, null)
            .calculateInterestInternal(new BigDecimal("10000"), new BigDecimal("10"), 3L, 12L);
    // The native approximate helper truncates to whole compounding periods and knows no dates.
    assertEquals(new BigDecimal("10000"), nativeApproximation);
    var projected =
        new InvestmentSimulationCalculator()
            .calculate(
                1,
                LocalDate.of(2026, 1, 1),
                90,
                new BigDecimal("10000"),
                InvestmentProductFixtures.nativeProduct(),
                settings(365, 7, 7),
                InvestmentProductData.InterestPayoutMode.AT_MATURITY,
                false,
                published("10"),
                selected("10", new BigDecimal("10000"), 90));
    assertNotEquals(0, nativeApproximation.compareTo(projected.projectedMaturityValue()));
  }
}
