/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import com.google.gson.*;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VaultDepositPreviewValidation {
  private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
  private final Validator validator;

  /** Reject unknown fields and lossy Gson long coercion before mapping to typed records. */
  public VaultDepositPreviewRequest parse(String json) {
    try {
      JsonObject body = GSON.fromJson(json, JsonObject.class);
      allowed(
          body,
          Set.of("officeId", "businessDate", "currencies", "correlationReference"),
          "request");
      if (body.has("officeId")) integer(body.get("officeId"), "officeId");
      string(body.get("businessDate"), "businessDate");
      if (body.has("correlationReference"))
        string(body.get("correlationReference"), "correlationReference");
      int ci = 0;
      for (JsonElement value : body.getAsJsonArray("currencies")) {
        String field = "currencies[" + ci++ + "]";
        JsonObject currency = value.getAsJsonObject();
        allowed(currency, Set.of("currencyCode", "denominations"), field);
        string(currency.get("currencyCode"), field + ".currencyCode");
        int di = 0;
        for (JsonElement line : currency.getAsJsonArray("denominations")) {
          String path = field + ".denominations[" + di++ + "]";
          JsonObject denomination = line.getAsJsonObject();
          allowed(denomination, Set.of("denominationId", "quantity"), path);
          string(denomination.get("denominationId"), path + ".denominationId");
          integer(denomination.get("quantity"), path + ".quantity");
        }
      }
      var request = GSON.fromJson(body, VaultDepositPreviewRequest.class);
      validate(request);
      return request;
    } catch (PlatformApiDataValidationException e) {
      throw e;
    } catch (RuntimeException e) {
      throw invalid(
          "request", "request.malformed", "Invalid preview JSON structure or field types.");
    }
  }

  public void validate(VaultDepositPreviewRequest request) {
    if (request == null)
      throw invalid("request", "request.required", "A deposit preview request is required.");
    var violations = validator.validate(request);
    if (!violations.isEmpty()) {
      var errors =
          violations.stream()
              .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
              .map(
                  v ->
                      ApiParameterError.parameterError(
                          "error.msg.base.teller.vault.preview.field.invalid",
                          v.getMessage(),
                          v.getPropertyPath().toString()))
              .toList();
      throw new PlatformApiDataValidationException(errors);
    }
    try {
      LocalDate.parse(request.businessDate());
    } catch (DateTimeParseException e) {
      throw invalid(
          "businessDate", "business.date.invalid", "businessDate must be a valid ISO local date.");
    }
    Set<String> codes = new HashSet<>();
    for (int ci = 0; ci < request.currencies().size(); ci++) {
      var currency = request.currencies().get(ci);
      String field = "currencies[" + ci + "]";
      if (!codes.add(currency.currencyCode().toUpperCase(Locale.ROOT)))
        throw invalid(
            field + ".currencyCode",
            "currency.duplicate",
            "Each currency may be declared only once.");
      Set<String> ids = new HashSet<>();
      boolean positive = false;
      for (int di = 0; di < currency.denominations().size(); di++) {
        var d = currency.denominations().get(di);
        if (!ids.add(d.denominationId().trim().toLowerCase(Locale.ROOT)))
          throw invalid(
              field + ".denominations[" + di + "].denominationId",
              "denomination.duplicate",
              "Denomination identifiers must be unique within a currency.");
        positive |= d.quantity() > 0;
      }
      if (!positive)
        throw invalid(
            field + ".denominations",
            "selection.empty",
            "Select at least one banknote or coin in each requested currency.");
    }
  }

  private static void allowed(JsonObject value, Set<String> fields, String path) {
    if (value == null) throw invalid(path, "request.malformed", "A JSON object is required.");
    for (String key : value.keySet()) {
      if (!fields.contains(key))
        throw invalid(path + "." + key, "field.unsupported", "Unsupported preview field.");
    }
  }

  private static void string(JsonElement value, String path) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw invalid(path, "field.type.invalid", "A string is required.");
  }

  private static void integer(JsonElement value, String path) {
    try {
      if (value == null
          || !value.isJsonPrimitive()
          || !value.getAsJsonPrimitive().isNumber()
          || !value.getAsString().matches("-?[0-9]{1,19}")) throw new NumberFormatException();
      Long.parseLong(value.getAsString());
    } catch (NumberFormatException e) {
      throw invalid(
          path, "integer.invalid", "A whole JSON integer within the supported range is required.");
    }
  }

  static PlatformApiDataValidationException invalid(String path, String code, String message) {
    return new PlatformApiDataValidationException(
        "error.msg.base.teller.vault.preview." + code, message, path);
  }
}
