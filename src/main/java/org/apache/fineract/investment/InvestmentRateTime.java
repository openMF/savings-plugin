/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentProductValidation.invalid;
import static org.apache.fineract.investment.InvestmentRateData.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.stereotype.Component;

/**
 * Wall-clock pricing with explicit tenant timezone; Fineract business date is a publication gate.
 */
@Component
public class InvestmentRateTime {
  private final Clock clock;

  public InvestmentRateTime() {
    this(Clock.systemUTC());
  }

  InvestmentRateTime(Clock clock) {
    this.clock = clock;
  }

  Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  static LocalDate date(String value, String field) {
    try {
      if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        throw new IllegalArgumentException();
      return LocalDate.parse(value);
    } catch (RuntimeException e) {
      throw invalid(field, "An ISO date (yyyy-MM-dd) is required.");
    }
  }

  static Instant instant(String value) {
    try {
      return Instant.parse(value);
    } catch (RuntimeException e) {
      throw invalid("asOfInstant", "An ISO instant with UTC offset is required.");
    }
  }

  String timezone() {
    var tenant = ThreadLocalContextUtil.getTenant();
    String zone = tenant == null ? null : tenant.getTimezoneId();
    try {
      return ZoneId.of(zone).getId();
    } catch (RuntimeException e) {
      return null;
    }
  }

  List<String> blockers(EffectiveValidity validity, String zone, Instant now) {
    var result = new ArrayList<String>();
    if (zone == null) return List.of("TENANT_TIMEZONE_MISSING_OR_INVALID");
    var today = now.atZone(ZoneId.of(zone)).toLocalDate();
    if (!today.equals(ThreadLocalContextUtil.getBusinessDate()))
      result.add("BUSINESS_DATE_DIFFERS_FROM_TENANT_CALENDAR_DATE");
    if (validity.mode() == ValidityMode.SCHEDULED) {
      var date = date(validity.effectiveDate(), "effectiveDate");
      if (!date.isAfter(today)) result.add("SCHEDULE_MUST_BE_FUTURE_TENANT_DATE");
      // Some zones skip/repeat midnight. Never silently shift a required 00:00 boundary.
      if (ZoneId.of(zone).getRules().getValidOffsets(date.atStartOfDay()).size() != 1)
        result.add("SCHEDULE_MIDNIGHT_AMBIGUOUS_OR_NONEXISTENT");
    }
    return List.copyOf(result);
  }

  static Instant boundary(EffectiveValidity validity, String zone, Instant now) {
    return validity.mode() == ValidityMode.IMMEDIATE
        ? now
        : date(validity.effectiveDate(), "effectiveDate").atStartOfDay(ZoneId.of(zone)).toInstant();
  }

  static long micros(Instant value) {
    return Math.addExact(
        Math.multiplyExact(value.getEpochSecond(), 1_000_000L), value.getNano() / 1000);
  }

  static Instant fromMicros(long value) {
    return Instant.ofEpochSecond(
        Math.floorDiv(value, 1_000_000L), Math.floorMod(value, 1_000_000L) * 1000);
  }
}
