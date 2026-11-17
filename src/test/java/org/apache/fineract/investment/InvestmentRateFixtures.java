/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentRateData.*;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.function.Consumer;

final class InvestmentRateFixtures {
  private InvestmentRateFixtures() {}

  static final String BASE =
      """
      {"expectedRevision":0,"matrix":{"currencyCode":"MXN",
      "amountBands":[{"lower":100,"upper":1000,"upperInclusive":false},{"lower":1000,"upper":100000,"upperInclusive":true}],
      "termBands":[{"minimumDays":1,"maximumDays":30},{"minimumDays":31,"maximumDays":1825}],
      "cells":[{"amountBand":0,"termBand":0,"annualRatePercent":7.123456},
      {"amountBand":0,"termBand":1,"annualRatePercent":8},
      {"amountBand":1,"termBand":0,"annualRatePercent":9},
      {"amountBand":1,"termBand":1,"annualRatePercent":10}]},
      "effectiveValidity":{"mode":"IMMEDIATE","effectiveDate":null},"campaignMetadata":null}
      """;

  static String request(long revision, String date, String rate) {
    return edit(
        root -> {
          root.addProperty("expectedRevision", revision);
          if (date != null) {
            root.getAsJsonObject("effectiveValidity").addProperty("mode", "SCHEDULED");
            root.getAsJsonObject("effectiveValidity").addProperty("effectiveDate", date);
          }
          for (var cell : root.getAsJsonObject("matrix").getAsJsonArray("cells"))
            cell.getAsJsonObject().addProperty("annualRatePercent", new BigDecimal(rate));
        });
  }

  static String edit(Consumer<JsonObject> mutate) {
    var root = com.google.gson.JsonParser.parseString(BASE).getAsJsonObject();
    mutate.accept(root);
    return root.toString();
  }

  static Matrix largest() {
    var amounts = new ArrayList<AmountBand>();
    var terms = new ArrayList<TermBand>();
    var cells = new ArrayList<Cell>();
    for (int i = 0; i < 50; i++) {
      amounts.add(
          new AmountBand(
              BigDecimal.valueOf(100 + i * 100),
              BigDecimal.valueOf(i == 49 ? 100000 : 200 + i * 100),
              i == 49));
      terms.add(new TermBand(i + 1, i == 49 ? 1825 : i + 1));
      for (int j = 0; j < 50; j++) cells.add(new Cell(i, j, new BigDecimal("8.123456")));
    }
    return new Matrix("MXN", amounts, terms, cells);
  }
}
