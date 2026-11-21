/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentSimulationData.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.savings.domain.interest.CompoundInterestValues;
import org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod;
import org.springframework.stereotype.Component;

/**
 * Executes only native in-memory period arithmetic. The native approximate month quote and
 * floating-point daily compounding path are deliberately not used. No posting service is injected.
 */
@Component
public class InvestmentSimulationCalculator {
  public List<String> blockers(
      NativeSettings settings, InvestmentProductData.InterestPayoutMode payout) {
    var result = new ArrayList<String>();
    if (settings.calculationType() == null || !List.of(1, 2).contains(settings.calculationType()))
      result.add("NATIVE_INTEREST_CALCULATION_TYPE_MISSING_OR_UNSUPPORTED");
    if (settings.daysInYear() == null || !List.of(360, 365).contains(settings.daysInYear()))
      result.add("NATIVE_DAY_COUNT_MISSING_OR_UNSUPPORTED");
    if (Integer.valueOf(1).equals(settings.compoundingPeriod()))
      result.add("NATIVE_DAILY_COMPOUNDING_USES_FLOATING_POINT");
    else if (settings.compoundingPeriod() == null
        || !List.of(4, 7).contains(settings.compoundingPeriod()))
      result.add("NATIVE_COMPOUNDING_PERIOD_MISSING_OR_UNVERIFIED");
    if (settings.postingPeriod() == null || !List.of(4, 7).contains(settings.postingPeriod()))
      result.add("NATIVE_POSTING_PERIOD_MISSING_OR_UNVERIFIED");
    if (settings.financialYearBeginningMonth() == null
        || settings.financialYearBeginningMonth() < 1
        || settings.financialYearBeginningMonth() > 12)
      result.add("NATIVE_FINANCIAL_YEAR_CONVENTION_MISSING_OR_INVALID");
    // Native annual compounding can construct a next-year February boundary from this year's
    // month length. Until parity across leap years is verified, do not enable that combination.
    if (Integer.valueOf(7).equals(settings.compoundingPeriod())
        && Integer.valueOf(3).equals(settings.financialYearBeginningMonth()))
      result.add("NATIVE_ANNUAL_FEBRUARY_BOUNDARY_UNVERIFIED");
    if (settings.postingAtCurrentPeriodEnd() == null)
      result.add("NATIVE_POSTING_DATE_CONVENTION_MISSING");
    if (settings.roundingMode() == null || settings.monetaryMathPrecision() == null)
      result.add("NATIVE_TENANT_ROUNDING_UNINITIALIZED");
    if (settings.minimumBalanceForInterest() == null
        || settings.minimumBalanceForInterest().signum() < 0)
      result.add("NATIVE_MINIMUM_INTEREST_BALANCE_INVALID");
    if (payout == InvestmentProductData.InterestPayoutMode.MONTHLY
        && (!Integer.valueOf(4).equals(settings.postingPeriod())
            || !Integer.valueOf(4).equals(settings.compoundingPeriod())))
      result.add("MONTHLY_PAYOUT_REQUIRES_MATCHING_NATIVE_MONTHLY_COMPOUNDING_AND_POSTING");
    return List.copyOf(result);
  }

  Convention convention(
      NativeSettings settings,
      InvestmentProductData.NativeProduct product,
      InvestmentProductData.InterestPayoutMode payout) {
    return new Convention(
        settings,
        product.currencyDigits(),
        product.currencyMultiple(),
        "NOMINAL_ANNUAL_PERCENT_DIVIDED_BY_100_NATIVE_MONETARY_CONTEXT",
        "START_INCLUSIVE_MATURITY_EXCLUSIVE_ACTUAL_DAYS_FIXED_NATIVE_YEAR_BASIS",
        "START_PLUS_TERM_DAYS_UNADJUSTED",
        "NATIVE_CALENDAR_POSTING_DATES_RESIDUAL_AT_MATURITY_NO_BUSINESS_DAY_ADJUSTMENT",
        "NATIVE_DECIMAL64_ACCRUAL_SCALE_9_THEN_MONEY_PER_POSTING_PERIOD_SUM_ROUNDED_PERIODS",
        payout == InvestmentProductData.InterestPayoutMode.CAPITALIZE_ON_RENEWAL
            ? "ROUNDED_MATURITY_INTEREST_ADDED_ON_REQUESTED_RENEWALS_TERMINAL_INTEREST_PAID"
            : "PRINCIPAL_ONLY_RENEWAL_INTEREST_PAID_SEPARATELY",
        ENGINE_VERSION);
  }

