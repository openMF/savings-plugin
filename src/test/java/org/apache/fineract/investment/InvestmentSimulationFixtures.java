/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentSimulationData.*;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

final class InvestmentSimulationFixtures {
  private InvestmentSimulationFixtures() {}

  static String request(long product, String key) {
    return edit(product, key, root -> {});
  }

  static String edit(long product, String key, Consumer<JsonObject> changes) {
    var root = new JsonObject();
    root.addProperty("productId", product);
    root.addProperty("investmentAmount", new BigDecimal("10000.00"));
    root.addProperty("termDays", 90);
    root.addProperty("startDate", "2026-11-17");
    root.addProperty("capitalizationEnabled", false);
    root.addProperty("interestPayoutMode", "AT_MATURITY");
    root.addProperty("automaticReinvestment", false);
    root.addProperty("requestedReinvestmentCount", 0);
    if (key != null) root.addProperty("requestKey", key);
    changes.accept(root);
    return root.toString();
  }

  static NativeSettings settings(int basis, int compounding, int posting) {
    return new NativeSettings(
        1, basis, compounding, posting, BigDecimal.ZERO, 1, false, "HALF_EVEN", 19);
  }

  static InvestmentRateData.Configuration published(String rate) {
    var policy = new InvestmentProductValidation().parse(InvestmentProductFixtures.JSON).policy();
    var request =
        new InvestmentRateValidation().parse(InvestmentRateFixtures.request(0, null, rate));
    return new InvestmentRateData.Configuration(
        1,
        policy.investmentType(),
        1,
        1L,
        InvestmentRateData.Status.APPROVED_EFFECTIVE,
        "MXN",
        "2020-01-01",
        "2020-01-01T00:00:00Z",
        "UTC",
        request,
        1,
        policy,
        InvestmentProductFixtures.nativeProduct(),
        1,
        "2020-01-01T00:00:00Z",
        2L,
        "2020-01-01T00:00:00Z",
        null,
        InvestmentRateData.capabilities(),
        InvestmentRateData.financialBlockers());
  }

  static InvestmentRateData.Selection selected(String rate, BigDecimal principal, int term) {
    return InvestmentRateResolver.select(
        1, published(rate), principal, term, Instant.parse("2026-01-01T00:00:00Z"));
  }

  static InvestmentProductData.Policy policy(Map<String, Object> changes) {
    return new InvestmentProductValidation()
        .parse(InvestmentProductFixtures.request(0, changes))
        .policy();
  }
}
