/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ReferenceRateData.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReferenceRateValidationTest {
  static final LocalDate DAY = LocalDate.of(2026, 11, 1);
  static final String FORMULA =
      """
      {"referenceId":"CETES_TEST","referenceType":"CETES","tenor":"TEST_TENOR",
       "formulaMode":"ADDITIVE_SPREAD","spreadPercentagePoints":1.50,"reviewFrequency":"DAILY"}
      """;

  static String policy(long revision, String formula) {
    var o = ProductPolicyValidation.object(ProductPolicyValidationTest.JSON);
    o.addProperty("expectedRevision", revision);
    var p = o.getAsJsonObject("policy");
    p.addProperty("classification", "SAVINGS");
    p.addProperty("interestMode", "REFERENCE_RATE");
    if (formula != null) p.add("referenceRate", ProductPolicyValidation.object(formula));
    return o.toString();
  }

  static Formula formula(String json) {
    return ReferenceRateValidation.formula(ProductPolicyValidation.object(json));
  }

  static Fixing fixing(String rate, LocalDate date, Status status) {
    return new Fixing(
        "CETES_TEST",
        "TEST_TENOR",
        7,
        2,
        status,
        new BigDecimal(rate),
        date,
        date,
        "Manual source",
        "Illustrative test",
        null,
        1L,
        1L,
        2L,
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH,
        "Reviewed");
  }

  static ProductPolicyWorkflowData.Version version(
      String formula, long version, LocalDate effective) {
    var p = new ProductPolicyValidation().parse(policy(0, formula)).policy();
    return new ProductPolicyWorkflowData.Version(
        1,
        version,
        "APPROVED",
        effective,
        null,
        1L,
        2L,
        Instant.EPOCH,
        Instant.EPOCH,
        null,
        p,
        Map.of());
  }

  @AfterEach
  void clear() {
    ThreadLocalContextUtil.reset();
  }

  @ParameterizedTest
  @CsvSource({
    "ADDITIVE_SPREAD,1.50,9.75",
    "ADDITIVE_SPREAD,0,8.25",
    "ADDITIVE_SPREAD,-1.50,6.75",
    "MULTIPLICATIVE_FACTOR,1,8.25",
    "MULTIPLICATIVE_FACTOR,1.20,9.90",
    "MULTIPLICATIVE_FACTOR,0.80,6.60"
  })
  void exactIllustrativeMath(String mode, String operand, String expected) {
    String f =
        FORMULA
            .replace("ADDITIVE_SPREAD", mode)
            .replace(
                "spreadPercentagePoints",
                mode.equals("ADDITIVE_SPREAD") ? "spreadPercentagePoints" : "multiplier")
            .replace("1.50", operand);
    var calculation =
        ReferenceRateCalculator.preview(
            version(f, 3, DAY), fixing("8.25", DAY, Status.APPROVED), DAY);
    assertEquals(0, new BigDecimal(expected).compareTo(calculation.calculatedAnnualRatePercent()));
    assertEquals(new BigDecimal("8.25"), calculation.benchmarkAnnualRatePercent());
    assertEquals(3, calculation.policyVersion());
    assertEquals(7, calculation.benchmarkVersion());
    assertFalse(calculation.financialOperational());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"0", "-1", "null", "true", "\"1.2\"", "1e2", "0.1234567890123", "10000000000000"})
  void invalidMultiplier(String value) {
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            formula(
                FORMULA
                    .replace("ADDITIVE_SPREAD", "MULTIPLICATIVE_FACTOR")
                    .replace("spreadPercentagePoints", "multiplier")
                    .replace("1.50", value)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"null", "true", "\"1.5\"", "1e2", "1.0000000000000", "10000000000000", "[]"})
  void invalidSpread(String value) {
    assertThrows(
        PlatformApiDataValidationException.class, () -> formula(FORMULA.replace("1.50", value)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "referenceId",
        "referenceType",
        "tenor",
        "formulaMode",
        "spreadPercentagePoints",
        "reviewFrequency"
      })
  void missingRequired(String field) {
    var f = ProductPolicyValidation.object(FORMULA);
    f.remove(field);
    assertThrows(PlatformApiDataValidationException.class, () -> formula(f.toString()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "referenceType:OTHER",
        "tenor:",
        "formulaMode:UNKNOWN",
        "reviewFrequency:MONTHLY",
        "reviewFrequency:WEEKLY",
        "referenceId:bad id"
      })
  void unsupportedOptions(String option) {
    var parts = option.split(":", -1);
    var f = ProductPolicyValidation.object(FORMULA);
    f.addProperty(parts[0], parts[1]);
    assertThrows(PlatformApiDataValidationException.class, () -> formula(f.toString()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"multiplier", "minimumRatePercent", "maximumRatePercent", "makerId", "tenantId"})
  void conflictingOrUnsupportedFields(String field) {
    var f = ProductPolicyValidation.object(FORMULA);
    f.addProperty(field, 1);
    assertThrows(PlatformApiDataValidationException.class, () -> formula(f.toString()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"DAILY", "END_OF_MONTH"})
  void reviewIsNonoperationalMetadata(String value) {
    assertEquals(
        ReviewFrequency.valueOf(value), formula(FORMULA.replace("DAILY", value)).reviewFrequency());
  }

  @Test
  void exactnessWithoutIntermediateRounding() {
    String f =
        FORMULA
            .replace("ADDITIVE_SPREAD", "MULTIPLICATIVE_FACTOR")
            .replace("spreadPercentagePoints", "multiplier")
            .replace("1.50", "1.123456789012");
    var rate =
        ReferenceRateCalculator.preview(
                version(f, 1, DAY), fixing("8.123456789012", DAY, Status.APPROVED), DAY)
            .calculatedAnnualRatePercent();
    assertEquals(new BigDecimal("8.123456789012").multiply(new BigDecimal("1.123456789012")), rate);
    assertEquals(24, rate.scale());
  }

  @Test
  void outputOverflowAndNegativeFailClosed() {
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            ReferenceRateCalculator.preview(
                version(FORMULA.replace("1.50", "-9"), 1, DAY),
                fixing("8.25", DAY, Status.APPROVED),
                DAY));
    String f =
        FORMULA
            .replace("ADDITIVE_SPREAD", "MULTIPLICATIVE_FACTOR")
            .replace("spreadPercentagePoints", "multiplier")
            .replace("1.50", "9999999999999");
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            ReferenceRateCalculator.preview(
                version(f, 1, DAY), fixing("9999999999999", DAY, Status.APPROVED), DAY));
  }

  @Test
  void unapprovedFutureStaleOrMismatchedInputsCannotCalculate() {
    for (var state : List.of(Status.DRAFT, Status.PENDING_APPROVAL, Status.REJECTED))
      assertThrows(
          PlatformApiDataValidationException.class,
          () ->
              ReferenceRateCalculator.preview(
                  version(FORMULA, 1, DAY), fixing("8.25", DAY, state), DAY));
    for (var date : List.of(DAY.minusDays(1), DAY.plusDays(1)))
      assertThrows(
          PlatformApiDataValidationException.class,
          () ->
              ReferenceRateCalculator.preview(
                  version(FORMULA, 1, DAY), fixing("8.25", date, Status.APPROVED), DAY));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            ReferenceRateCalculator.preview(
                version(FORMULA, 1, DAY.plusDays(1)), fixing("8.25", DAY, Status.APPROVED), DAY));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            ReferenceRateCalculator.preview(
                version(FORMULA.replace("TEST_TENOR", "OTHER"), 1, DAY),
                fixing("8.25", DAY, Status.APPROVED),
                DAY));
  }

  @Test
  void parentPolicyCannotBypassFormulaValidationOrMode() {
    var v = new ProductPolicyValidation();
    assertNotNull(v.parse(policy(0, FORMULA)).policy().referenceRate());
    assertNull(v.parse(ProductPolicyValidationTest.JSON).policy().referenceRate());
    for (String mode : List.of("NONE", "FIXED", "TIERED"))
      assertThrows(
          PlatformApiDataValidationException.class,
          () -> v.parse(policy(0, FORMULA).replace("REFERENCE_RATE", mode)));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            v.parse(
                policy(0, FORMULA)
                    .replace("\"classification\":\"SAVINGS\"", "\"classification\":\"SIGHT\"")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> v.parse(policy(0, FORMULA.replace("1.50", "1e1"))));
  }

  static String capture(long revision, LocalDate effective, String rate) {
    return "{\"expectedRevision\":"
        + revision
        + ",\"tenor\":\"TEST_TENOR\",\"annualRatePercent\":"
        + rate
        + ",\"publicationDate\":\""
        + DAY
        + "\",\"effectiveDate\":\""
        + effective
        + "\",\"sourceReference\":\"Operations manual source #1\"}";
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "-1", "\"8.25\"", "8.1234567890123", "1e2", "true", "{}"})
  void invalidFixingNumbers(String n) {
    ProductPolicyWorkflowIntegrationTest.setDay(DAY);
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> ReferenceRateValidation.capture(capture(0, DAY, n)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-02-30", "2026-1-01", "0000-01-01", "2026-11-01T00:00:00Z", ""})
  void invalidDates(String n) {
    assertThrows(
        PlatformApiDataValidationException.class, () -> ReferenceRateValidation.date(n, "date"));
  }

  @Test
  void fixingDatesSourceUnknownIdentityAndDuplicateJson() {
    ProductPolicyWorkflowIntegrationTest.setDay(DAY);
    String body = capture(0, DAY, "8.25");
    assertEquals(new BigDecimal("8.25"), ReferenceRateValidation.capture(body).annualRatePercent());
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> ReferenceRateValidation.capture(capture(0, DAY.minusDays(1), "8.25")));
    var o = ProductPolicyValidation.object(body);
    o.addProperty("publicationDate", DAY.plusDays(1).toString());
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> ReferenceRateValidation.capture(o.toString()));
    for (String field :
        List.of(
            "sourceReference", "tenor", "annualRatePercent", "publicationDate", "effectiveDate")) {
      var b = ProductPolicyValidation.object(body);
      b.remove(field);
      assertThrows(
          PlatformApiDataValidationException.class,
          () -> ReferenceRateValidation.capture(b.toString()));
    }
    for (String field : List.of("makerId", "tenantId", "status")) {
      var b = ProductPolicyValidation.object(body);
      b.addProperty(field, "spoof");
      assertThrows(
          PlatformApiDataValidationException.class,
          () -> ReferenceRateValidation.capture(b.toString()));
    }
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            ReferenceRateValidation.capture(body.replace("8.25", "8.25,\"annualRatePercent\":9")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> ReferenceRateValidation.capture(body + "{}"));
    ThreadLocalContextUtil.reset();
    assertThrows(
        PlatformApiDataValidationException.class, () -> ReferenceRateValidation.capture(body));
  }

  @Test
  void actionRequiresRevisionAndRejectionReason() {
    for (String body :
        List.of(
            "{}",
            "{\"expectedRevision\":\"1\"}",
            "{\"expectedRevision\":1,\"reason\":\"\"}",
            "{\"expectedRevision\":1,\"checkerId\":2}"))
      assertThrows(
          PlatformApiDataValidationException.class,
          () -> ReferenceRateValidation.action(body, true));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> ReferenceRateValidation.action("{\"expectedRevision\":1}", true));
  }
}
