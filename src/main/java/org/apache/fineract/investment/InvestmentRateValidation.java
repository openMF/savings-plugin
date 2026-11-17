/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentProductValidation.invalid;
import static org.apache.fineract.investment.InvestmentRateData.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.springframework.stereotype.Component;

/** Complete ordered matrices; limits bound validation work and persisted snapshot size. */
@Component
public class InvestmentRateValidation {
  public static final int MAX_BANDS = 50;
  public static final int MAX_CELLS = 2500;
  private static final int MAX_JSON = 400_000;
  private static final Set<String> NULLABLE =
      Set.of("campaignMetadata", "upper", "effectiveDate", "endDate");

  public WriteRequest parse(String json) {
    var request = parse(json, WriteRequest.class);
    if (request.expectedRevision() < 0)
      throw invalid("expectedRevision", "Nonnegative revision required.");
    var validity = request.effectiveValidity();
    if (validity.mode() == ValidityMode.IMMEDIATE && validity.effectiveDate() != null)
      throw invalid(
          "effectiveDate", "Immediate validity takes its instant from the approval clock.");
    if (validity.mode() == ValidityMode.SCHEDULED)
      InvestmentRateTime.date(validity.effectiveDate(), "effectiveDate");
    var c = request.campaignMetadata();
    if (c != null) {
      if (!c.campaignId().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
          || c.name().isBlank()
          || c.name().length() > 200
          || c.name().chars().anyMatch(Character::isISOControl))
        throw invalid(
            "campaignMetadata", "A bounded campaign identifier and printable name are required.");
      var start = InvestmentRateTime.date(c.startDate(), "startDate");
      if (c.endDate() != null && InvestmentRateTime.date(c.endDate(), "endDate").isBefore(start))
        throw invalid("endDate", "Campaign end must be on or after its start.");
    }
    return request;
  }

  public PublishRequest publishRequest(String json) {
    var request = parse(json, PublishRequest.class);
    if (request.expectedRevision() < 1)
      throw invalid("expectedRevision", "Positive draft revision required.");
    return request;
  }

  private static <T> T parse(String json, Class<T> type) {
    if (json == null || json.length() > MAX_JSON)
      throw invalid("request", "JSON object must be at most 400000 characters.");
    try {
      try (var reader = new JsonReader(new StringReader(json))) {
        reader.setStrictness(Strictness.STRICT);
        scan(reader, 0);
        if (reader.peek() != JsonToken.END_DOCUMENT)
          throw invalid("request", "Trailing JSON is unsupported.");
      }
      var root = JsonParser.parseString(json);
      shape(root, type, "request");
      return InvestmentProductJson.JSON.fromJson(root, type);
    } catch (PlatformApiDataValidationException e) {
      throw e;
    } catch (Exception e) {
      throw invalid("request", "Malformed JSON or unsupported field type.");
    }
  }

