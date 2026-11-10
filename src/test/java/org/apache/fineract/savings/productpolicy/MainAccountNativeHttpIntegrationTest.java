/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Exact Maven native runtime, real native account operations, and financial-table comparisons. */
class MainAccountNativeHttpIntegrationTest extends ProductTransactionNativeTestSupport {
  static final String ROOT = "/v1/savings/account-relationships";

  @Test
  void nativeMainOpeningRelationshipsAndRejectedGuaranteeCommandsPreserveFinancialControls()
      throws Exception {
    var api = new ProductTransactionNativeHttpIntegrationTest();
    String hash =
        HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(
                        Files.readAllBytes(Path.of("target/savings-plugin-1.16.0-SNAPSHOT.jar"))));
    assertTrue(
        fineract
            .execInContainer("sha256sum", "/app/plugins/savings-plugin.jar")
            .getStdout()
            .startsWith(hash));
    api.call("PUT", "/v1/currencies", Map.of("currencies", List.of("MXN", "USD")), 200);
    api.cash = api.gl("BE08 cash", 1);
    api.control = api.gl("BE08 control", 2);
    api.interest = api.gl("BE08 expense", 5);
    api.income = api.gl("BE08 income", 4);
    api.suspense = api.gl("BE08 suspense", 2);
    api.checker = "be08-checker-" + UUID.randomUUID().toString().substring(0, 6);
    user(api, api.checker, 1);
    api.client = api.client();
    long otherClient = api.client();
    long product = api.product(0);
    api.policy(product, true, true, false);
    long account;
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<io.restassured.response.Response> opening =
          () -> {
            start.await();
            return api.call(
                "POST",
                "/v1/savingsaccounts",
                Map.of(
                    "clientId",
                    api.client,
                    "productId",
                    product,
                    "submittedOnDate",
                    ProductTransactionNativeHttpIntegrationTest.DATE,
                    "dateFormat",
                    "yyyy-MM-dd",
                    "locale",
                    "en"),
                -1);
          };
      var first = workers.submit(opening);
      var second = workers.submit(opening);
      start.countDown();
      var results =
          List.of(
              first.get(30, java.util.concurrent.TimeUnit.SECONDS),
              second.get(30, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(1, results.stream().filter(r -> r.statusCode() == 200).count());
      assertTrue(results.stream().allMatch(r -> List.of(200, 400, 409).contains(r.statusCode())));
      account =
          results.stream()
              .filter(r -> r.statusCode() == 200)
              .findFirst()
              .orElseThrow()
              .jsonPath()
              .getLong("savingsId");
    }
    assertEquals(
        "1",
        querySingleValueInDefaultTenant(
            "SELECT count(*) FROM m_savings_main_account_audit WHERE savings_account_id="
                + account));
    String path = ROOT + "/clients/" + api.client + "/main";
    String before = api.financial();
    var found = api.call("GET", path, null, 200);
    assertEquals(account, found.jsonPath().getLong("relationships[0].savingsAccountId"));
    assertFalse(found.jsonPath().getBoolean("relationships[0].active"));
    for (int repeat = 0; repeat < 2; repeat++)
      assertEquals(
          account,
          api.call("PUT", path, Map.of("savingsAccountId", account), 200)
              .jsonPath()
              .getLong("savingsAccountId"));
    api.call(
        "PUT",
        ROOT + "/clients/" + otherClient + "/main",
        Map.of("savingsAccountId", account),
        400);
    api.call("PUT", path, Map.of("savingsAccountId", account, "override", true), 400);
    assertEquals(before, api.financial());
    var audit = api.call("GET", ROOT + "/clients/" + api.client + "/audit", null, 200);
    assertEquals(1, audit.jsonPath().getList("$").size());
    assertEquals("BIND", audit.jsonPath().getString("[0].action"));
    assertNotNull(audit.jsonPath().getString("[0].tenantIdentifier"));
    var template = api.call("GET", ROOT + "/template", null, 200);
    for (String capability :
        List.of(
            "guaranteedAccountCreation",
            "guaranteeAllocation",
            "guaranteeRelease",
            "loanDisbursementRouting",
            "paymentReferenceProvider")) assertFalse(template.jsonPath().getBoolean(capability));

    long role =
        api.post(
            "/v1/roles",
            Map.of(
                "name",
                "BE08 no privileges " + UUID.randomUUID(),
                "description",
                "Permission rejection test"));
    String reader = "be08-denied-" + UUID.randomUUID().toString().substring(0, 6);
    user(api, reader, role);
    for (String method : List.of("GET", "PUT"))
      api.call(
          reader,
          method,
          path,
          method.equals("PUT") ? Map.of("savingsAccountId", account) : null,
          403);
    api.call(reader, "GET", ROOT + "/clients/" + api.client + "/audit", null, 403);
    assertEquals(before, api.financial());

    api.call(
        "POST",
        "/v1/savingsaccounts",
        Map.of(
            "clientId",
            api.client,
            "productId",
            product,
            "submittedOnDate",
            ProductTransactionNativeHttpIntegrationTest.DATE,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"),
        400);
    assertEquals(before, api.financial());
    api.approve(account);
    before = api.financial();
    api.activate(account, 400);
    assertEquals(before, api.financial());

    long guaranteedProduct = api.product(0);
    var draft = JsonParser.parseString(ProductPolicyValidationTest.JSON).getAsJsonObject();
    var policy = draft.getAsJsonObject("policy");
    policy.addProperty("purpose", "GUARANTEED");
    policy.addProperty("liquidGuarantee", true);
    policy.addProperty("freezing", "LOAN_RELEASE");
    policy.addProperty("additionalPaymentReference", true);
    policy.addProperty("paymentReferencePartner", "Unapproved provider configuration only");
    String policyPath = "/v1/savingsproducts/policy/" + guaranteedProduct;
    api.call("PUT", policyPath, draft.toString(), 200);
    api.call(
        "POST",
        policyPath + "/submit",
        Map.of(
            "expectedRevision",
            1,
            "effectiveDate",
            ProductTransactionNativeHttpIntegrationTest.DATE),
        200);
    api.call(
        api.checker,
        "POST",
        policyPath + "/versions/1/approve",
        Map.of("reason", "Configuration approval does not authorize financial operations"),
        200);
    before = api.financial();
    api.call(
        "POST",
        "/v1/savingsaccounts",
        Map.of(
            "clientId",
            otherClient,
            "productId",
            guaranteedProduct,
            "submittedOnDate",
            ProductTransactionNativeHttpIntegrationTest.DATE,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"),
        400);
    assertEquals(before, api.financial());
    assertEquals(
        "0",
        querySingleValueInDefaultTenant(
            "SELECT count(*) FROM m_savings_account WHERE product_id=" + guaranteedProduct));
    assertEquals(
        "1",
        querySingleValueInDefaultTenant(
            "SELECT count(*) FROM m_savings_main_account_audit WHERE savings_account_id="
                + account));
  }

  private void user(ProductTransactionNativeHttpIntegrationTest api, String username, long role) {
    api.post(
        "/v1/users",
        Map.ofEntries(
            Map.entry("username", username),
            Map.entry("firstname", "BE08"),
            Map.entry("lastname", "Synthetic"),
            Map.entry("email", username + "@example.invalid"),
            Map.entry("officeId", 1),
            Map.entry("roles", List.of(role)),
            Map.entry("sendPasswordToEmail", false),
            Map.entry("password", "Be06-review-26!"),
            Map.entry("repeatPassword", "Be06-review-26!"),
            Map.entry("passwordNeverExpires", true)));
  }
}
