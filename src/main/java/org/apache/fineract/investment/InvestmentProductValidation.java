/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentProductData.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.springframework.stereotype.Component;

/** Strict reusable configuration validation, independent of JAX-RS JSON coercion. */
@Component
public class InvestmentProductValidation {
  private static final Set<String> OPTIONAL =
      Set.of(
          "minimumInvestmentAmount",
          "maximumInvestmentAmount",
          "maximumReinvestments",
          "maximumReinvestmentsByTerm",
          "renewalRatePolicy",
          "cancellationPolicyReference");

  public WriteRequest parse(String json) {
    if (json == null || json.length() > 32000)
      throw invalid("request", "A JSON object of at most 32000 characters is required.");
    try {
      try (var reader = new JsonReader(new StringReader(json))) {
        reader.setStrictness(Strictness.STRICT);
        scan(reader, 0);
        if (reader.peek() != JsonToken.END_DOCUMENT)
          throw invalid("request", "Trailing JSON content.");
      }
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      fields(root, Set.of("expectedRevision", "policy"), "request");
      integer(root.get("expectedRevision"), "expectedRevision", Long.MAX_VALUE - 1);
      JsonObject policy = root.getAsJsonObject("policy");
      fields(
          policy,
          Arrays.stream(Policy.class.getRecordComponents())
              .map(java.lang.reflect.RecordComponent::getName)
              .collect(Collectors.toSet()),
          "policy");
      for (var field : Policy.class.getRecordComponents()) {
        String name = field.getName();
        JsonElement value = policy.get(name);
        if (value == null || value.isJsonNull()) {
          if (!OPTIONAL.contains(name)) throw invalid(name, "Required configuration is missing.");
          continue;
        }
        if (name.equals("maximumReinvestmentsByTerm")) {
          if (!value.isJsonArray()) throw invalid(name, "An array is required.");
          for (var entry : value.getAsJsonArray()) {
            var obj = entry.getAsJsonObject();
            fields(obj, Set.of("termDays", "maximumReinvestments"), name);
            integer(obj.get("termDays"), name + ".termDays", Integer.MAX_VALUE);
            integer(
                obj.get("maximumReinvestments"), name + ".maximumReinvestments", Integer.MAX_VALUE);
          }
        } else if (name.equals("cancellationPolicyReference")) {
          var obj = value.getAsJsonObject();
          fields(obj, Set.of("policyCode", "revision"), name);
          string(obj.get("policyCode"), name + ".policyCode");
          integer(obj.get("revision"), name + ".revision", Long.MAX_VALUE - 1);
        } else if (field.getType() == Integer.class) integer(value, name, Integer.MAX_VALUE);
        else if (field.getType() == BigDecimal.class) {
          if (!value.isJsonPrimitive()
              || !value.getAsJsonPrimitive().isNumber()
              || !value.getAsString().matches("[0-9]{1,13}(\\.[0-9]{1,6})?"))
            throw invalid(
                name,
                "A plain non-negative JSON decimal with at most 13 integer and 6 fractional digits is required.");
        } else if (field.getType() == Boolean.class) {
          if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw invalid(name, "A JSON boolean is required.");
        } else {
          string(value, name);
          if (field.getType().isEnum()
              && Arrays.stream(field.getType().getEnumConstants())
                  .noneMatch(e -> ((Enum<?>) e).name().equals(value.getAsString())))
            throw invalid(name, "Unsupported enum value.");
        }
      }
      var result = InvestmentProductJson.JSON.fromJson(root, WriteRequest.class);
      validate(result.policy());
      return result;
    } catch (PlatformApiDataValidationException e) {
      throw e;
    } catch (Exception e) {
      throw invalid("request", "Malformed JSON object or invalid field type.");
    }
  }