  private static void scan(JsonReader reader, int depth) throws java.io.IOException {
    if (depth > 8) throw invalid("request", "Excessive JSON nesting.");
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
      int size = 0;
      while (reader.hasNext()) {
        if (++size > MAX_CELLS)
          throw invalid("request", "At most 2500 array entries are supported.");
        scan(reader, depth + 1);
      }
      reader.endArray();
    } else reader.skipValue();
  }

  /** Refuse Gson's normal coercions (strings to numbers, fractional integers, unknown fields). */
  private static void shape(JsonElement value, Type type, String field) {
    if (value == null || value.isJsonNull()) {
      if (!NULLABLE.contains(field)) throw invalid(field, "Required field is missing.");
      return;
    }
    if (type instanceof ParameterizedType list) {
      if (!value.isJsonArray()) throw invalid(field, "Array required.");
      for (var item : value.getAsJsonArray()) shape(item, list.getActualTypeArguments()[0], field);
    } else if (type instanceof Class<?> cls && cls.isRecord()) {
      if (!value.isJsonObject()) throw invalid(field, "Object required.");
      var object = value.getAsJsonObject();
      var names =
          Arrays.stream(cls.getRecordComponents())
              .map(java.lang.reflect.RecordComponent::getName)
              .toList();
      for (String key : object.keySet())
        if (!names.contains(key)) throw invalid(key, "Unknown field.");
      for (var component : cls.getRecordComponents())
        shape(object.get(component.getName()), component.getGenericType(), component.getName());
    } else {
      if (!value.isJsonPrimitive()) throw invalid(field, "Scalar required.");
      var p = value.getAsJsonPrimitive();
      boolean valid;
      if (type == String.class) valid = p.isString() && p.getAsString().length() <= 200;
      else if (type == boolean.class) valid = p.isBoolean();
      else if (type == BigDecimal.class)
        valid = p.isNumber() && p.getAsString().matches("-?(0|[1-9][0-9]{0,12})(\\.[0-9]{1,12})?");
      else if (type == Long.class || type == int.class)
        valid =
            p.isNumber()
                && p.getAsString().matches("[0-9]{1,19}")
                && p.getAsBigDecimal()
                        .compareTo(
                            BigDecimal.valueOf(
                                type == int.class ? Integer.MAX_VALUE : Long.MAX_VALUE))
                    <= 0;
      else if (type instanceof Class<?> cls && cls.isEnum())
        valid =
            p.isString()
                && Arrays.stream(cls.getEnumConstants())
                    .anyMatch(e -> ((Enum<?>) e).name().equals(p.getAsString()));
      else valid = false;
      if (!valid) throw invalid(field, "Invalid field type, value or precision.");
    }
  }

  public void validate(
      Matrix m, InvestmentProductData.Policy p, InvestmentProductData.NativeProduct n) {
    if (m == null
        || !p.currencyCode().equals(m.currencyCode())
        || !n.currencyCode().equals(m.currencyCode()))
      throw invalid("currencyCode", "Matrix currency must match policy and native product.");
    if (m.amountBands() == null
        || m.termBands() == null
        || m.cells() == null
        || m.amountBands().isEmpty()
        || m.termBands().isEmpty()
        || m.amountBands().size() > MAX_BANDS
        || m.termBands().size() > MAX_BANDS
        || m.cells().size() > MAX_CELLS
        || m.cells().size() != m.amountBands().size() * m.termBands().size())
      throw invalid("matrix", "Complete matrix required: 1–50 bands per axis, at most 2500 cells.");
    BigDecimal min =
        p.minimumInvestmentAmount() == null
            ? n.minimumDepositAmount()
            : p.minimumInvestmentAmount();
    BigDecimal max =
        p.maximumInvestmentAmount() == null
            ? n.maximumDepositAmount()
            : p.maximumInvestmentAmount();
    if (min == null) min = BigDecimal.ZERO;
    BigDecimal next = min;
    for (int i = 0; i < m.amountBands().size(); i++) {
      var b = m.amountBands().get(i);
      money(b.lower(), n, "lower");
      boolean last = i == m.amountBands().size() - 1;
      if (next == null || b.lower().compareTo(next) != 0)
        throw invalid(
            "lower",
            "Amount bands must be ordered, continuous and nonoverlapping from the product minimum.");
      if (b.upper() == null) {
        if (!last || max != null || b.upperInclusive())
          throw invalid("upper", "Only an uncapped product permits a final unbounded band.");
      } else {
        money(b.upper(), n, "upper");
        if (b.upper().compareTo(b.lower()) < 0
            || (!b.upperInclusive() && b.upper().compareTo(b.lower()) == 0))
          throw invalid("upper", "Amount band must be nonempty.");
        if (b.upperInclusive() != (last && max != null)
            || (last && (max == null || b.upper().compareTo(max) != 0)))
          throw invalid(
              "upperInclusive",
              "Only the final native/policy maximum is inclusive; full coverage is required.");
      }
      next = b.upper();
    }
    long term = p.minimumTermDays();
    for (var b : m.termBands()) {
      new InvestmentProductValidation().validateTerm(p, b.minimumDays());
      new InvestmentProductValidation().validateTerm(p, b.maximumDays());
      if (b.minimumDays() != term || b.maximumDays() < b.minimumDays())
        throw invalid(
            "termBands",
            "Ordered inclusive bands must cover all policy-eligible terms exactly once.");
      term = (long) b.maximumDays() + p.termMultipleDays();
    }
    if (m.termBands().getLast().maximumDays() != p.maximumTermDays())
      throw invalid("termBands", "Coverage must end at policy maximum term.");
    var cells = new HashSet<String>();
    for (var c : m.cells()) {
      if (c.amountBand() < 0
          || c.amountBand() >= m.amountBands().size()
          || c.termBand() < 0
          || c.termBand() >= m.termBands().size()
          || !cells.add(c.amountBand() + ":" + c.termBand()))
        throw invalid("cells", "Duplicate or invalid amount/term cell reference.");
      decimal(c.annualRatePercent(), 6, "annualRatePercent");
    }
  }

  static void decimal(BigDecimal value, int digits, String field) {
    if (value == null
        || value.signum() < 0
        || value.stripTrailingZeros().scale() > digits
        || (long) value.precision() - value.scale() > 13)
      throw invalid(
          field,
          "Nonnegative exact decimal within supported precision is required; no rounding is performed.");
  }

  static void money(
      BigDecimal value, InvestmentProductData.NativeProduct nativeProduct, String field) {
    decimal(value, nativeProduct.currencyDigits(), field);
    if (nativeProduct.currencyDigits() == 0
        && nativeProduct.currencyMultiple() != null
        && nativeProduct.currencyMultiple() > 0
        && value.remainder(BigDecimal.valueOf(nativeProduct.currencyMultiple())).signum() != 0)
      throw invalid(field, "Amount must respect native denomination multiples.");
  }

  static List<String> campaignBlockers(CampaignMetadata campaign) {
    if (campaign == null) return List.of();
    return campaign.type() == CampaignType.FINAL_RENEWAL_PERIOD
        ? List.of(
            "FINAL_RENEWAL_CONTEXT_UNAVAILABLE", "CAMPAIGN_APPLICABILITY_AND_PRECEDENCE_UNAPPROVED")
        : List.of("CAMPAIGN_APPLICABILITY_AND_PRECEDENCE_UNAPPROVED");
  }
}
