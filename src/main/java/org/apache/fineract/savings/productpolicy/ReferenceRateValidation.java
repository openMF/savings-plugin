/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;
import static org.apache.fineract.savings.productpolicy.ReferenceRateData.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exact parsing shared by the formula facade and parent policy PUT; no implicit units. */
public final class ReferenceRateValidation {
  private ReferenceRateValidation() {}

  static JsonObject object(JsonElement e, String field) {
    if (e == null || !e.isJsonObject()) throw invalid(field, "Object required.");
    return e.getAsJsonObject();
  }

  static String text(JsonElement e, String field, int max) {
    if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
      throw invalid(field, "JSON string required.");
    String s = e.getAsString();
    if (s.isBlank() || s.length() > max || s.chars().anyMatch(Character::isISOControl))
      throw invalid(field, "Bounded nonblank text without control characters required.");
    return s;
  }

  static String identifier(String s, String field) {
    if (s == null || !s.matches("[A-Z][A-Z0-9_-]{0,63}"))
      throw invalid(field, "Explicit uppercase identifier of 1 to 64 characters required.");
    return s;
  }

  static <T extends Enum<T>> T enumeration(JsonElement e, String field, Class<T> type) {
    try {
      return Enum.valueOf(type, text(e, field, 64));
    } catch (IllegalArgumentException ex) {
      throw invalid(field, "Unsupported or missing option.");
    }
  }

  static BigDecimal decimal(JsonElement e, String field) {
    if (e == null
        || !e.isJsonPrimitive()
        || !e.getAsJsonPrimitive().isNumber()
        || !e.getAsString().matches("-?(0|[1-9][0-9]{0,12})(\\.[0-9]{1,12})?"))
      throw invalid(
          field, "Plain JSON decimal required: at most 13 integer and 12 fractional digits.");
    return e.getAsBigDecimal();
  }

  static void bounded(BigDecimal n, String field, int scale) {
    if (n == null || n.scale() > scale || n.scale() < 0 || (long) n.precision() - n.scale() > 13)
      throw invalid(field, "Unsupported decimal precision or representational overflow.");
  }

  static Formula formula(JsonElement e) {
    var o = object(e, "referenceRate");
    ProductPolicyValidation.fields(
        o,
        Set.of(
            "referenceId",
            "referenceType",
            "tenor",
            "formulaMode",
            "spreadPercentagePoints",
            "multiplier",
            "reviewFrequency"));
    var mode = enumeration(o.get("formulaMode"), "formulaMode", FormulaMode.class);
    String applicable =
        mode == FormulaMode.ADDITIVE_SPREAD ? "spreadPercentagePoints" : "multiplier";
    String irrelevant =
        mode == FormulaMode.ADDITIVE_SPREAD ? "multiplier" : "spreadPercentagePoints";
    if (o.has(irrelevant) && !o.get(irrelevant).isJsonNull())
      throw invalid(irrelevant, "Conflicting formula parameter.");
    var f =
        new Formula(
            identifier(text(o.get("referenceId"), "referenceId", 64), "referenceId"),
            enumeration(o.get("referenceType"), "referenceType", ReferenceType.class),
            identifier(text(o.get("tenor"), "tenor", 64), "tenor"),
            mode,
            mode == FormulaMode.ADDITIVE_SPREAD ? decimal(o.get(applicable), applicable) : null,
            mode == FormulaMode.MULTIPLICATIVE_FACTOR
                ? decimal(o.get(applicable), applicable)
                : null,
            enumeration(o.get("reviewFrequency"), "reviewFrequency", ReviewFrequency.class));
    validate(f);
    return f;
  }

