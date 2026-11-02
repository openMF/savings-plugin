/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Minimal executive opening contract. Native command processing remains authoritative. */
@Service
@RequiredArgsConstructor
public class ProductPolicyApplicationService {
  private final ProductPolicyWorkflow workflow;
  private final PortfolioCommandSourceWritePlatformService commands;

  @Transactional
  public CommandProcessingResult apply(long product, String json) {
    workflow.authorize("CREATE_SAVINGSACCOUNT");
    var request = ProductPolicyValidation.object(json);
    if (!request.keySet().equals(Set.of("clientId", "expectedPolicyVersion")))
      throw ProductPolicyValidation.invalid(
          "request",
          "Only clientId and expectedPolicyVersion are supported; product settings are inherited.");
    long client, expected;
    try {
      for (String key : request.keySet())
        if (!request.get(key).isJsonPrimitive()
            || !request.get(key).getAsJsonPrimitive().isNumber()
            || !request.get(key).getAsString().matches("[1-9][0-9]{0,17}"))
          throw new IllegalArgumentException();
      client = request.get("clientId").getAsLong();
      expected = request.get("expectedPolicyVersion").getAsLong();
    } catch (Exception e) {
      throw ProductPolicyValidation.invalid(
          "request", "Positive clientId and expectedPolicyVersion are required.");
    }
    workflow.lock(product);
    var active = workflow.resolve(product, ProductPolicyWorkflow.today());
    if (active == null || active.version() != expected) throw new ProductPolicyService.Conflict();
    String command =
        ProductPolicyWorkflow.JSON.toJson(
            Map.of(
                "clientId",
                client,
                "productId",
                product,
                "submittedOnDate",
                ProductPolicyWorkflow.today().toString(),
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    var application = ProductPolicyWorkflow.JSON.fromJson(command, JsonObject.class);
    var charges = new com.google.gson.JsonArray();
    for (var item :
        ProductPolicyWorkflow.JSON
            .toJsonTree(active.coreSettings().get("charges"))
            .getAsJsonArray()) {
      var charge = item.getAsJsonObject();
      int time = charge.get("charge_time_enum").getAsInt();
      if (time == 2 || time == 11)
        throw ProductPolicyValidation.invalid(
            "charges", "Dated product fees require a dedicated approved due-date contract.");
      var inherited = new JsonObject();
      inherited.add("chargeId", charge.get("id"));
      inherited.add("amount", charge.get("amount"));
      charges.add(inherited);
    }
    if (!charges.isEmpty()) application.add("charges", charges);
    command = application.toString();
    return commands.logCommandSource(
        new CommandWrapperBuilder().createSavingsAccount().withJson(command).build());
  }
}