  Cycle calculate(
      int number,
      LocalDate start,
      int term,
      BigDecimal principal,
      InvestmentProductData.NativeProduct product,
      NativeSettings settings,
      InvestmentProductData.InterestPayoutMode payout,
      boolean renews,
      InvestmentRateData.Configuration published,
      InvestmentRateData.Selection rate) {
    if (!blockers(settings, payout).isEmpty())
      throw new IllegalArgumentException("Unverified convention");
    var maturity = start.plusDays(term);
    var upTo = maturity.minusDays(1);
    var currency =
        new MonetaryCurrency(
            product.currencyCode(), product.currencyDigits(), product.currencyMultiple());
    var amount = Money.of(currency, principal);
    var minimum = Money.of(currency, settings.minimumBalanceForInterest());
    var zero = Money.zero(currency).getAmount();
    var fraction =
        rate.annualRatePercent().divide(BigDecimal.valueOf(100), MoneyHelper.getMathContext());
    // Fineract 0.0.1706-5309ee1 removed SavingsHelper.determineInterestPostingPeriods (its
    // constructor still requires an AccountTransfersReadPlatformService). The posting-window
    // computation is pure date arithmetic, so it is reimplemented here in-memory as a port of
    // upstream 0.0.1628-bc1c598. Semantics are unchanged: no posting-as-on dates (empty list),
    // calendar posting periods, financial-year start month from NativeSettings, and the posting
    // window [start, upTo] with upTo = maturity - 1 day.
    var intervals =
        determineInterestPostingPeriods(
            start,
            upTo,
            SavingsPostingInterestPeriodType.fromInt(settings.postingPeriod()),
            settings.financialYearBeginningMonth());
    if (intervals.size() > MAX_SCHEDULE_PERIODS)
      throw new IllegalArgumentException("Schedule limit exceeded");
    var periods = new ArrayList<Period>();
    var payments = new ArrayList<Payment>();
    var carried = new CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO);
    BigDecimal gross = zero;
    BigDecimal paid = zero;
    boolean monthly = payout == InvestmentProductData.InterestPayoutMode.MONTHLY;
    for (var interval : intervals) {
      var posting =
          PostingPeriod.createFrom(
              interval,
              amount,
              List.of(),
              currency,
              SavingsCompoundingInterestPeriodType.fromInt(settings.compoundingPeriod()),
              SavingsInterestCalculationType.fromInt(settings.calculationType()),
              fraction,
              settings.daysInYear(),
              upTo,
              List.of(),
              false,
              minimum,
              settings.postingAtCurrentPeriodEnd(),
              false,
              settings.financialYearBeginningMonth());
      BigDecimal incoming = carried.getcompoundedInterest();
      posting.calculateInterest(carried);
      BigDecimal interest = posting.getInterestEarned().getAmount();
      gross = gross.add(interest);
      var end = interval.endDate().isBefore(upTo) ? interval.endDate().plusDays(1) : maturity;
      periods.add(
          new Period(
              periods.size() + 1,
              interval.startDate().toString(),
              end.toString(),
              posting.dateOfPostingTransaction().toString(),
              interest,
              incoming,
              carried.getcompoundedInterest()));
      if (monthly) {
        var date =
            posting.dateOfPostingTransaction().isAfter(maturity)
                ? maturity
                : posting.dateOfPostingTransaction();
        payments.add(
            new Payment(
                payments.size() + 1,
                date.toString(),
                interest,
                zero,
                zero,
                "PROJECTED_NO_TRANSFER"));
        paid = paid.add(interest);
        // Matches removal of accrued interest at each projected payout; never carries payouts into
        // the next principal. Only matching calendar monthly periods are enabled for this mode.
        carried = new CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO);
      }
    }
    boolean capitalizeRenewal =
        renews && payout == InvestmentProductData.InterestPayoutMode.CAPITALIZE_ON_RENEWAL;
    BigDecimal capitalized = capitalizeRenewal ? gross : zero;
    BigDecimal next = capitalizeRenewal ? principal.add(gross) : principal;
    if (!monthly) {
      paid = capitalizeRenewal ? zero : gross;
      payments.add(
          new Payment(
              1,
              maturity.toString(),
              paid,
              capitalized,
              renews ? zero : principal,
              "PROJECTED_NO_SETTLEMENT"));
    } else if (!payments.isEmpty()
        && payments.getLast().paymentDate().equals(maturity.toString())) {
      var last = payments.removeLast();
      payments.add(
          new Payment(
              last.paymentNumber(),
              last.paymentDate(),
              last.interestPayout(),
              zero,
              renews ? zero : principal,
              "PROJECTED_NO_SETTLEMENT"));
    } else {
      payments.add(
          new Payment(
              payments.size() + 1,
              maturity.toString(),
              zero,
              zero,
              renews ? zero : principal,
              "PROJECTED_NO_SETTLEMENT"));
    }
    BigDecimal dueAtMaturity = principal;
    for (var payment : payments)
      if (payment.paymentDate().equals(maturity.toString()))
        dueAtMaturity =
            dueAtMaturity.add(payment.interestPayout()).add(payment.capitalizedOnRenewal());
    // Detect tenant rounding changes while native primitives were executing. Never return mixed
    // rounding results as completed evidence.
    if (!settings.roundingMode().equals(MoneyHelper.getRoundingMode().name())
        || settings.monetaryMathPrecision() != MoneyHelper.getMathContext().getPrecision())
      throw new RoundingChanged();
    return new Cycle(
        number,
        start.toString(),
        maturity.toString(),
        principal,
        published,
        rate,
        Status.COMPLETE_GROSS_PROJECTION,
        gross,
        dueAtMaturity,
        paid,
        capitalized,
        next,
        List.copyOf(periods),
        List.copyOf(payments),
        List.of());
  }

  /**
   * Pure in-memory port of Fineract 0.0.1628-bc1c598's
   * {@code SavingsHelper.determineInterestPostingPeriods}, removed upstream in
   * 0.0.1706-5309ee1. Keeps today's semantics: no posting-as-on dates, calendar posting periods,
   * financial-year start month from NativeSettings, and posting window {@code [start, upTo]}.
   */
  private static List<LocalDateInterval> determineInterestPostingPeriods(
      LocalDate startInterestCalculationLocalDate,
      LocalDate interestPostingUpToDate,
      SavingsPostingInterestPeriodType postingPeriodType,
      Integer financialYearBeginningMonth) {
    var postingPeriods = new ArrayList<LocalDateInterval>();
    if (startInterestCalculationLocalDate == null
        || interestPostingUpToDate == null
        || postingPeriodType == null) {
      return postingPeriods;
    }
    var periodStartDate = startInterestCalculationLocalDate;
    var periodEndDate = periodStartDate;
    var actualPeriodStartDate = periodStartDate;
    final int anniversaryDayOfMonth = startInterestCalculationLocalDate.getDayOfMonth();
    while (!periodStartDate.isAfter(interestPostingUpToDate)
        && !periodEndDate.isAfter(interestPostingUpToDate)) {
      var interestPostingLocalDate =
          determineInterestPostingPeriodEndDateFrom(
              periodStartDate,
              postingPeriodType,
              interestPostingUpToDate,
              financialYearBeginningMonth,
              anniversaryDayOfMonth);
      periodEndDate = interestPostingLocalDate.minusDays(1);
      postingPeriods.add(LocalDateInterval.create(periodStartDate, periodEndDate));
      if (actualPeriodStartDate.equals(periodEndDate)) {
        periodEndDate = actualPeriodStartDate.plusDays(1);
        periodStartDate = actualPeriodStartDate.plusDays(1);
      } else {
        periodEndDate = interestPostingLocalDate;
        periodStartDate = interestPostingLocalDate;
      }
    }
    return postingPeriods;
  }

  private static LocalDate determineInterestPostingPeriodEndDateFrom(
      LocalDate periodStartDate,
      SavingsPostingInterestPeriodType interestPostingPeriodType,
      LocalDate interestPostingUpToDate,
      Integer financialYearBeginningMonth,
      int anniversaryDayOfMonth) {
    // interest posting always occurs on the next day after the period end date.
    var periodEndDate = interestPostingUpToDate.plusDays(1);
    final var monthOfYear = periodStartDate.getMonthValue();
    var fybm = financialYearBeginningMonth;
    fybm--;
    if (fybm == 0) {
      fybm = 12;
    }
    var quarterlyDates = new ArrayList<LocalDate>();
    quarterlyDates.add(periodStartDate.withMonth(fybm).with(TemporalAdjusters.lastDayOfMonth()));
    quarterlyDates.add(
        periodStartDate
            .withMonth(fybm)
            .plusMonths(3)
            .withYear(periodStartDate.getYear())
            .with(TemporalAdjusters.lastDayOfMonth()));
    quarterlyDates.add(
        periodStartDate
            .withMonth(fybm)
            .plusMonths(6)
            .withYear(periodStartDate.getYear())
            .with(TemporalAdjusters.lastDayOfMonth()));
    quarterlyDates.add(
        periodStartDate
            .withMonth(fybm)
            .plusMonths(9)
            .withYear(periodStartDate.getYear())
            .with(TemporalAdjusters.lastDayOfMonth()));
    quarterlyDates.sort(Comparator.naturalOrder());
    var biannualDates = new ArrayList<LocalDate>();
    biannualDates.add(periodStartDate.withMonth(fybm).with(TemporalAdjusters.lastDayOfMonth()));
    biannualDates.add(
        periodStartDate
            .withMonth(fybm)
            .plusMonths(6)
            .withYear(periodStartDate.getYear())
            .with(TemporalAdjusters.lastDayOfMonth()));
    biannualDates.sort(Comparator.naturalOrder());
    boolean quarterlySet = false;
    boolean biannualSet = false;
    switch (interestPostingPeriodType) {
      case INVALID:
        break;
      case DAILY:
        // interest posting always occurs on the next day after the period end date.
        periodEndDate = periodStartDate.plusDays(1);
        break;
      case MONTHLY:
        // interest posting occurs on the first day of the next month and on the next day
        // after the period end date.
        periodEndDate = periodStartDate.with(TemporalAdjusters.lastDayOfMonth()).plusDays(1);
        break;
      case QUATERLY:
        for (var quarterlyDate : quarterlyDates) {
          if (quarterlyDate.isAfter(periodStartDate)) {
            periodEndDate = quarterlyDate.plusDays(1);
            quarterlySet = true;
            break;
          }
        }
        if (!quarterlySet) {
          periodEndDate =
              quarterlyDates.get(0)
                  .plusYears(1)
                  .with(TemporalAdjusters.lastDayOfMonth())
                  .plusDays(1);
        }
        break;
      case BIANNUAL:
        for (var biannualDate : biannualDates) {
          if (biannualDate.isAfter(periodStartDate)) {
            periodEndDate = biannualDate.plusDays(1);
            biannualSet = true;
            break;
          }
        }
        if (!biannualSet) {
          periodEndDate =
              biannualDates.get(0)
                  .plusYears(1)
                  .with(TemporalAdjusters.lastDayOfMonth())
                  .plusDays(1);
        }
        break;
      case ANNUAL:
        if (fybm < monthOfYear) {
          periodEndDate = periodStartDate.withMonth(fybm).plusYears(1);
        } else {
          periodEndDate = periodStartDate.withMonth(fybm);
        }
        // interest posting always occurs on the next day after the period end date.
        periodEndDate = periodEndDate.with(TemporalAdjusters.lastDayOfMonth()).plusDays(1);
        break;
      case ANNIVERSARY_MONTHLY:
        periodEndDate = adjustToAnniversaryDay(periodStartDate.plusMonths(1), anniversaryDayOfMonth);
        break;
      case ANNIVERSARY_QUARTERLY:
        periodEndDate = adjustToAnniversaryDay(periodStartDate.plusMonths(3), anniversaryDayOfMonth);
        break;
      case ANNIVERSARY_BIANNUAL:
        periodEndDate = adjustToAnniversaryDay(periodStartDate.plusMonths(6), anniversaryDayOfMonth);
        break;
      case ANNIVERSARY_ANNUAL:
        periodEndDate = adjustToAnniversaryDay(periodStartDate.plusMonths(12), anniversaryDayOfMonth);
        break;
    }
    return periodEndDate;
  }

  private static LocalDate adjustToAnniversaryDay(LocalDate date, int anniversaryDay) {
    return date.withDayOfMonth(Math.min(anniversaryDay, date.lengthOfMonth()));
  }

  static final class RoundingChanged extends RuntimeException {}
}
