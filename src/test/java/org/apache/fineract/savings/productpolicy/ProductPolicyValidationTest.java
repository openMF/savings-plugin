/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductPolicyValidationTest {
  static final String JSON =
      """
      {"expectedRevision":0,"policy":{
        "classification":"SIGHT","purpose":"MAIN","withdrawalsAllowed":true,
        "depositsAllowed":true,"liquidGuarantee":false,"maximumBalance":55000000,
        "minimumMonthlyAverageBalance":0,"feeApplicable":false,"freezing":"NONE",
        "directDebitForLoanPayment":false,"multiplicity":"SINGLE",
        "accountDesignation":"Main Account","additionalPaymentReference":false,
        "interestMode":"NONE"}}
      """;
  final ProductPolicyValidation validator = new ProductPolicyValidation();

  @ParameterizedTest
  @ValueSource(strings = {"SIGHT", "INTEREST_BEARING_SIGHT", "SAVINGS"})
  void classificationsAndZeroOpeningAreValid(String classification) {
    var body = body();
    body.getAsJsonObject("policy").addProperty("classification", classification);
    var p = validator.parse(body.toString()).policy();
    assertDoesNotThrow(
        () -> validator.validateProduct(p, "MXN", 2, BigDecimal.ZERO, BigDecimal.ZERO));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "classification:UNSUPPORTED",
        "classification:null",
        "purpose:LIQUID_GUARANTEE",
        "interestMode:FIXED",
        "interestMode:TIERED",
        "maximumBalance:-1",
        "minimumMonthlyAverageBalance:-0.01",
        "maximumBalance:0.0000001",
        "minimumMonthlyAverageBalance:55000001",
        "multiplicity:UNLIMITED",
        "freezing:TERM",
        "freezing:LOAN_RELEASE",
        "liquidGuarantee:true",
        "feeApplicable:true",
        "feeChargeId:1",
        "feeChargeId:-1",
        "freezingTermDays:5",
        "freezingTermDays:4294967297",
        "accountDesignation:",
        "additionalPaymentReference:true",
        "paymentReferencePartner:Partner",
        "depositsAllowed:null",
        "operational:true",
        "maximumBalance:10000000000000"
      })
  void invalidCombinationsAreRejected(String change) {
    var body = body();
    String[] parts = change.split(":", -1);
    String field = parts[0], value = parts[1];
    JsonObject policy = body.getAsJsonObject("policy");
    if (value.equals("null")) policy.remove(field);
    else if (value.matches("-?[0-9.]+|true|false"))
      policy.add(field, JsonParser.parseString(value));
    else policy.addProperty(field, value);
    assertThrows(PlatformApiDataValidationException.class, () -> validator.parse(body.toString()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{",
        "{}",
        "[]",
        "null",
        "{expectedRevision:0}",
        "{\"expectedRevision\":0,\"expectedRevision\":0}",
        "{\"expectedRevision\":\"0\"}",
        "{\"expectedRevision\":-1}",
        "{\"expectedRevision\":0.1}"
      })
  void malformedAndMistypedJsonRejected(String json) {
    assertThrows(PlatformApiDataValidationException.class, () -> validator.parse(json));
  }

  @Test
  void strictTypesUnknownFieldsDuplicateKeysAndTrailingContentRejected() {
    for (String json :
        new String[] {
          JSON + "{}",
          JSON.replace("55000000", "\"55000000\""),
          JSON.replace("\"depositsAllowed\":true", "\"depositsAllowed\":\"true\""),
          JSON.replace("\"maximumBalance\":55000000", "\"maximumBalance\":1,\"maximumBalance\":2"),
          JSON.replace("\"expectedRevision\":0", "\"activate\":true,\"expectedRevision\":0"),
          JSON.replace("\"feeApplicable\":false", "\"feeApplicable\":null"),
          JSON.replace("\"purpose\":\"MAIN\"", "\"purpose\":42")
        })
      assertThrows(PlatformApiDataValidationException.class, () -> validator.parse(json), json);
  }

  @Test
  void validatesCoreCurrencyPrecisionOpeningAndInterestWithoutChangingThem() {
    var p = validator.parse(JSON).policy();
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validateProduct(p, "USD", 2, BigDecimal.ZERO, BigDecimal.ZERO));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validateProduct(p, "MXN", 2, new BigDecimal("55000001"), BigDecimal.ZERO));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validateProduct(p, "MXN", 2, BigDecimal.ZERO, BigDecimal.ONE));
    var body = body();
    body.getAsJsonObject("policy").addProperty("maximumBalance", new BigDecimal("1.001"));
    var fractional = validator.parse(body.toString()).policy();
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validateProduct(fractional, "MXN", 2, BigDecimal.ZERO, BigDecimal.ZERO));
  }

  @Test
  void extremeExponentCannotOverflowMonetaryDigitLimit() {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.parse(JSON.replace("55000000", "1e2147483647")));
  }

  @Test
  void zeroMaximumOpeningAndAverageRemainValid() {
    var policy = validator.parse(JSON.replace("55000000", "0")).policy();
    assertDoesNotThrow(
        () -> validator.validateProduct(policy, "MXN", 2, BigDecimal.ZERO, BigDecimal.ZERO));
  }

  @Test
  void approvedPurposeShapesAndFutureInterestAreDraftOnly() {
    for (String purpose : new String[] {"GUARANTEED", "PLANNED_MAIN", "POCKET"}) {
      var body = body();
      var p = body.getAsJsonObject("policy");
      p.addProperty("purpose", purpose);
      p.addProperty("liquidGuarantee", purpose.equals("GUARANTEED"));
      p.addProperty("multiplicity", purpose.equals("POCKET") ? "UNLIMITED" : "SINGLE");
      p.addProperty("freezing", "TERM");
      p.addProperty("freezingTermDays", 30);
      p.addProperty("classification", "SAVINGS");
      p.addProperty("interestMode", "REFERENCE_RATE");
      assertNotNull(validator.parse(body.toString()));
    }
    assertFalse(ProductPolicyData.capabilities().activation());
    assertFalse(ProductPolicyData.capabilities().financialEnforcement());
  }

  static JsonObject body() {
    return JsonParser.parseString(JSON).getAsJsonObject();
  }
}
