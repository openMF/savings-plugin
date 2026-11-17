/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentProductData.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

final class InvestmentProductFixtures {
  private InvestmentProductFixtures() {}

  static final String JSON =
      """
      {"expectedRevision":0,"policy":{
        "investmentType":"PROMISSORY_NOTE","currencyCode":"MXN",
        "minimumTermDays":1,"maximumTermDays":1825,"termMultipleDays":1,
        "automaticReinvestmentEnabled":false,"capitalizationEligible":false,
        "capitalizationEnabled":false,"interestPayoutMode":"AT_MATURITY",
        "principalSettlementMode":"AT_MATURITY","earlyCancellationAllowed":false,
        "penaltyMode":"NONE","penaltyFreeNonRenewalSupported":true}}
      """;

  static String request(long revision, Map<String, Object> changes) {
    var root = com.google.gson.JsonParser.parseString(JSON).getAsJsonObject();
    root.addProperty("expectedRevision", revision);
    changes.forEach(
        (key, value) ->
            root.getAsJsonObject("policy").add(key, InvestmentProductJson.JSON.toJsonTree(value)));
    return root.toString();
  }

  static NativeProduct nativeProduct(
      NativeTerms terms, int accounting, List<AccountingMapping> mappings) {
    return new NativeProduct(
        1,
        "Synthetic FD",
        "MXN",
        2,
        1,
        terms,
        new BigDecimal("100"),
        new BigDecimal("100000"),
        new BigDecimal("1000"),
        new NativePenalty(false, null, null),
        accounting,
        false,
        4,
        1,
        false,
        null,
        mappings);
  }

  static NativeProduct nativeProduct() {
    return nativeProduct(new NativeTerms(1, 0, 1825, 0, null, null), 2, mappings());
  }

  static List<AccountingMapping> mappings() {
    return Map.of(1, 1, 2, 2, 3, 5, 4, 4, 5, 4, 10, 2).entrySet().stream()
        .map(
            e ->
                new AccountingMapping(
                    e.getKey(),
                    e.getKey(),
                    e.getKey().longValue(),
                    false,
                    e.getValue(),
                    1,
                    null,
                    null))
        .toList();
  }
}
