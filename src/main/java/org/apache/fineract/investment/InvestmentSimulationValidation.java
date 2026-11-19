/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentProductValidation.invalid;
import static org.apache.fineract.investment.InvestmentSimulationData.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.springframework.stereotype.Component;

/** Strict bounded wire input, followed by reuse of BE-01 eligibility rules. */
@Component
public class InvestmentSimulationValidation {
  public Request parse(String json) {
    if (json == null || json.length() > 8000)
      throw invalid("request", "A JSON object of at most 8000 characters is required.");
    try {
      try (var reader = new JsonReader(new StringReader(json))) {
        reader.setStrictness(Strictness.STRICT);
        scan(reader, 0);
        if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("request", "Trailing JSON.");
      }
      var root = JsonParser.parseString(json).getAsJsonObject();
      fields(
          root,
          Set.of(
              "productId",
              "investmentAmount",
              "termDays",
              "startDate",
              "capitalizationEnabled",
              "interestPayoutMode",
              "automaticReinvestment",
              "requestedReinvestmentCount",
              "fundingContext",
              "requestKey"));
      long product = integer(root, "productId", Long.MAX_VALUE);
      int days = (int) integer(root, "termDays", MAX_HORIZON_DAYS);
      int renewals = (int) integer(root, "requestedReinvestmentCount", MAX_REINVESTMENTS);
      var amount = root.get("investmentAmount");
      if (amount == null
          || !amount.isJsonPrimitive()
          || !amount.getAsJsonPrimitive().isNumber()
          || !amount.getAsString().matches("[0-9]{1,13}(\\.[0-9]{1,6})?"))
        throw invalid("investmentAmount", "An exact positive plain JSON decimal is required.");
      BigDecimal principal = amount.getAsBigDecimal().stripTrailingZeros();
      if (product < 1) throw invalid("productId", "A positive product ID is required.");
      if (principal.signum() <= 0) throw invalid("investmentAmount", "Principal must be positive.");
      if (days < 1) throw invalid("termDays", "A positive term is required.");
      if ((long) days * (renewals + 1) > MAX_HORIZON_DAYS)
        throw invalid("termDays", "Total projection horizon exceeds 3650 days.");
      String start = string(root, "startDate");
      var date = InvestmentRateTime.date(start, "startDate");
      if (date.getYear() < 1900 || date.plusDays((long) days * (renewals + 1)).getYear() > 9999)
        throw invalid("startDate", "Projection dates must be within 1900–9999.");
      boolean cap = bool(root, "capitalizationEnabled"), auto = bool(root, "automaticReinvestment");
      if (!auto && renewals != 0)
        throw invalid("requestedReinvestmentCount", "Renewals require automaticReinvestment.");
      var payout =
          enumeration(root, "interestPayoutMode", InvestmentProductData.InterestPayoutMode.class);
      FundingMode funding = FundingMode.QUOTE_ONLY;
      if (root.has("fundingContext") && !root.get("fundingContext").isJsonNull()) {
        var object = root.getAsJsonObject("fundingContext");
        fields(object, Set.of("mode"));
        funding = enumeration(object, "mode", FundingMode.class);
      }
      String key =
          root.has("requestKey") && !root.get("requestKey").isJsonNull()
              ? string(root, "requestKey")
              : null;
      if (key != null && !key.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))
        throw invalid("requestKey", "A bounded ASCII request identifier is required.");
      return new Request(
          product,
          principal,
          days,
          start,
          cap,
          payout,
          auto,
          renewals,
          new FundingContext(funding),
          key);
    } catch (PlatformApiDataValidationException e) {
      throw e;
    } catch (RuntimeException | java.io.IOException e) {
      throw invalid("request", "Malformed JSON, enum or field type.");
    }
  }

  void eligible(
      Request request,
      InvestmentProductData.Policy policy,
      InvestmentProductData.NativeProduct product) {
    var validation = new InvestmentProductValidation();
    validation.validateAgainstNative(policy, product);
    validation.validateTerm(policy, request.termDays());
    amount(request.investmentAmount(), policy, product);
    if (request.interestPayoutMode() != policy.interestPayoutMode())
      throw invalid("interestPayoutMode", "The requested payout must match the product policy.");
    if (request.capitalizationEnabled() != policy.capitalizationEnabled())
      throw invalid(
          "capitalizationEnabled", "Renewal capitalization must match the product policy.");
    if (request.automaticReinvestment() && !policy.automaticReinvestmentEnabled())
      throw invalid("automaticReinvestment", "Product reinvestment is disabled.");
    Integer maximum = policy.maximumReinvestments();
    if (policy.maximumReinvestmentsByTerm() != null)
      for (var limit : policy.maximumReinvestmentsByTerm())
        if (limit.termDays() == request.termDays()) maximum = limit.maximumReinvestments();
    if (request.automaticReinvestment()
        && (maximum == null || request.requestedReinvestmentCount() > maximum))
      throw invalid(
          "requestedReinvestmentCount", "Requested renewals exceed the configured term limit.");
  }

  static void amount(
      BigDecimal amount,
      InvestmentProductData.Policy policy,
      InvestmentProductData.NativeProduct product) {
    InvestmentRateValidation.money(amount, product, "investmentAmount");
    BigDecimal min =
        policy.minimumInvestmentAmount() == null
            ? product.minimumDepositAmount()
            : policy.minimumInvestmentAmount();
    BigDecimal max =
        policy.maximumInvestmentAmount() == null
            ? product.maximumDepositAmount()
            : policy.maximumInvestmentAmount();
    if (amount.signum() <= 0
        || (min != null && amount.compareTo(min) < 0)
        || (max != null && amount.compareTo(max) > 0))
      throw invalid(
          "investmentAmount", "Principal must satisfy the effective product amount bounds.");
  }

  private static void scan(JsonReader reader, int depth) throws java.io.IOException {
    if (depth > 2) throw invalid("request", "Excessive JSON nesting.");
    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
      reader.beginObject();
      var keys = new HashSet<String>();
      while (reader.hasNext()) {
        if (!keys.add(reader.nextName())) throw invalid("request", "Duplicate JSON field.");
        scan(reader, depth + 1);
      }
      reader.endObject();
    } else if (reader.peek() == JsonToken.BEGIN_ARRAY)
      throw invalid("request", "Arrays are unsupported.");
    else reader.skipValue();
  }

  private static void fields(JsonObject object, Set<String> fields) {
    if (object == null) throw invalid("request", "A JSON object is required.");
    for (String field : object.keySet())
      if (!fields.contains(field)) throw invalid(field, "Unknown field.");
  }

  private static long integer(JsonObject root, String name, long maximum) {
    var value = root.get(name);
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isNumber()
        || !value.getAsString().matches("[0-9]{1,19}")
        || value.getAsBigDecimal().compareTo(BigDecimal.valueOf(maximum)) > 0)
      throw invalid(name, "A nonnegative bounded JSON integer is required.");
    return value.getAsLong();
  }

  private static boolean bool(JsonObject root, String name) {
    var value = root.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
      throw invalid(name, "A JSON boolean is required.");
    return value.getAsBoolean();
  }

  private static String string(JsonObject root, String name) {
    var value = root.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw invalid(name, "A JSON string is required.");
    return value.getAsString();
  }

  private static <T extends Enum<T>> T enumeration(JsonObject root, String name, Class<T> type) {
    try {
      return Enum.valueOf(type, string(root, name));
    } catch (IllegalArgumentException e) {
      throw invalid(name, "Unsupported enum value.");
    }
  }
}
