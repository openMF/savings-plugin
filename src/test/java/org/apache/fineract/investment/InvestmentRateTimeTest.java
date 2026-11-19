/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentRateData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InvestmentRateTimeTest {
  void tenant(String zone, String businessDate) {
    var t = mock(FineractPlatformTenant.class);
    when(t.getTimezoneId()).thenReturn(zone);
    ThreadLocalContextUtil.setTenant(t);
    ThreadLocalContextUtil.setBusinessDates(
        new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.parse(businessDate))));
  }

  @AfterEach
  void clear() {
    ThreadLocalContextUtil.reset();
  }

  @Test
  void immediateUsesControllableMicrosecondClockAndNeverDraftTime() {
    var time =
        new InvestmentRateTime(
            Clock.fixed(Instant.parse("2026-11-17T12:00:00.123456789Z"), ZoneOffset.UTC));
    assertEquals("2026-11-17T12:00:00.123456Z", time.now().toString());
    assertEquals(
        time.now(),
        InvestmentRateTime.boundary(
            new EffectiveValidity(ValidityMode.IMMEDIATE, null), "UTC", time.now()));
    assertEquals(time.now(), InvestmentRateTime.fromMicros(InvestmentRateTime.micros(time.now())));
    assertEquals(
        Instant.parse("1960-01-01T00:00:00.123456Z"),
        InvestmentRateTime.fromMicros(
            InvestmentRateTime.micros(Instant.parse("1960-01-01T00:00:00.123456Z"))));
  }

  @Test
  void usesTenantZoneWithNoJvmDefaultFallback() {
    var time = new InvestmentRateTime();
    tenant(null, "2026-11-17");
    assertNull(time.timezone());
    assertEquals(
        java.util.List.of("TENANT_TIMEZONE_MISSING_OR_INVALID"),
        time.blockers(
            new EffectiveValidity(ValidityMode.IMMEDIATE, null),
            null,
            Instant.parse("2026-11-17T12:00:00Z")));
    tenant("Asia/Kolkata", "2026-11-17");
    var validity = new EffectiveValidity(ValidityMode.SCHEDULED, "2026-11-18");
    assertEquals(
        Instant.parse("2026-11-17T18:30:00Z"),
        InvestmentRateTime.boundary(validity, time.timezone(), Instant.EPOCH));
    assertTrue(
        time.blockers(validity, time.timezone(), Instant.parse("2026-11-17T12:00:00Z")).isEmpty());
  }

  @Test
  void daylightSavingSchedulesUseOffsetsAtTheirEffectiveDate() {
    var validity = new EffectiveValidity(ValidityMode.SCHEDULED, "2026-03-09");
    assertEquals(
        Instant.parse("2026-03-09T04:00:00Z"),
        InvestmentRateTime.boundary(validity, "America/New_York", Instant.EPOCH));
    var before = new EffectiveValidity(ValidityMode.SCHEDULED, "2026-03-08");
    assertEquals(
        Instant.parse("2026-03-08T05:00:00Z"),
        InvestmentRateTime.boundary(before, "America/New_York", Instant.EPOCH));
  }

  @Test
  void skippedAndRepeatedMidnightsAndDifferentBusinessDateBlockPublication() {
    var time = new InvestmentRateTime();
    tenant("Pacific/Apia", "2011-12-29");
    assertTrue(
        time.blockers(
                new EffectiveValidity(ValidityMode.SCHEDULED, "2011-12-30"),
                "Pacific/Apia",
                Instant.parse("2011-12-29T12:00:00Z"))
            .contains("SCHEDULE_MIDNIGHT_AMBIGUOUS_OR_NONEXISTENT"));
    // Use a historical overlap: future DST rules can change between JDK tzdb updates.
    tenant("America/Havana", "2020-10-31");
    assertEquals(
        2,
        ZoneId.of("America/Havana")
            .getRules()
            .getValidOffsets(LocalDate.of(2020, 11, 1).atStartOfDay())
            .size());
    assertTrue(
        time.blockers(
                new EffectiveValidity(ValidityMode.SCHEDULED, "2020-11-01"),
                "America/Havana",
                Instant.parse("2020-10-31T12:00:00Z"))
            .contains("SCHEDULE_MIDNIGHT_AMBIGUOUS_OR_NONEXISTENT"));
    tenant("UTC", "2026-11-16");
    assertTrue(
        time.blockers(
                new EffectiveValidity(ValidityMode.IMMEDIATE, null),
                "UTC",
                Instant.parse("2026-11-17T12:00:00Z"))
            .contains("BUSINESS_DATE_DIFFERS_FROM_TENANT_CALENDAR_DATE"));
  }
}
