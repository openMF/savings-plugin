/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentSimulationData.*;
import static org.apache.fineract.investment.InvestmentSimulationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class InvestmentSimulationCalculatorTest {
  final InvestmentSimulationCalculator calculator = new InvestmentSimulationCalculator();

  @BeforeEach
  void tenant() {
    var tenant = mock(FineractPlatformTenant.class);
    when(tenant.getTenantIdentifier()).thenReturn("be03-calculator");
    ThreadLocalContextUtil.setTenant(tenant);
    MoneyHelper.initializeTenantRoundingMode("be03-calculator", RoundingMode.HALF_EVEN.ordinal());
  }

  @AfterEach
  void clear() {
    MoneyHelper.clearCacheForTenant("be03-calculator");
    ThreadLocalContextUtil.reset();
  }

  Cycle calculate(
      String date,
      int term,
      int basis,
      int compounding,
      int posting,
      InvestmentProductData.InterestPayoutMode payout,
      boolean renews,
      String amount,
      String rate) {
    var principal = new BigDecimal(amount);
    return calculator.calculate(
        1,
        LocalDate.parse(date),
        term,
        principal,
        InvestmentProductFixtures.nativeProduct(),
        settings(basis, compounding, posting),
        payout,
        renews,
        published(rate),
        selected(rate, principal, term));
  }

  @ParameterizedTest
  @CsvSource({"360,250.00,10250.00", "365,246.58,10246.58"})
  void verifiedActualDayBasesAndMaturityOnlySettlement(int basis, String gross, String maturity) {
    var result =
        calculate(
            "2026-01-01",
            90,
            basis,
            7,
            7,
            InvestmentProductData.InterestPayoutMode.AT_MATURITY,
            false,
            "10000",
            "10");
    assertEquals(new BigDecimal(gross), result.grossInterest());
    assertEquals(new BigDecimal(maturity), result.projectedMaturityValue());
    assertEquals("2026-04-01", result.maturityDate());
    assertEquals(1, result.paymentSchedule().size());
    assertEquals(result.grossInterest(), result.paymentSchedule().getFirst().interestPayout());
    assertEquals(new BigDecimal("10000"), result.paymentSchedule().getFirst().principalReturned());
  }

  @ParameterizedTest
  @CsvSource({
    "2028-02-28,1,2028-02-29",
    "2028-02-28,30,2028-03-29",
    "2028-01-31,90,2028-04-30",
    "2027-12-31,365,2028-12-30",
    "2026-01-31,1825,2031-01-30"
  })
  void nativeDayTermsCrossLeapMonthAndYearBoundaries(String start, int term, String maturity) {
    var result =
        calculate(
            start,
            term,
            365,
            4,
            4,
            InvestmentProductData.InterestPayoutMode.AT_MATURITY,
            false,
            "10000",
            "7.123456");
    assertEquals(maturity, result.maturityDate());
    assertEquals(start, result.accrualSchedule().getFirst().periodStartDate());
    assertEquals(maturity, result.accrualSchedule().getLast().periodEndDateExclusive());
    for (int i = 1; i < result.accrualSchedule().size(); i++)
      assertEquals(
          result.accrualSchedule().get(i - 1).periodEndDateExclusive(),
          result.accrualSchedule().get(i).periodStartDate());
    assertEquals(
        result.grossInterest(),
        result.accrualSchedule().stream()
            .map(Period::grossInterest)
            .reduce(new BigDecimal("0.00"), BigDecimal::add));
  }

  @Test
  void cedeMonthlyPayoutUsesActualCalendarDaysWithNoInterestReinvestment() {
    var result =
        calculate(
            "2028-01-31",
            60,
            360,
            4,
            4,
            InvestmentProductData.InterestPayoutMode.MONTHLY,
            false,
            "10000",
            "10");
    assertEquals(
        List.of("2028-02-01", "2028-03-01", "2028-03-31"),
        result.paymentSchedule().stream().map(Payment::paymentDate).toList());
    assertEquals(
        List.of(new BigDecimal("2.78"), new BigDecimal("80.56"), new BigDecimal("83.33")),
        result.paymentSchedule().stream().map(Payment::interestPayout).toList());
    assertEquals(new BigDecimal("166.67"), result.grossInterest());
    assertEquals(new BigDecimal("10083.33"), result.projectedMaturityValue());
    assertTrue(
        result.paymentSchedule().subList(0, 2).stream()
            .allMatch(p -> p.principalReturned().signum() == 0));
    assertEquals(0, result.capitalizedOnRenewal().signum());
    assertTrue(
        result.accrualSchedule().stream()
            .allMatch(p -> p.interestCarriedIntoCompounding().signum() == 0));
  }

  @Test
  void nativeInTermMonthlyCompoundingIsDistinctFromRenewalCapitalization() {
    var compound =
        calculate(
            "2026-01-01",
            90,
            360,
            4,
            4,
            InvestmentProductData.InterestPayoutMode.AT_MATURITY,
            false,
            "10000",
            "10");
    var payouts =
        calculate(
            "2026-01-01",
            90,
            360,
            4,
            4,
            InvestmentProductData.InterestPayoutMode.MONTHLY,
            false,
            "10000",
            "10");
    assertTrue(compound.grossInterest().compareTo(payouts.grossInterest()) > 0);
    assertEquals(1, compound.paymentSchedule().size());
    assertEquals(0, compound.capitalizedOnRenewal().signum());
    assertTrue(compound.accrualSchedule().get(1).interestCarriedIntoCompounding().signum() > 0);
    var renewal =
        calculate(
            "2026-01-01",
            90,
            360,
            4,
            4,
            InvestmentProductData.InterestPayoutMode.CAPITALIZE_ON_RENEWAL,
            true,
            "10000",
            "10");
    assertEquals(compound.grossInterest(), renewal.capitalizedOnRenewal());
    assertEquals(0, renewal.interestPayoutTotal().signum());
    assertEquals(renewal.openingPrincipal().add(renewal.grossInterest()), renewal.nextPrincipal());
    assertEquals(0, renewal.paymentSchedule().getFirst().principalReturned().signum());
  }

  @Test
  void terminalCyclePaysInterestRatherThanCapitalizingWithoutARenewal() {
    var result =
        calculate(
            "2026-01-01",
            90,
            360,
            7,
            7,
            InvestmentProductData.InterestPayoutMode.CAPITALIZE_ON_RENEWAL,
            false,
            "10000",
            "10");
    assertEquals(new BigDecimal("250.00"), result.interestPayoutTotal());
    assertEquals(0, result.capitalizedOnRenewal().signum());
  }

  @Test
  void nativeRoundingModeControlsHalfCentAndHighPrecisionRatesArePreserved() {
    var even =
        calculate(
            "2026-01-01",
            1,
            360,
            7,
            7,
            InvestmentProductData.InterestPayoutMode.AT_MATURITY,
            false,
            "100",
            "1.8");
    assertEquals(new BigDecimal("0.00"), even.grossInterest());
    MoneyHelper.updateTenantRoundingMode("be03-calculator", RoundingMode.HALF_UP.ordinal());
    var principal = new BigDecimal("100");
    var context = new NativeSettings(1, 360, 7, 7, BigDecimal.ZERO, 1, false, "HALF_UP", 19);
    var up =
        calculator.calculate(
            1,
            LocalDate.of(2026, 1, 1),
            1,
            principal,
            InvestmentProductFixtures.nativeProduct(),
            context,
            InvestmentProductData.InterestPayoutMode.AT_MATURITY,
            false,
            published("1.8"),
            selected("1.8", principal, 1));
    assertEquals(new BigDecimal("0.01"), up.grossInterest());
    MoneyHelper.updateTenantRoundingMode("be03-calculator", RoundingMode.HALF_EVEN.ordinal());
    var high =
        calculate(
            "2026-01-01",
            90,
            360,
            7,
            7,
            InvestmentProductData.InterestPayoutMode.AT_MATURITY,
            false,
            "10000",
            "7.123456");
    assertEquals(new BigDecimal("178.09"), high.grossInterest());
    assertEquals(new BigDecimal("7.123456"), high.rateSelection().annualRatePercent());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 5, 6, 8, 99})
  void unverifiedCompoundingFailsClosed(int compounding) {
    assertFalse(
        calculator
            .blockers(
                settings(365, compounding, 4), InvestmentProductData.InterestPayoutMode.AT_MATURITY)
            .isEmpty());
  }

  @Test
  void missingConventionsNeverBecomeDefaults() {
    var missing = new NativeSettings(null, null, null, null, null, null, null, null, null);
    var blockers =
        calculator.blockers(missing, InvestmentProductData.InterestPayoutMode.AT_MATURITY);
    assertTrue(blockers.contains("NATIVE_DAY_COUNT_MISSING_OR_UNSUPPORTED"));
    assertTrue(blockers.contains("NATIVE_TENANT_ROUNDING_UNINITIALIZED"));
    assertTrue(blockers.contains("NATIVE_POSTING_DATE_CONVENTION_MISSING"));
  }

  @Test
  void nativeAverageDailyBalanceAndCurrencyScalesUseTheVerifiedMonetaryRules() {
    var principal = new BigDecimal("10000");
    var settings = new NativeSettings(2, 365, 7, 7, BigDecimal.ZERO, 1, false, "HALF_EVEN", 19);
    for (var example :
        List.of(new String[] {"6", "1", "246.575342"}, new String[] {"0", "5", "245"})) {
      var nativeJson =
          InvestmentProductJson.JSON
              .toJsonTree(InvestmentProductFixtures.nativeProduct())
              .getAsJsonObject();
      nativeJson.addProperty("currencyDigits", Integer.parseInt(example[0]));
      nativeJson.addProperty("currencyMultiple", Integer.parseInt(example[1]));
      var product =
          InvestmentProductJson.JSON.fromJson(
              nativeJson, InvestmentProductData.NativeProduct.class);
      var result =
          calculator.calculate(
              1,
              LocalDate.of(2026, 1, 1),
              90,
              principal,
              product,
              settings,
              InvestmentProductData.InterestPayoutMode.AT_MATURITY,
              false,
              published("10"),
              selected("10", principal, 90));
      assertEquals(new BigDecimal(example[2]), result.grossInterest());
    }
  }

  @Test
  void nativePeriodEndPostingDatesAndConfiguredZeroRatesAreRepresentedExactly() {
    var principal = new BigDecimal("10000");
    var settings = new NativeSettings(1, 360, 4, 4, BigDecimal.ZERO, 1, true, "HALF_EVEN", 19);
    var result =
        calculator.calculate(
            1,
            LocalDate.of(2028, 1, 31),
            60,
            principal,
            InvestmentProductFixtures.nativeProduct(),
            settings,
            InvestmentProductData.InterestPayoutMode.MONTHLY,
            false,
            published("0"),
            selected("0", principal, 60));
    assertEquals(
        List.of("2028-01-31", "2028-02-29", "2028-03-31"),
        result.paymentSchedule().stream().map(Payment::paymentDate).toList());
    assertEquals(new BigDecimal("0.00"), result.grossInterest());
    assertEquals(new BigDecimal("10000.00"), result.projectedMaturityValue());
  }
}
