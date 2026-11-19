/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentSimulationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InvestmentSimulationValidationTest {
  final InvestmentSimulationValidation validation = new InvestmentSimulationValidation();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{",
        "[]",
        "null",
        "true",
        "{\"productId\":1,\"productId\":2}",
        "{\"productId\":1} trailing",
        "{\"requestKey\":\"secret\",\"annualRatePercent\":8}"
      })
  void malformedUnknownAndDuplicateFieldsAreRejected(String json) {
    assertThrows(PlatformApiDataValidationException.class, () -> validation.parse(json));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"0", "-1", "1e4", "\"1000\"", "true", "null", "100.0000001", "10000000000000"})
  void principalWireFormatsMustBeExactPositiveBoundedDecimals(String amount) {
    String request = request(1, null).replace("10000.00", amount);
    assertThrows(PlatformApiDataValidationException.class, () -> validation.parse(request));
  }

  @Test
  void canonicalInputNormalizesKeyOrderAmountsAndOptionalQuoteContext() {
    var original = validation.parse(request(1, "same"));
    var alternate = validation.parse(request(1, "same").replace("10000.00", "10000"));
    assertEquals(original, alternate);
    assertEquals(InvestmentSimulationData.FundingMode.QUOTE_ONLY, original.fundingContext().mode());
    var fund =
        edit(
            1,
            null,
            r ->
                r.add(
                    "fundingContext",
                    com.google.gson.JsonParser.parseString(
                        "{\"mode\":\"QUOTE_ONLY\",\"balance\":999999}")));
    assertThrows(PlatformApiDataValidationException.class, () -> validation.parse(fund));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 1825})
  void promissoryNotePolicyTermExtremesAreAccepted(int days) {
    var request = validation.parse(edit(1, null, r -> r.addProperty("termDays", days)));
    assertDoesNotThrow(
        () ->
            validation.eligible(
                request, policy(Map.of()), InvestmentProductFixtures.nativeProduct()));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 31, 59, 61, 181})
  void cedeIncrementsComeFromExistingProductValidation(int days) {
    var cede =
        policy(
            Map.of(
                "investmentType",
                "CEDE",
                "minimumTermDays",
                60,
                "maximumTermDays",
                180,
                "termMultipleDays",
                30));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.eligible(
                validation.parse(edit(1, null, r -> r.addProperty("termDays", days))),
                cede,
                InvestmentProductFixtures.nativeProduct()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"99", "100000.01", "100.001"})
  void effectiveAmountBoundsAndCurrencyPrecisionAreEnforced(String principal) {
    var request =
        validation.parse(
            edit(
                1,
                null,
                r -> r.addProperty("investmentAmount", new java.math.BigDecimal(principal))));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.eligible(
                request, policy(Map.of()), InvestmentProductFixtures.nativeProduct()));
  }

  @Test
  void payoutCapitalizationRenewalLimitsAndHorizonAreEnforced() {
    var product = InvestmentProductFixtures.nativeProduct();
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.eligible(
                validation.parse(
                    edit(1, null, r -> r.addProperty("interestPayoutMode", "MONTHLY"))),
                policy(Map.of()),
                product));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.eligible(
                validation.parse(edit(1, null, r -> r.addProperty("capitalizationEnabled", true))),
                policy(Map.of()),
                product));
    var renewals =
        policy(
            Map.of(
                "automaticReinvestmentEnabled",
                true,
                "maximumReinvestments",
                3,
                "maximumReinvestmentsByTerm",
                java.util.List.of(Map.of("termDays", 90, "maximumReinvestments", 1)),
                "renewalRatePolicy",
                "RATE_EFFECTIVE_AT_RENEWAL"));
    var one =
        validation.parse(
            edit(
                1,
                null,
                r -> {
                  r.addProperty("automaticReinvestment", true);
                  r.addProperty("requestedReinvestmentCount", 1);
                }));
    assertDoesNotThrow(() -> validation.eligible(one, renewals, product));
    var two =
        validation.parse(
            edit(
                1,
                null,
                r -> {
                  r.addProperty("automaticReinvestment", true);
                  r.addProperty("requestedReinvestmentCount", 2);
                }));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validation.eligible(two, renewals, product));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.parse(
                edit(
                    1,
                    null,
                    r -> {
                      r.addProperty("automaticReinvestment", true);
                      r.addProperty("termDays", 1825);
                      r.addProperty("requestedReinvestmentCount", 2);
                    })));
  }
}
