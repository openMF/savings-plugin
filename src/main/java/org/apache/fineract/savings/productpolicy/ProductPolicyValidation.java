/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyData.*;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.springframework.stereotype.Component;

/** Strict, shared parsing and business validation, independent of the HTTP JSON provider. */
@Component
public class ProductPolicyValidation {
  private static final Gson GSON = new Gson();
  private static final Set<String> OPTIONAL =
      Set.of("feeChargeId", "freezingTermDays", "paymentReferencePartner");

  public WriteRequest parse(String json) {
    if (json == null || json.length() > 16000)
      throw invalid("request", "A bounded JSON object is required.");
    try {
      // Reject duplicate keys and trailing content as well as Gson's otherwise lenient syntax.
      try (var reader = new JsonReader(new StringReader(json))) {
        reader.setStrictness(Strictness.STRICT);
        scan(reader, 0);
        if (reader.peek() != JsonToken.END_DOCUMENT)
          throw invalid("request", "Trailing JSON content.");
      }
      JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
      fields(root, Set.of("expectedRevision", "policy"));
      integer(root.get("expectedRevision"), "expectedRevision");
      JsonObject policy = root.getAsJsonObject("policy");
      Set<String> names = new HashSet<>();
      for (var field : Policy.class.getRecordComponents()) names.add(field.getName());
      fields(policy, names);
      for (var field : Policy.class.getRecordComponents()) {
        String name = field.getName();
        JsonElement value = policy.get(name);
        if (value == null || value.isJsonNull()) {
          if (!OPTIONAL.contains(name)) throw invalid(name, "Required configuration is missing.");
          continue;
        }
        if (!value.isJsonPrimitive()) throw invalid(name, "A scalar value is required.");
        var scalar = value.getAsJsonPrimitive();
        if (field.getType() == Boolean.class && !scalar.isBoolean())
          throw invalid(name, "A JSON boolean is required.");
        if ((field.getType().isEnum() || field.getType() == String.class) && !scalar.isString())
          throw invalid(name, "A JSON string is required.");
        if (field.getType() == Long.class || field.getType() == Integer.class) integer(value, name);
        if (field.getType() == Integer.class
            && value.getAsBigDecimal().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0)
          throw invalid(name, "Integer exceeds supported range.");
        if (field.getType() == BigDecimal.class && !scalar.isNumber())
          throw invalid(name, "A JSON number is required.");
      }
      WriteRequest request = GSON.fromJson(root, WriteRequest.class);
      if (request.expectedRevision() < 0 || request.expectedRevision() == Long.MAX_VALUE)
        throw invalid("expectedRevision", "Revision must be a non-negative supported integer.");
      validate(request.policy());
      return request;
    } catch (PlatformApiDataValidationException e) {
      throw e;
    } catch (Exception e) {
      throw invalid("request", "Malformed JSON, unsupported enum, or invalid field type.");
    }
  }

