/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.JsonSerializer;
import com.google.gson.ToNumberPolicy;
import java.math.BigDecimal;

/** Preserve accepted plain decimal notation when a policy snapshot is edited through a facade. */
final class ProductPolicyJson {
  private ProductPolicyJson() {}

  static Gson create(boolean nulls) {
    var builder =
        new GsonBuilder()
            .setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL)
            .registerTypeAdapter(
                BigDecimal.class,
                (JsonSerializer<BigDecimal>)
                    (value, type, context) -> JsonParser.parseString(value.toPlainString()));
    if (nulls) builder.serializeNulls();
    return builder.create();
  }
}
