/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;
import static org.apache.fineract.savings.productpolicy.RatePropagationData.*;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * Strict request parsing; caller-supplied identity, source snapshots and capabilities are rejected.
 */
final class RatePropagationValidation {
  private RatePropagationValidation() {}

  static Plan plan(String json) {
    var o = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(o, Set.of("policyVersion", "effectiveDate"));
    return plan(o);
  }

  static Plan plan(JsonObject o) {
    return new Plan(
        positive(o, "policyVersion"),
        ReferenceRateValidation.date(o.get("effectiveDate"), "effectiveDate"));
  }

  static Create create(String json) {
    var o = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(o, Set.of("requestKey", "policyVersion", "effectiveDate"));
    var p = plan(o);
    return new Create(
        uuid(ReferenceRateValidation.text(o.get("requestKey"), "requestKey", 36), "requestKey"),
        p.policyVersion(),
        p.effectiveDate());
  }

  static Action action(String json, boolean reasonRequired) {
    var o = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(o, Set.of("expectedRevision", "reason"));
    var a =
        new Action(
            positive(o, "expectedRevision"),
            ReferenceRateValidation.optionalText(o, "reason", 1000));
    if (reasonRequired && a.reason() == null)
      throw invalid("reason", "A review or cancellation reason is required.");
    return a;
  }

  static long positive(JsonObject o, String field) {
    ProductPolicyValidation.integer(o.get(field), field);
    long n = o.get(field).getAsLong();
    if (n <= 0) throw invalid(field, "Positive integer required.");
    return n;
  }

  static String uuid(String value, String field) {
    if (value == null
        || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      throw invalid(field, "Canonical lowercase UUID required.");
    return UUID.fromString(value).toString();
  }

  static int limit(int requested, int configured) {
    if (requested < 1 || requested > Math.min(500, configured))
      throw invalid("limit", "Limit must be between 1 and the configured maximum (at most 500).");
    return requested;
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
