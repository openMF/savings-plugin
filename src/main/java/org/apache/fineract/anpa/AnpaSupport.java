/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;

final class AnpaSupport {
  private AnpaSupport() {}

  static final Gson JSON =
      new GsonBuilder()
          .serializeNulls()
          .registerTypeAdapter(
              LocalDate.class,
              (JsonSerializer<LocalDate>) (v, t, c) -> new JsonPrimitive(v.toString()))
          .registerTypeAdapter(
              LocalDate.class,
              (JsonDeserializer<LocalDate>) (v, t, c) -> LocalDate.parse(v.getAsString()))
          .registerTypeAdapter(
              Instant.class, (JsonSerializer<Instant>) (v, t, c) -> new JsonPrimitive(v.toString()))
          .registerTypeAdapter(
              Instant.class,
              (JsonDeserializer<Instant>) (v, t, c) -> Instant.parse(v.getAsString()))
          .create();

  static PlatformApiDataValidationException invalid(String field, String message) {
    return new PlatformApiDataValidationException("error.msg.anpa.invalid", message, field);
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

  static LocalDate date(String value, LocalDate today) {
    if (value == null) return today;
    try {
      if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
      var day = LocalDate.parse(value);
      if (day.isBefore(LocalDate.of(1900, 1, 1)) || day.isAfter(today))
        throw new IllegalArgumentException();
      return day;
    } catch (java.time.DateTimeException | IllegalArgumentException e) {
      throw invalid(
          "asOf", "ISO business date between 1900-01-01 and the current business date required.");
    }
  }

  static void page(long after, int limit) {
    if (after < 0 || limit < 1 || limit > 200)
      throw invalid("pagination", "after >= 0 and limit 1..200 required.");
  }

  static AnpaAccountingData.SaveRequest request(String json) {
    try {
      if (json == null || json.length() > 4096) throw new IllegalArgumentException();
      var o = JsonParser.parseString(json).getAsJsonObject();
      if (!Set.of("idempotencyKey", "asOf", "supersedesAssessmentId", "correctionReason")
          .containsAll(o.keySet())) throw new IllegalArgumentException();
      for (var v : o.asMap().values())
        if (!v.isJsonNull() && (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()))
          throw new IllegalArgumentException();
      var r = JSON.fromJson(o, AnpaAccountingData.SaveRequest.class);
      uuid(r.idempotencyKey());
      if (r.supersedesAssessmentId() != null) uuid(r.supersedesAssessmentId());
      if ((r.supersedesAssessmentId() == null) != (r.correctionReason() == null))
        throw new IllegalArgumentException();
      if (r.correctionReason() != null
          && (r.correctionReason().isBlank()
              || r.correctionReason().length() > 1000
              || r.correctionReason().chars().anyMatch(Character::isISOControl)))
        throw new IllegalArgumentException();
      return r;
    } catch (JsonParseException | IllegalStateException | IllegalArgumentException e) {
      throw invalid("request", "Invalid assessment request or unsupported fields.");
    }
  }

  static String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(JSON.toJson(value).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
