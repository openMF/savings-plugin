/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import com.google.gson.*;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VaultDepositExecutionValidation {
  private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
  private final VaultDepositPreviewValidation previews;

  public VaultDepositExecutionRequest parse(String json) {
    try {
      JsonObject body = GSON.fromJson(json, JsonObject.class);
      allowed(body, Set.of("deposit", "vaultKey", "idempotencyKey", "source"));
      String vault = string(body.get("vaultKey"), "vaultKey", "VAULT:[1-9][0-9]{0,18}");
      String key = string(body.get("idempotencyKey"), "idempotencyKey", "[A-Za-z0-9._:-]{1,100}");
      VaultDepositExecutionRequest.Source proposedSource = null;
      if (body.has("source")) {
        var source = body.getAsJsonObject("source");
        allowed(source, Set.of("type", "reference"));
        String type =
            string(
                source.get("type"),
                "source.type",
                "INTERNAL_CUSTODY_TRANSFER|EXTERNAL_CASH_RECEIPT");
        String reference =
            string(source.get("reference"), "source.reference", "[A-Za-z0-9._:-]{1,100}");
        proposedSource =
            new VaultDepositExecutionRequest.Source(
                VaultDepositExecutionRequest.SourceType.valueOf(type), reference);
      }
      var deposit = previews.parse(GSON.toJson(body.get("deposit")));
      if (deposit.officeId() == null)
        throw invalid(
            "deposit.officeId", "office.required", "Execution requires an explicit officeId.");
      if (!vault.equals("VAULT:" + deposit.officeId()))
        throw invalid("vaultKey", "vault.mismatch", "vaultKey must match the selected officeId.");
      return new VaultDepositExecutionRequest(deposit, vault, key, proposedSource);
    } catch (PlatformApiDataValidationException e) {
      throw e;
    } catch (RuntimeException e) {
      throw invalid("request", "request.malformed", "Invalid vault deposit execution JSON.");
    }
  }

  private static void allowed(JsonObject object, Set<String> fields) {
    if (object == null) throw invalid("request", "object.required", "A JSON object is required.");
    for (String field : object.keySet())
      if (!fields.contains(field))
        throw invalid(field, "field.unsupported", "Unsupported execution field.");
  }

  private static String string(JsonElement value, String field, String pattern) {
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isString()
        || !value.getAsString().matches(pattern))
      throw invalid(field, "field.invalid", "A supported execution field value is required.");
    return value.getAsString();
  }

  static PlatformApiDataValidationException invalid(String field, String code, String message) {
    return new PlatformApiDataValidationException(
        "error.msg.base.teller.vault.deposit." + code, message, field);
  }
}
