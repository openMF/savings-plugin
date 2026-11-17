/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.JsonSerializer;
import com.google.gson.Strictness;
import java.math.BigDecimal;

/** One decimal-preserving codec for responses and immutable audit snapshots. */
final class InvestmentProductJson {
  private InvestmentProductJson() {}

  static final Gson JSON =
      new GsonBuilder()
          .serializeNulls()
          .setStrictness(Strictness.STRICT)
          .registerTypeAdapter(
              BigDecimal.class,
              (JsonSerializer<BigDecimal>)
                  (value, type, context) -> JsonParser.parseString(value.toPlainString()))
          .create();
}
