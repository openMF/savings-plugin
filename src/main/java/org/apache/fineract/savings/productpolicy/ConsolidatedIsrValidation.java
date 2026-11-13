/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Strict, bounded input; monetary values and actor/tenant/profile overrides are never accepted. */
final class ConsolidatedIsrValidation {
  private ConsolidatedIsrValidation() {}

  static <T> T parse(String json, Class<T> type, Set<String> fields) {
    if (json == null || json.length() > 12000)
      throw invalid("request", "Bounded JSON object required.");
    try {
      var object = JsonParser.parseString(json).getAsJsonObject();
      keys(object, fields);
      if (object.has("expectedVersion")) {
        var value = object.get("expectedVersion");
        if (!value.isJsonPrimitive()
            || !value.getAsJsonPrimitive().isNumber()
            || !value.getAsString().matches("0|[1-9][0-9]{0,18}"))
          throw new IllegalArgumentException();
        Long.parseLong(value.getAsString());
      }
      if (object.has("period")) keys(object.get("period"), Set.of("from", "through"));
      if (object.has("definition"))
        keys(
            object.get("definition"),
            Set.of(
                "jurisdiction",
                "taxTreatment",
                "currency",
                "effectiveFrom",
                "effectiveThrough",
                "specificationReference",
                "specificationSha256",
                "basisReference",
                "periodReference",
                "formulaReference",
                "allocationReference",
                "roundingReference",
                "accountingReference"));
      return RatePropagationJson.JSON.fromJson(object, type);
    } catch (com.google.gson.JsonParseException
        | IllegalStateException
        | IllegalArgumentException e) {
      throw invalid("request", "Invalid ISR diagnostic request.");
    }
  }

  private static void keys(JsonElement value, Set<String> fields) {
    if (!value.isJsonObject() || !fields.containsAll(value.getAsJsonObject().keySet()))
      throw invalid("request", "Unsupported ISR fields.");
  }

  static ConsolidatedIsrData.Period period(ConsolidatedIsrData.Period p, LocalDate today) {
    if (p == null
        || p.from() == null
        || p.through() == null
        || today == null
        || p.from().isAfter(p.through())
        || p.through().isAfter(today)
        || p.from().isBefore(LocalDate.of(1900, 1, 1))
        || java.time.temporal.ChronoUnit.DAYS.between(p.from(), p.through()) > 366)
      throw invalid(
          "period",
          "Ordered diagnostic dates through the business date, at most 367 days, are required.");
    return p;
  }

  static String uuid(String value) {
    try {
      if (value == null || !UUID.fromString(value).toString().equals(value))
        throw new IllegalArgumentException();
      return value;
    } catch (IllegalArgumentException e) {
      throw invalid("id", "Canonical UUID required.");
    }
  }

  static void text(String field, String value, boolean required, int max) {
    if (value == null && !required) return;
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value.chars().anyMatch(Character::isISOControl))
      throw invalid(field, "Nonblank bounded text without control characters required.");
  }

  static void size(long after, int limit) {
    if (after < 0 || limit < 1 || limit > 100)
      throw invalid("pagination", "Use after >= 0 and limit 1..100.");
  }

  static String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(RatePropagationJson.JSON.toJson(value).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