  private static void scan(JsonReader reader, int depth) throws java.io.IOException {
    if (depth > 5) throw invalid("request", "Excessive JSON nesting.");
    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
      reader.beginObject();
      var keys = new HashSet<String>();
      while (reader.hasNext()) {
        if (!keys.add(reader.nextName())) throw invalid("request", "Duplicate JSON field.");
        scan(reader, depth + 1);
      }
      reader.endObject();
    } else if (reader.peek() == JsonToken.BEGIN_ARRAY) {
      reader.beginArray();
      int count = 0;
      while (reader.hasNext()) {
        if (++count > 100)
          throw invalid("request", "At most 100 term renewal limits are supported.");
        scan(reader, depth + 1);
      }
      reader.endArray();
    } else reader.skipValue();
  }

  private static void fields(JsonObject object, Set<String> allowed, String name) {
    if (object == null) throw invalid(name, "A JSON object is required.");
    for (var key : object.keySet())
      if (!allowed.contains(key)) throw invalid(key, "Unknown field.");
  }

  private static void integer(JsonElement value, String field, long maximum) {
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isNumber()
        || !value.getAsString().matches("[0-9]{1,19}")
        || value.getAsBigDecimal().compareTo(BigDecimal.valueOf(maximum)) > 0)
      throw invalid(field, "A non-negative JSON integer within the supported range is required.");
  }

  private static void string(JsonElement value, String field) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw invalid(field, "A JSON string is required.");
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
    if (!p.currencyCode().matches("[A-Z]{3}"))
      throw invalid("currencyCode", "A three-letter currency code is required.");
    if (p.minimumTermDays() < 1) throw invalid("minimumTermDays", "Term must be at least one day.");
    if (p.maximumTermDays() < p.minimumTermDays())
      throw invalid("maximumTermDays", "Maximum term must cover the minimum.");
    if (p.termMultipleDays() < 1
        || (p.maximumTermDays().longValue() - p.minimumTermDays()) % p.termMultipleDays() != 0)
      throw invalid(
          "termMultipleDays", "A positive term increment must align the maximum with the minimum.");
    if (p.investmentType() == InvestmentType.PROMISSORY_NOTE && p.maximumTermDays() > 1825)
      throw invalid("maximumTermDays", "Promissory Note terms must be within 1–1825 days.");
    // 60 is the traditional suggested minimum, not a universal institution minimum.
    if (p.investmentType() == InvestmentType.CEDE
        && (p.minimumTermDays() % 30 != 0 || p.termMultipleDays() % 30 != 0))
      throw invalid("termMultipleDays", "CEDE terms must be multiples of 30 days.");
    money(p.minimumInvestmentAmount(), "minimumInvestmentAmount", 6, true);
    money(p.maximumInvestmentAmount(), "maximumInvestmentAmount", 6, false);
    if (p.minimumInvestmentAmount() != null
        && p.maximumInvestmentAmount() != null
        && p.maximumInvestmentAmount().compareTo(p.minimumInvestmentAmount()) < 0)
      throw invalid("maximumInvestmentAmount", "Maximum amount must cover the minimum.");
    var limits =
        p.maximumReinvestmentsByTerm() == null
            ? List.<TermRenewalLimit>of()
            : p.maximumReinvestmentsByTerm();
    if (limits.size() > 100)
      throw invalid("maximumReinvestmentsByTerm", "At most 100 entries are supported.");
    if (!p.automaticReinvestmentEnabled()) {
      if (p.maximumReinvestments() != null || !limits.isEmpty() || p.renewalRatePolicy() != null)
        throw invalid(
            "automaticReinvestmentEnabled",
            "Disabled reinvestment cannot contain renewal limits or rate instructions.");
    } else {
      if (p.renewalRatePolicy() != RenewalRatePolicy.RATE_EFFECTIVE_AT_RENEWAL)
        throw invalid("renewalRatePolicy", "The rate effective at renewal time is required.");
      if (p.maximumReinvestments() == null && limits.isEmpty())
        throw invalid(
            "maximumReinvestments", "A global or term-specific renewal limit is required.");
    }
    if (p.maximumReinvestments() != null && p.maximumReinvestments() < 0)
      throw invalid("maximumReinvestments", "A non-negative renewal count is required.");
    Set<Integer> terms = new HashSet<>();
    for (var limit : limits) {
      if (limit == null || limit.termDays() == null || limit.maximumReinvestments() == null)
        throw invalid("maximumReinvestmentsByTerm", "Each entry requires a term and count.");
      validateTerm(p, limit.termDays());
      if (!terms.add(limit.termDays())
          || limit.maximumReinvestments() < 0
          || (p.maximumReinvestments() != null
              && limit.maximumReinvestments() > p.maximumReinvestments()))
        throw invalid(
            "maximumReinvestmentsByTerm",
            "Terms must be unique and counts must be non-negative and within the global limit.");
    }
    if (p.capitalizationEnabled()
        && (!p.capitalizationEligible() || !p.automaticReinvestmentEnabled()))
      throw invalid(
          "capitalizationEnabled", "Capitalization requires eligibility and reinvestment.");
    if (p.capitalizationEnabled()
        != (p.interestPayoutMode() == InterestPayoutMode.CAPITALIZE_ON_RENEWAL))
      throw invalid(
          "interestPayoutMode",
          "CAPITALIZE_ON_RENEWAL and enabled capitalization must be selected together.");
    if (!p.earlyCancellationAllowed()
        && (p.penaltyMode() != PenaltyMode.NONE || p.cancellationPolicyReference() != null))
      throw invalid(
          "earlyCancellationAllowed",
          "Disabled cancellation cannot contain a penalty instruction.");
    if (p.penaltyMode() == PenaltyMode.VERSIONED_REFERENCE) {
      var ref = p.cancellationPolicyReference();
      if (ref == null
          || ref.policyCode() == null
          || !ref.policyCode().matches("[A-Za-z0-9_.-]{1,100}")
          || ref.revision() == null
          || ref.revision() < 1)
        throw invalid(
            "cancellationPolicyReference",
            "A stable policy code and positive revision are required; reference remains unresolved until PR 13.");
    } else if (p.cancellationPolicyReference() != null)
      throw invalid(
          "cancellationPolicyReference",
          "Only VERSIONED_REFERENCE accepts a future penalty reference.");
  }

  /**
   * Day terms accepted by later simulation/contracting callers must also pass current native rules.
   */
  public void validateTerm(Policy p, int days) {
    if (days < p.minimumTermDays()
        || days > p.maximumTermDays()
        || (days - (long) p.minimumTermDays()) % p.termMultipleDays() != 0)
      throw invalid("termDays", "Term is outside configured bounds or increments.");
  }

  public void validateAgainstNative(Policy p, NativeProduct nativeProduct) {
    validate(p);
    if (!p.currencyCode().equals(nativeProduct.currencyCode()))
      throw invalid("currencyCode", "Currency must match the native product.");
    if (nativeProduct.currencyDigits() < 0 || nativeProduct.currencyDigits() > 6)
      throw invalid(
          "currencyCode", "Native precision is outside the supported exact monetary range.");
    money(
        p.minimumInvestmentAmount(),
        "minimumInvestmentAmount",
        nativeProduct.currencyDigits(),
        true);
    money(
        p.maximumInvestmentAmount(),
        "maximumInvestmentAmount",
        nativeProduct.currencyDigits(),
        false);
    money(
        nativeProduct.minimumDepositAmount(),
        "nativeMinimumDepositAmount",
        nativeProduct.currencyDigits(),
        false);
    money(
        nativeProduct.maximumDepositAmount(),
        "nativeMaximumDepositAmount",
        nativeProduct.currencyDigits(),
        false);
    if (nativeProduct.minimumDepositAmount() != null
        && nativeProduct.maximumDepositAmount() != null
        && nativeProduct.minimumDepositAmount().compareTo(nativeProduct.maximumDepositAmount()) > 0)
      throw invalid("nativeProduct", "Native amount bounds are inconsistent.");
    if (nativeProduct.currencyMultiple() != null && nativeProduct.currencyMultiple() < 0)
      throw invalid("nativeProduct", "Native currency multiple is invalid.");
    BigDecimal minimum =
        p.minimumInvestmentAmount() == null
            ? nativeProduct.minimumDepositAmount()
            : p.minimumInvestmentAmount();
    BigDecimal maximum =
        p.maximumInvestmentAmount() == null
            ? nativeProduct.maximumDepositAmount()
            : p.maximumInvestmentAmount();
    if (nativeProduct.minimumDepositAmount() != null
        && minimum != null
        && minimum.compareTo(nativeProduct.minimumDepositAmount()) < 0)
      throw invalid("minimumInvestmentAmount", "Policy cannot weaken the native minimum deposit.");
    if (nativeProduct.maximumDepositAmount() != null
        && maximum != null
        && maximum.compareTo(nativeProduct.maximumDepositAmount()) > 0)
      throw invalid("maximumInvestmentAmount", "Policy cannot exceed the native maximum deposit.");
    if (minimum != null && maximum != null && maximum.compareTo(minimum) < 0)
      throw invalid(
          "maximumInvestmentAmount", "Effective native and plugin amount bounds do not overlap.");
    // Native Money applies denomination rounding only to zero-decimal currencies.
    // Reject amounts that would round there; decimal currencies keep their exact precision.
    if (nativeProduct.currencyDigits() == 0
        && nativeProduct.currencyMultiple() != null
        && nativeProduct.currencyMultiple() > 0) {
      BigDecimal unit = BigDecimal.valueOf(nativeProduct.currencyMultiple());
      for (var amount : new BigDecimal[] {p.minimumInvestmentAmount(), p.maximumInvestmentAmount()})
        if (amount != null && amount.remainder(unit).signum() != 0)
          throw invalid("investmentAmount", "Amount must respect native currency multiples.");
    }
    var t = nativeProduct.terms();
    Long min = days(t.minimum(), t.minimumUnit(), "nativeMinimumTerm");
    Long max = days(t.maximum(), t.maximumUnit(), "nativeMaximumTerm");
    Long multiple = days(t.multiple(), t.multipleUnit(), "nativeTermMultiple");
    if (min != null && p.minimumTermDays() < min)
      throw invalid("minimumTermDays", "Policy cannot weaken the native minimum term.");
    if (max != null && p.maximumTermDays() > max)
      throw invalid("maximumTermDays", "Policy cannot exceed the native maximum term.");
    if (multiple != null
        && (min == null
            || (p.minimumTermDays() - min) % multiple != 0
            || (p.maximumTermDays() - min) % multiple != 0
            || (p.minimumTermDays() < p.maximumTermDays() && p.termMultipleDays() % multiple != 0)))
      throw invalid(
          "termMultipleDays",
          "Every policy term must align with native increments measured from the native minimum.");
    if (p.interestPayoutMode() == InterestPayoutMode.MONTHLY
        && !Integer.valueOf(4).equals(nativeProduct.interestPostingPeriod()))
      throw invalid(
          "interestPayoutMode",
          "Monthly payout preference requires native monthly posting; posting alone does not execute payout.");
    if (p.earlyCancellationAllowed()
        && p.penaltyMode() == PenaltyMode.NATIVE
        && (nativeProduct.preClosure().interest() == null
            || nativeProduct.preClosure().interest().signum() < 0
            || nativeProduct.preClosure().interestOnType() == null))
      throw invalid("penaltyMode", "Native pre-closure penalty details are incomplete or invalid.");
    if (p.earlyCancellationAllowed()
        && p.penaltyMode() != PenaltyMode.VERSIONED_REFERENCE
        && (p.penaltyMode() == PenaltyMode.NATIVE) != nativeProduct.preClosure().applicable())
      throw invalid("penaltyMode", "Penalty mode must reflect native pre-closure applicability.");
  }

  private static Long days(Integer value, Integer unit, String field) {
    if (value == null) {
      if (unit != null) throw invalid(field, "Native term unit exists without a value.");
      return null;
    }
    if (value < 1 || unit == null || (unit != 0 && unit != 1))
      throw invalid(
          field,
          "Only positive native day/week restrictions can be verified without a start date; calendar month/year rules require future date-aware integration.");
    return value.longValue() * (unit == 1 ? 7 : 1);
  }

  private static void money(BigDecimal value, String field, int digits, boolean positive) {
    if (value != null
        && (value.signum() < (positive ? 1 : 0)
            || value.stripTrailingZeros().scale() > digits
            || (long) value.precision() - value.scale() > 13))
      throw invalid(
          field,
          "Amount must respect native precision, positivity and 13 integer digits without rounding.");
  }

  static PlatformApiDataValidationException invalid(String field, String message) {
    String code = "error.msg.investment.product.policy.invalid";
    return new PlatformApiDataValidationException(
        code, message, List.of(ApiParameterError.parameterError(code, message, field)));
  }
}