  private static void scan(JsonReader reader, int depth) throws java.io.IOException {
    if (depth > 3) throw invalid("request", "JSON nesting is unsupported.");
    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
      reader.beginObject();
      Set<String> keys = new HashSet<>();
      while (reader.hasNext()) {
        if (!keys.add(reader.nextName())) throw invalid("request", "Duplicate JSON key.");
        scan(reader, depth + 1);
      }
      reader.endObject();
    } else if (reader.peek() == JsonToken.BEGIN_ARRAY) {
      throw invalid("request", "Arrays are unsupported.");
    } else {
      reader.skipValue();
    }
  }

  private static void fields(JsonObject object, Set<String> supported) {
    if (object == null) throw invalid("request", "A JSON object is required.");
    for (String key : object.keySet())
      if (!supported.contains(key)) throw invalid(key, "Unsupported configuration option.");
  }

  private static void integer(JsonElement value, String name) {
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isNumber()
        || !value.getAsString().matches("[0-9]{1,18}"))
      throw invalid(name, "A non-negative JSON integer of at most 18 digits is required.");
  }

  public void validate(Policy p) {
    if (p == null) throw invalid("policy", "Policy is required.");
    for (var field : Policy.class.getRecordComponents()) {
      try {
        if (!OPTIONAL.contains(field.getName()) && field.getAccessor().invoke(p) == null)
          throw invalid(field.getName(), "Required configuration is missing.");
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }
    money(p.maximumBalance(), "maximumBalance", 6);
    money(p.minimumMonthlyAverageBalance(), "minimumMonthlyAverageBalance", 6);
    if (p.classification() == Classification.SIGHT && p.interestMode() != InterestMode.NONE)
      throw invalid("interestMode", "SIGHT cannot generate interest.");
    if (p.minimumMonthlyAverageBalance().compareTo(p.maximumBalance()) > 0)
      throw invalid(
          "minimumMonthlyAverageBalance", "Monthly average cannot exceed maximum balance.");
    if (p.feeApplicable() != (p.feeChargeId() != null))
      throw invalid(
          "feeChargeId", "Fee applicability requires exactly one supported charge reference.");
    if (p.feeChargeId() != null && p.feeChargeId() <= 0)
      throw invalid("feeChargeId", "A positive charge ID is required.");
    if ((p.freezing() == Freezing.TERM) != (p.freezingTermDays() != null)
        || (p.freezingTermDays() != null && p.freezingTermDays() <= 0))
      throw invalid("freezingTermDays", "Only TERM freezing requires a positive number of days.");
    if (p.purpose() == Purpose.MAIN && p.freezing() != Freezing.NONE)
      throw invalid(
          "freezing",
          "Resource freezing is specified only for Guaranteed, Planned Main and Pocket purposes.");
    if (p.freezing() == Freezing.LOAN_RELEASE && p.purpose() != Purpose.GUARANTEED)
      throw invalid("freezing", "LOAN_RELEASE requires GUARANTEED purpose.");
    if (p.liquidGuarantee() != (p.purpose() == Purpose.GUARANTEED))
      throw invalid("liquidGuarantee", "Liquid guarantee designation requires GUARANTEED purpose.");
    if ((p.purpose() == Purpose.MAIN || p.purpose() == Purpose.GUARANTEED)
        && p.multiplicity() != Multiplicity.SINGLE)
      throw invalid("multiplicity", "Main and Guaranteed purposes require SINGLE.");
    if (p.purpose() == Purpose.POCKET && p.multiplicity() != Multiplicity.UNLIMITED)
      throw invalid("multiplicity", "Pockets require UNLIMITED.");
    text(p.accountDesignation(), "accountDesignation", 100);
    if (p.additionalPaymentReference())
      text(p.paymentReferencePartner(), "paymentReferencePartner", 100);
    else if (p.paymentReferencePartner() != null)
      throw invalid("paymentReferencePartner", "Partner requires additionalPaymentReference.");
  }

  public void validateProduct(
      Policy policy, String currency, int digits, BigDecimal opening, BigDecimal rate) {
    validate(policy);
    if (!"MXN".equals(currency))
      throw invalid("savingsProductId", "WEB-1350 requires an MXN product.");
    money(policy.maximumBalance(), "maximumBalance", digits);
    money(policy.minimumMonthlyAverageBalance(), "minimumMonthlyAverageBalance", digits);
    if (opening != null && (opening.signum() < 0 || policy.maximumBalance().compareTo(opening) < 0))
      throw invalid(
          "maximumBalance", "Maximum balance must cover the core minimum opening balance.");
    if (policy.interestMode() == InterestMode.NONE && rate != null && rate.signum() != 0)
      throw invalid(
          "interestMode",
          "NONE requires zero nominal interest in the core product; this API does not change rates.");
  }

  private static void money(BigDecimal value, String name, int digits) {
    if (value == null
        || value.signum() < 0
        || value.stripTrailingZeros().scale() > digits
        || (long) value.precision() - value.scale() > 13)
      throw invalid(
          name,
          "A non-negative amount within product precision and 13 integer digits is required.");
  }

  private static void text(String value, String name, int length) {
    if (value == null
        || value.isBlank()
        || value.length() > length
        || value.chars().anyMatch(Character::isISOControl))
      throw invalid(
          name,
          "A nonblank label of at most "
              + length
              + " characters without control characters is required.");
  }

  static PlatformApiDataValidationException invalid(String field, String message) {
    return new PlatformApiDataValidationException(
        "error.msg.savings.product.policy.invalid", message, field);
  }
}
