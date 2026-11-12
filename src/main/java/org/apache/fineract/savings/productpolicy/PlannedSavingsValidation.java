/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** Strict, bounded input; callers cannot supply authority, financial status, or transaction IDs. */
final class PlannedSavingsValidation {
  private PlannedSavingsValidation() {}

  record Write(long expectedRevision, PlannedSavingsData.Configuration configuration) {}

  static Write configuration(String json) {
    var root = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(root, Set.of("expectedRevision", "configuration"));
    long revision = revision(root);
    if (!root.has("configuration") || !root.get("configuration").isJsonObject())
      throw invalid("configuration", "A configuration object is required.");
    var c = root.getAsJsonObject("configuration");
    ProductPolicyValidation.fields(
        c,
        Set.of(
            "savingsProductId",
            "policyVersion",
            "employerClientId",
            "arrangementReference",
            "contributionAmount",
            "frequencyUnit",
            "frequencyInterval",
            "shortMonthRule",
            "startDate",
            "termDays",
            "maturityDate",
            "status"));
    long product = positive(c, "savingsProductId"),
        version = positive(c, "policyVersion"),
        employer = positive(c, "employerClientId");
    String reference = text(c, "arrangementReference", 100);
    BigDecimal amount = amount(c, "contributionAmount");
    var unit = enumeration(c, "frequencyUnit", PlannedSavingsData.FrequencyUnit.class);
    long interval = positive(c, "frequencyInterval"), term = positive(c, "termDays");
    // Resource bounds, not approved business limits. No unbounded schedule or date arithmetic.
    if (interval > 36500 || term > 36500)
      throw invalid(
          "termDays", "The metadata projection supports at most 36500 days/interval units.");
    PlannedSavingsData.ShortMonthRule rule = null;
    if (unit == PlannedSavingsData.FrequencyUnit.MONTHS)
      rule = enumeration(c, "shortMonthRule", PlannedSavingsData.ShortMonthRule.class);
    else if (c.has("shortMonthRule"))
      throw invalid("shortMonthRule", "Specify shortMonthRule only for MONTHS.");
    LocalDate start = date(c, "startDate"), maturity = date(c, "maturityDate");
    if (!start.plusDays(term).equals(maturity))
      throw invalid("maturityDate", "Maturity must equal startDate plus explicit termDays.");
    var configuration =
        new PlannedSavingsData.Configuration(
            product,
            version,
            employer,
            reference,
            amount,
            unit,
            (int) interval,
            rule,
            start,
            (int) term,
            maturity,
            enumeration(c, "status", PlannedSavingsData.ArrangementStatus.class));
    PlannedContributionSchedule.dates(configuration);
    return new Write(revision, configuration);
  }

  static long revision(JsonObject object) {
    ProductPolicyValidation.integer(object.get("expectedRevision"), "expectedRevision");
    return object.get("expectedRevision").getAsLong();
  }

  static long positive(JsonObject object, String name) {
    ProductPolicyValidation.integer(object.get(name), name);
    long value = object.get(name).getAsLong();
    if (value <= 0) throw invalid(name, "A positive integer is required.");
    return value;
  }

  static BigDecimal amount(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw invalid(name, "A positive exact JSON amount is required.");
    BigDecimal amount;
    try {
      amount = value.getAsBigDecimal();
    } catch (NumberFormatException e) {
      throw invalid(name, "Invalid decimal amount.");
    }
    if (!ProductTransactionPolicyEvaluator.validMoney(amount, 6) || amount.signum() <= 0)
      throw invalid(name, "A positive amount within 13 integer and 6 decimal digits is required.");
    return amount;
  }

  static String text(JsonObject object, String name, int maximum) {
    var value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw invalid(name, "A JSON string is required.");
    String text = value.getAsString();
    if (text.isBlank()
        || !text.equals(text.strip())
        || text.length() > maximum
        || text.chars().anyMatch(Character::isISOControl))
      throw invalid(
          name, "A bounded nonblank string without padding or control characters is required.");
    return text;
  }

  static <E extends Enum<E>> E enumeration(JsonObject object, String name, Class<E> type) {
    try {
      return Enum.valueOf(type, text(object, name, 40));
    } catch (IllegalArgumentException e) {
      throw invalid(name, "Unsupported enum value.");
    }
  }

  static LocalDate date(JsonObject object, String name) {
    try {
      String value = text(object, name, 10);
      LocalDate date = LocalDate.parse(value);
      if (!value.equals(date.toString()) || date.getYear() < 1900 || date.getYear() > 2200)
        throw new IllegalArgumentException();
      return date;
    } catch (java.time.DateTimeException | IllegalArgumentException e) {
      throw invalid(name, "Use an ISO date between 1900 and 2200.");
    }
  }

  static String id(String id) {
    try {
      if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException | NullPointerException e) {
      throw invalid("id", "A canonical lowercase UUID is required.");
    }
  }

  static void page(long after, int limit) {
    if (after < 0 || limit < 1 || limit > 100)
      throw invalid("pagination", "Use after >= 0 and limit 1-100.");
  }
}
