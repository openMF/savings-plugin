/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentRateData.*;
import static org.apache.fineract.investment.InvestmentRateFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InvestmentRateValidationTest {
  final InvestmentRateValidation validation = new InvestmentRateValidation();
  final InvestmentProductData.Policy policy =
      new InvestmentProductValidation().parse(InvestmentProductFixtures.JSON).policy();

  void valid(String json) {
    validation.validate(
        validation.parse(json).matrix(), policy, InvestmentProductFixtures.nativeProduct());
  }

  @Test
  void acceptsExactFullCoverageAndHighPrecisionRates() {
    valid(BASE);
    assertEquals(
        new BigDecimal("7.123456"),
        validation.parse(BASE).matrix().cells().getFirst().annualRatePercent());
    valid(BASE.replace("1000,", "1000.00,"));
    validation.validate(largest(), policy, InvestmentProductFixtures.nativeProduct());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "[]",
        "{",
        "{}",
        "{\"expectedRevision\":0,\"expectedRevision\":0}",
        "{\"expectedRevision\":1.5}",
        "{\"expectedRevision\":\"1\"}",
        "{\"expectedRevision\":9223372036854775808}"
      })
  void rejectsMalformedInput(String json) {
    assertThrows(PlatformApiDataValidationException.class, () -> validation.parse(json));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"NaN", "Infinity", "-1", "1e2", "\"8.00\"", "1.0000001", "10000000000000"})
  void rejectsInvalidRates(String rate) {
    assertThrows(
        PlatformApiDataValidationException.class, () -> valid(BASE.replace("7.123456", rate)));
  }

  @Test
  void rejectsMissingDuplicateAndUnknownCells() {
    for (var mutate :
        List.<java.util.function.Consumer<com.google.gson.JsonArray>>of(
            cells -> cells.remove(0),
            cells -> cells.set(1, cells.get(0)),
            cells -> cells.get(0).getAsJsonObject().addProperty("amountBand", 2)))
      assertThrows(
          PlatformApiDataValidationException.class,
          () ->
              valid(
                  edit(
                      root ->
                          mutate.accept(root.getAsJsonObject("matrix").getAsJsonArray("cells")))));
  }

  @Test
  void rejectsAmountOverlapsGapsAndWrongInclusiveBounds() {
    for (String field : List.of("lower", "upper"))
      assertThrows(
          PlatformApiDataValidationException.class,
          () ->
              valid(
                  edit(
                      root ->
                          root.getAsJsonObject("matrix")
                              .getAsJsonArray("amountBands")
                              .get(1)
                              .getAsJsonObject()
                              .addProperty(field, 999))));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> valid(BASE.replace("\"upperInclusive\":false", "\"upperInclusive\":true")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> valid(BASE.replace("\"upperInclusive\":true", "\"upperInclusive\":false")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> valid(BASE.replace("\"lower\":100,", "\"lower\":-100,")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> valid(BASE.replace("\"lower\":100,", "\"lower\":100.001,")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> valid(BASE.replace("\"upper\":100000", "\"upper\":null")));
  }

  @Test
  void rejectsTermOverlapsGapsAndUnsupportedTerms() {
    for (int day : new int[] {0, 30, 32, 1826})
      assertThrows(
          PlatformApiDataValidationException.class,
          () -> valid(BASE.replace("\"minimumDays\":31", "\"minimumDays\":" + day)));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> valid(BASE.replace("\"maximumDays\":1825", "\"maximumDays\":1824")));
  }

  @Test
  void validatesCedeFromPolicyIncludingSixtyDayMinimumAndThirtyDayIncrement() {
    var p =
        new InvestmentProductValidation()
            .parse(
                InvestmentProductFixtures.request(
                    0,
                    Map.of(
                        "investmentType",
                        "CEDE",
                        "minimumTermDays",
                        60,
                        "maximumTermDays",
                        180,
                        "termMultipleDays",
                        30)))
            .policy();
    var m =
        new Matrix(
            "MXN",
            List.of(new AmountBand(new BigDecimal("100"), new BigDecimal("100000"), true)),
            List.of(new TermBand(60, 90), new TermBand(120, 180)),
            List.of(new Cell(0, 0, BigDecimal.ONE), new Cell(0, 1, BigDecimal.TEN)));
    validation.validate(m, p, InvestmentProductFixtures.nativeProduct());
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.validate(
                new Matrix(
                    "MXN",
                    m.amountBands(),
                    List.of(new TermBand(60, 180), new TermBand(181, 1825)),
                    m.cells()),
                p,
                InvestmentProductFixtures.nativeProduct()));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> new InvestmentProductValidation().validateTerm(p, 61));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> new InvestmentProductValidation().validateTerm(p, 30));
  }

  @Test
  void boundsPayloadsAndRejectsUnknownOrCoercedFields() {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> valid(BASE.replace("\"upperInclusive\":false", "\"upperInclusive\":\"false\"")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> valid(BASE.replace("\"matrix\":{", "\"matrix\":{\"unknown\":1,")));
    assertThrows(
        PlatformApiDataValidationException.class, () -> valid(BASE.replace("\"MXN\"", "\"USD\"")));
    assertThrows(
        PlatformApiDataValidationException.class, () -> validation.parse(" ".repeat(400001)));
    var root = com.google.gson.JsonParser.parseString(BASE).getAsJsonObject();
    var cells = root.getAsJsonObject("matrix").getAsJsonArray("cells");
    while (cells.size() <= 2500) cells.add(cells.get(0).deepCopy());
    assertThrows(PlatformApiDataValidationException.class, () -> valid(root.toString()));
  }

  @Test
  void campaignsAreTypedAndRetainedWithExplicitPublicationBlockers() {
    for (var type : CampaignType.values()) {
      var request =
          validation.parse(
              edit(
                  root ->
                      root.add(
                          "campaignMetadata",
                          com.google.gson.JsonParser.parseString(
                              "{\"campaignId\":\"synthetic\",\"name\":\"Test offer\",\"type\":\""
                                  + type
                                  + "\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-31\"}"))));
      assertFalse(InvestmentRateValidation.campaignBlockers(request.campaignMetadata()).isEmpty());
    }
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validation.parse(BASE.replace("\"IMMEDIATE\"", "\"UNAPPROVED\"")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validation.parse(request(0, "2026-02-30", "8")));
  }
}