  static void validate(Formula f) {
    if (f == null
        || f.referenceType() != ReferenceType.CETES
        || f.formulaMode() == null
        || f.reviewFrequency() == null)
      throw invalid("referenceRate", "Complete CETES formula required.");
    identifier(f.referenceId(), "referenceId");
    identifier(f.tenor(), "tenor");
    if (f.formulaMode() == FormulaMode.ADDITIVE_SPREAD) {
      if (f.multiplier() != null)
        throw invalid("multiplier", "Only spread is accepted in additive mode.");
      bounded(f.spreadPercentagePoints(), "spreadPercentagePoints", 12);
    } else {
      if (f.spreadPercentagePoints() != null)
        throw invalid("spreadPercentagePoints", "Only multiplier is accepted in factor mode.");
      bounded(f.multiplier(), "multiplier", 12);
      if (f.multiplier().signum() <= 0)
        throw invalid(
            "multiplier",
            "A strictly positive dimensionless multiplier is required; 120% is 1.20.");
    }
  }

  static void definition(JdbcTemplate jdbc, Formula f) {
    if (f == null) return;
    validate(f);
    if (jdbc.queryForList(
            "SELECT reference_id FROM m_savings_reference_rate WHERE reference_id=? AND"
                + " reference_type=? AND tenor=?",
            String.class,
            f.referenceId(),
            f.referenceType().name(),
            f.tenor())
        .isEmpty())
      throw invalid(
          "referenceId", "Matching tenant reference definition and supported tenor required.");
  }

  static LocalDate date(String s, String field) {
    try {
      if (s == null || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        throw new IllegalArgumentException();
      LocalDate date = LocalDate.parse(s);
      if (date.getYear() < 1) throw new IllegalArgumentException();
      return date;
    } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
      throw invalid(field, "ISO yyyy-MM-dd date required.");
    }
  }

  static LocalDate date(JsonElement e, String field) {
    return date(text(e, field, 10), field);
  }

  static LocalDate businessDate(LocalDate date) {
    LocalDate current;
    try {
      current = ProductPolicyWorkflow.today();
    } catch (IllegalArgumentException | NullPointerException e) {
      throw invalid("businessDate", "Tenant business date is required.");
    }
    if (current == null) throw invalid("businessDate", "Tenant business date is required.");
    return date == null ? current : date;
  }

  static long revision(JsonObject o) {
    ProductPolicyValidation.integer(o.get("expectedRevision"), "expectedRevision");
    return o.get("expectedRevision").getAsLong();
  }

  static String optionalText(JsonObject o, String field, int max) {
    return !o.has(field) || o.get(field).isJsonNull() ? null : text(o.get(field), field, max);
  }

  static Capture capture(String json) {
    var o = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(
        o,
        Set.of(
            "expectedRevision",
            "tenor",
            "annualRatePercent",
            "publicationDate",
            "effectiveDate",
            "sourceReference",
            "note"));
    var r =
        new Capture(
            revision(o),
            identifier(text(o.get("tenor"), "tenor", 64), "tenor"),
            decimal(o.get("annualRatePercent"), "annualRatePercent"),
            date(o.get("publicationDate"), "publicationDate"),
            date(o.get("effectiveDate"), "effectiveDate"),
            text(o.get("sourceReference"), "sourceReference", 500),
            optionalText(o, "note", 1000));
    bounded(r.annualRatePercent(), "annualRatePercent", 12);
    if (r.annualRatePercent().signum() < 0)
      throw invalid("annualRatePercent", "Negative quotes await institutional approval.");
    var today = businessDate(null);
    if (r.publicationDate().isAfter(today)
        || r.effectiveDate().isBefore(today)
        || r.effectiveDate().isBefore(r.publicationDate()))
      throw invalid(
          "effectiveDate", "Published observation and nonretroactive effective date required.");
    return r;
  }

  static Action action(String json, boolean rejecting) {
    var o = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(o, Set.of("expectedRevision", "reason"));
    var a = new Action(revision(o), optionalText(o, "reason", 1000));
    if (rejecting && a.reason() == null) throw invalid("reason", "Rejection reason required.");
    return a;
  }
}
