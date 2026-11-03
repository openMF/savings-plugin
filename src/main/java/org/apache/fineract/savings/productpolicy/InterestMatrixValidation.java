/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;

import com.google.gson.JsonElement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Set;

/** Exact validation of a deliberately restricted, continuous configuration representation. */
public final class InterestMatrixValidation {
  public static final int MAX_BRACKETS = 50;

  private InterestMatrixValidation() {}

  static InterestMatrixData.Matrix parse(JsonElement value) {
    if (value == null || !value.isJsonObject())
      throw invalid("interestMatrix", "Matrix object required.");
    var object = value.getAsJsonObject();
    ProductPolicyValidation.fields(object, Set.of("calculationMethod", "brackets"));
    var method = object.get("calculationMethod");
    if (method == null
        || !method.isJsonPrimitive()
        || !method.getAsJsonPrimitive().isString()
        || !method.getAsString().equals("UNAPPROVED"))
      throw invalid(
          "calculationMethod",
          "Only UNAPPROVED metadata is supported; financial semantics require business approval.");
    var rows = object.get("brackets");
    if (rows == null
        || !rows.isJsonArray()
        || rows.getAsJsonArray().isEmpty()
        || rows.getAsJsonArray().size() > MAX_BRACKETS)
      throw invalid("brackets", "Between 1 and 50 brackets are required.");
    var brackets = new ArrayList<InterestMatrixData.Bracket>();
    for (var row : rows.getAsJsonArray()) {
      if (!row.isJsonObject()) throw invalid("brackets", "Bracket object required.");
      var b = row.getAsJsonObject();
      ProductPolicyValidation.fields(
          b, Set.of("lowerBalance", "upperBalance", "annualRatePercent"));
      if (!b.has("upperBalance"))
        throw invalid("upperBalance", "Explicit amount or null required.");
      brackets.add(
          new InterestMatrixData.Bracket(
              decimal(b.get("lowerBalance"), "lowerBalance"),
              b.get("upperBalance").isJsonNull()
                  ? null
                  : decimal(b.get("upperBalance"), "upperBalance"),
              decimal(b.get("annualRatePercent"), "annualRatePercent")));
    }
    return new InterestMatrixData.Matrix(InterestMatrixData.CalculationMethod.UNAPPROVED, brackets);
  }

  private static BigDecimal decimal(JsonElement e, String field) {
    if (e == null
        || !e.isJsonPrimitive()
        || !e.getAsJsonPrimitive().isNumber()
        || !e.getAsString().matches("-?(0|[1-9][0-9]{0,12})(\\.[0-9]{1,12})?"))
      throw invalid(
          field, "A bounded plain JSON decimal number is required (no exponent or string).");
    return e.getAsBigDecimal();
  }

  public static void validate(InterestMatrixData.Matrix matrix, int digits, BigDecimal maximum) {
    if (digits < 0 || digits > 6)
      throw invalid("currencyDigits", "Supported currency precision is 0 through 6.");
    if (matrix == null
        || matrix.calculationMethod() != InterestMatrixData.CalculationMethod.UNAPPROVED)
      throw invalid("calculationMethod", "Unapproved matrix metadata required.");
    if (matrix.brackets() == null
        || matrix.brackets().isEmpty()
        || matrix.brackets().size() > MAX_BRACKETS)
      throw invalid("brackets", "Between 1 and 50 brackets are required.");
    amount(maximum, digits, "maximumBalance");
    BigDecimal next = BigDecimal.ZERO;
    BigDecimal unit = BigDecimal.ONE.movePointLeft(digits);
    for (int i = 0; i < matrix.brackets().size(); i++) {
      var b = matrix.brackets().get(i);
      if (b == null) throw invalid("brackets", "Null brackets are unsupported.");
      amount(b.lowerBalance(), digits, "lowerBalance");
      amount(b.annualRatePercent(), 6, "annualRatePercent");
      if (next == null || b.lowerBalance().compareTo(next) != 0)
        throw invalid(
            "lowerBalance",
            "Ordered inclusive ranges must cover zero continuously without overlaps or gaps.");
      if (b.lowerBalance().compareTo(maximum) > 0)
        throw invalid("lowerBalance", "Bracket starts beyond the policy maximum balance.");
      if (b.upperBalance() == null) {
        if (i != matrix.brackets().size() - 1)
          throw invalid("upperBalance", "Only the final bracket may be unbounded.");
        next = null;
      } else {
        amount(b.upperBalance(), digits, "upperBalance");
        if (b.lowerBalance().compareTo(b.upperBalance()) > 0)
          throw invalid("upperBalance", "Upper balance must not be below lower balance.");
        if (b.upperBalance().compareTo(maximum) > 0)
          throw invalid(
              "upperBalance", "Finite coverage must not exceed the policy maximum balance.");
        next = b.upperBalance().add(unit);
      }
    }
    var last = matrix.brackets().getLast();
    if (last.upperBalance() != null && last.upperBalance().compareTo(maximum) != 0)
      throw invalid("upperBalance", "Final finite bracket must end at the policy maximum balance.");
  }

  static void amount(BigDecimal value, int digits, String field) {
    if (value == null
        || value.signum() < 0
        || value.stripTrailingZeros().scale() > digits
        || (long) value.precision() - value.scale() > 13)
      throw invalid(
          field,
          "A nonnegative decimal within currency/rate precision and 13 integer digits is"
              + " required.");
  }
}
