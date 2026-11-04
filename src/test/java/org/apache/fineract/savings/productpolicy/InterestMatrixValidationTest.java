/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.InterestMatrixCandidates.*;
import static org.apache.fineract.savings.productpolicy.InterestMatrixData.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InterestMatrixValidationTest {
  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  static final String MATRIX =
      """
      {"calculationMethod":"UNAPPROVED","brackets":[
        {"lowerBalance":0,"upperBalance":1000,"annualRatePercent":0},
        {"lowerBalance":1000.01,"upperBalance":10000,"annualRatePercent":6}]}
      """;

  static Matrix matrix(String json) {
    return InterestMatrixValidation.parse(ProductPolicyValidation.object(json));
  }

  static void valid(String json) {
    InterestMatrixValidation.validate(matrix(json), 2, d("10000"));
  }

  static String policy(long revision, String matrix) {
    var body = ProductPolicyValidation.object(ProductPolicyValidationTest.JSON);
    body.addProperty("expectedRevision", revision);
    var p = body.getAsJsonObject("policy");
    p.addProperty("classification", "SAVINGS");
    p.addProperty("interestMode", "TIERED");
    p.addProperty("maximumBalance", 10000);
    if (matrix != null) p.add("interestMatrix", ProductPolicyValidation.object(matrix));
    return body.toString();
  }

  @Test
  void policyParsingRetainsExactDecimalsAndBackwardCompatibility() {
    var validation = new ProductPolicyValidation();
    var p = validation.parse(policy(0, MATRIX)).policy();
    validation.validateProduct(p, "MXN", 2, BigDecimal.ZERO, BigDecimal.ZERO);
    assertEquals(d("1000.01"), p.interestMatrix().brackets().get(1).lowerBalance());
    assertNull(validation.parse(ProductPolicyValidationTest.JSON).policy().interestMatrix());
    assertFalse(ProductPolicyData.capabilities().financialEnforcement());
    assertTrue(ProductPolicyData.capabilities().pendingFeatures().contains("INTEREST_CALCULATION"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "-1",
        "0.001",
        "10000000000000",
        "null",
        "\"1\"",
        "1e2",
        "NaN",
        "true",
        "{}",
        "[]"
      })
  void invalidLowerValues(String value) {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> valid(MATRIX.replace("\"lowerBalance\":0", "\"lowerBalance\":" + value)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "0.0000001", "null", "\"6\"", "1e1000000", "true"})
  void invalidRates(String value) {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> valid(MATRIX.replace("\"annualRatePercent\":6", "\"annualRatePercent\":" + value)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1000", "999.99", "1000.02", "0", "10001"})
  void overlapsDuplicatesOrderingAndGaps(String lower) {
    assertThrows(
        PlatformApiDataValidationException.class, () -> valid(MATRIX.replace("1000.01", lower)));
  }

  @Test
  void rejectsMalformedMissingUnknownDuplicateAndUnapprovedMethods() {
    for (String s :
        List.of(
            MATRIX.replace("10000", "9999"),
            MATRIX.replace("10000", "999"),
            MATRIX.replace("10000", "10001"),
            MATRIX.replace("UNAPPROVED", "WHOLE_BALANCE"),
            MATRIX.replace("\"upperBalance\":1000,", ""),
            MATRIX.replace("\"upperBalance\":1000", "\"upperBalance\":null"),
            MATRIX.replace("\"lowerBalance\":0", "\"lowerBalance\":0,\"lowerBalance\":0"),
            MATRIX.replace("\"lowerBalance\":0", "\"lowerBalance\":0,\"priority\":1"),
            "{\"calculationMethod\":\"UNAPPROVED\",\"brackets\":[]}"))
      assertThrows(PlatformApiDataValidationException.class, () -> valid(s), s);
  }

  @Test
  void currencyPrecisionDeterminesSuccessorWithoutEpsilon() {
    var m = matrix(MATRIX.replace("1000.01", "1000.001"));
    InterestMatrixValidation.validate(m, 3, d("10000"));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> InterestMatrixValidation.validate(m, 2, d("10000")));
    InterestMatrixValidation.validate(matrix(MATRIX.replace("1000.01", "1001")), 0, d("10000"));
    InterestMatrixValidation.validate(
        matrix(MATRIX.replace("1000.01", "1000.000001")), 6, d("10000"));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> InterestMatrixValidation.validate(m, 7, d("10000")));
  }

  @Test
  void threeSlabsZeroRateAndUnboundedFinalAreSupported() {
    String json =
        MATRIX.replace(
            "{\"lowerBalance\":1000.01,\"upperBalance\":10000,\"annualRatePercent\":6}",
            "{\"lowerBalance\":1000.01,\"upperBalance\":5000,\"annualRatePercent\":6},"
                + "{\"lowerBalance\":5000.01,\"upperBalance\":null,\"annualRatePercent\":7.125}");
    valid(json);
    var m = matrix(json);
    assertEquals(3, m.brackets().size());
    assertNull(m.brackets().getLast().upperBalance());
    assertEquals(
        0,
        d("596.25").compareTo(annualSimpleAmount(m, 2, d("10000"), d("10000"), Strategy.MARGINAL)));
  }

  @Test
  void incompatiblePoliciesCannotHideMatrixInParentPut() {
    var validation = new ProductPolicyValidation();
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validation.parse(policy(0, MATRIX).replace("TIERED", "FIXED")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validation.parse(policy(0, MATRIX).replace("SAVINGS", "SIGHT")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.validateProduct(
                validation.parse(policy(0, MATRIX)).policy(), "USD", 2, d("0"), d("0")));
  }

  @Test
  void immutableListsAndBoundedPayloads() {
    var brackets = new ArrayList<>(matrix(MATRIX).brackets());
    var m = new Matrix(CalculationMethod.UNAPPROVED, brackets);
    brackets.clear();
    assertEquals(2, m.brackets().size());
    assertThrows(UnsupportedOperationException.class, () -> m.brackets().clear());
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> InterestMatrixValidation.validate(new Matrix(null, m.brackets()), 2, d("10000")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            InterestMatrixValidation.validate(
                new Matrix(
                    CalculationMethod.UNAPPROVED, Collections.nCopies(51, m.brackets().getFirst())),
                2,
                d("10000")));
  }

  @Test
  void unapprovedCandidateStrategiesDifferExactlyAndNeverDrift() {
    var m = matrix(MATRIX);
    assertEquals(
        0,
        d("120.00")
            .compareTo(annualSimpleAmount(m, 2, d("10000"), d("2000"), Strategy.WHOLE_BALANCE)));
    assertEquals(
        0,
        d("60.00").compareTo(annualSimpleAmount(m, 2, d("10000"), d("2000"), Strategy.MARGINAL)));
    assertEquals(
        0,
        d("0.0006")
            .compareTo(annualSimpleAmount(m, 2, d("10000"), d("1000.01"), Strategy.MARGINAL)));
    for (var strategy : Strategy.values()) {
      assertEquals(
          0,
          BigDecimal.ZERO.compareTo(
              annualSimpleAmount(m, 2, d("10000"), BigDecimal.ZERO, strategy)));
      assertEquals(
          0, BigDecimal.ZERO.compareTo(annualSimpleAmount(m, 2, d("10000"), d("1000"), strategy)));
      var sum = BigDecimal.ZERO;
      for (int i = 0; i < 1000; i++)
        sum = sum.add(annualSimpleAmount(m, 2, d("10000"), d("1000.01"), strategy));
      assertEquals(
          0, d(strategy == Strategy.WHOLE_BALANCE ? "60000.6000" : "0.6000").compareTo(sum));
      assertThrows(
          PlatformApiDataValidationException.class,
          () -> annualSimpleAmount(m, 2, d("10000"), d("10000.01"), strategy));
    }
  }
}
