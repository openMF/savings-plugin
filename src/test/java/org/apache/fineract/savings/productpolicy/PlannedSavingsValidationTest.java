/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlannedSavingsValidationTest {
  static final String JSON =
      """
      {"expectedRevision":0,"configuration":{"savingsProductId":5,"policyVersion":1,
      "employerClientId":10,"arrangementReference":"Proposed payroll arrangement",
      "contributionAmount":123.45,"frequencyUnit":"WEEKS","frequencyInterval":2,
      "startDate":"2026-11-01","termDays":60,"maturityDate":"2026-12-31",
      "status":"CONFIGURATION_ONLY"}}
      """;

  @Test
  void explicitWeeklyProjectionIncludesStartAndExcludesMaturity() {
    var c = PlannedSavingsValidation.configuration(JSON).configuration();
    assertEquals(
        List.of(
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 15),
            LocalDate.of(2026, 11, 29),
            LocalDate.of(2026, 12, 13),
            LocalDate.of(2026, 12, 27)),
        PlannedContributionSchedule.dates(c));
    assertFalse(PlannedSavingsData.capabilities().contributionScheduler());
    assertFalse(PlannedSavingsData.capabilities().nativeAccountCreation());
    assertFalse(PlannedSavingsData.capabilities().payrollExecution());
    assertFalse(PlannedSavingsData.capabilities().pocketTransfer());
    assertFalse(PlannedSavingsData.capabilities().accountClosure());
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "\"2\"", "0", "-1", "1.5", "1e2", "null", "36501"})
  void rejectsInvalidIntervals(String interval) {
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            PlannedSavingsValidation.configuration(
                JSON.replace("\"frequencyInterval\":2", "\"frequencyInterval\":" + interval)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"0", "-1", "\"1\"", "true", "1.0000001", "10000000000000", "1e9999", "null"})
  void rejectsInvalidMoney(String amount) {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> PlannedSavingsValidation.configuration(JSON.replace("123.45", amount)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"{", "{}", "{\"expectedRevision\":0,\"expectedRevision\":1}", "[]", "{} {}"})
  void rejectsMalformedAndIncompleteRequests(String json) {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> PlannedSavingsValidation.configuration(json));
  }

  @Test
  void rejectsUnsupportedAuthorityStatusDatesAndUnboundedProjection() {
    for (var entry :
        List.of(
            JSON.replace("\"status\":\"CONFIGURATION_ONLY\"", "\"status\":\"APPROVED\""),
            JSON.replace("\"expectedRevision\":0", "\"expectedRevision\":0,\"override\":true"),
            JSON.replace("\"frequencyUnit\":\"WEEKS\"", "\"frequencyUnit\":\"PAYROLL\""),
            JSON.replace("2026-12-31", "2026-12-30"),
            JSON.replace("2026-11-01", "2026-02-30"),
            JSON.replace("Proposed payroll arrangement", " "),
            JSON.replace("\"WEEKS\"", "\"MONTHS\""),
            JSON.replace("\"termDays\":60", "\"termDays\":2000")
                .replace("2026-12-31", "2032-04-23")
                .replace("\"WEEKS\"", "\"DAYS\"")
                .replace("\"frequencyInterval\":2", "\"frequencyInterval\":1"))) {
      assertThrows(
          PlatformApiDataValidationException.class,
          () -> PlannedSavingsValidation.configuration(entry));
    }
  }

  @Test
  void monthEndRuleIsExplicitAndAlwaysUsesOriginalAnchor() {
    var root = JsonParser.parseString(JSON).getAsJsonObject();
    var c = root.getAsJsonObject("configuration");
    c.addProperty("frequencyUnit", "MONTHS");
    c.addProperty("frequencyInterval", 1);
    c.addProperty("startDate", "2028-01-31");
    c.addProperty("termDays", 100);
    c.addProperty("maturityDate", "2028-05-10");
    c.addProperty("shortMonthRule", "LAST_DAY");
    assertEquals(
        List.of(
            LocalDate.of(2028, 1, 31),
            LocalDate.of(2028, 2, 29),
            LocalDate.of(2028, 3, 31),
            LocalDate.of(2028, 4, 30)),
        PlannedContributionSchedule.dates(
            PlannedSavingsValidation.configuration(root.toString()).configuration()));
    c.addProperty("shortMonthRule", "SKIP");
    assertEquals(
        List.of(LocalDate.of(2028, 1, 31), LocalDate.of(2028, 3, 31)),
        PlannedContributionSchedule.dates(
            PlannedSavingsValidation.configuration(root.toString()).configuration()));
    c.addProperty("frequencyUnit", "DAYS");
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> PlannedSavingsValidation.configuration(root.toString()));
  }

  @Test
  void projectionCannotClaimContributionCompletionOrMissWithoutNativeMatching() {
    var date = LocalDate.of(2026, 11, 1);
    assertEquals(
        "PROJECTED_UNAPPROVED",
        PlannedContributionSchedule.status(
            date, date.minusDays(1), PlannedSavingsData.ArrangementStatus.CONFIGURATION_ONLY));
    assertEquals(
        "DUE_UNVERIFIED",
        PlannedContributionSchedule.status(
            date, date, PlannedSavingsData.ArrangementStatus.CONFIGURATION_ONLY));
    assertEquals(
        "OVERDUE_UNVERIFIED",
        PlannedContributionSchedule.status(
            date, date.plusDays(1), PlannedSavingsData.ArrangementStatus.CONFIGURATION_ONLY));
    assertEquals(
        "CANCELLED_CONFIGURATION",
        PlannedContributionSchedule.status(
            date, date, PlannedSavingsData.ArrangementStatus.CANCELLED));
  }
}
