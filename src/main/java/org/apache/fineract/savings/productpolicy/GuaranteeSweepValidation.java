/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.GuaranteeSweepData.*;
import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

final class GuaranteeSweepValidation {
  private GuaranteeSweepValidation() {}

  static Request parse(String json, Operation operation) {
    var root = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(
        root,
        operation == Operation.APPLY
            ? Set.of("idempotencyKey", "savingsAccountId", "amount", "reason")
            : Set.of("idempotencyKey", "savingsAccountId", "reason"));
    ProductPolicyValidation.integer(root.get("savingsAccountId"), "savingsAccountId");
    long source = root.get("savingsAccountId").getAsLong();
    if (source <= 0) throw invalid("savingsAccountId", "A positive source account is required.");
    String key = text(root, "idempotencyKey", 100);
    if (!key.matches("[A-Za-z0-9_-]{8,100}"))
      throw invalid("idempotencyKey", "Use 8-100 letters, digits, underscores or hyphens.");
    String reason = text(root, "reason", 250);
    BigDecimal amount = null;
    if (operation == Operation.APPLY) {
      var value = root.get("amount");
      if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
        throw invalid("amount", "A positive JSON decimal amount is required.");
      amount = value.getAsBigDecimal();
      if (amount.signum() <= 0 || !ProductTransactionPolicyEvaluator.validMoney(amount, 6))
        throw invalid("amount", "Amount exceeds supported monetary precision.");
    }
    return new Request(key, source, amount, reason);
  }

  private static String text(com.google.gson.JsonObject root, String field, int max) {
    var value = root.get(field);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw invalid(field, "A nonblank string is required.");
    String text = value.getAsString();
    if (text.isBlank() || text.length() > max || text.chars().anyMatch(Character::isISOControl))
      throw invalid(field, "Text is empty, too long or contains control characters.");
    return text;
  }

  static String fingerprint(long client, long loan, Request request, Operation operation) {
    try {
      String canonical =
          RatePropagationJson.JSON.toJson(
              new Object[] {
                client,
                loan,
                operation,
                request.savingsAccountId(),
                request.amount() == null
                    ? null
                    : request.amount().stripTrailingZeros().toPlainString(),
                request.reason()
              });
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
