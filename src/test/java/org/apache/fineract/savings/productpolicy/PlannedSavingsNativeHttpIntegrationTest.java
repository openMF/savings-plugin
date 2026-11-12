/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/**
 * Exact pinned Fineract runtime: native account/transaction/journal assertions, no fake balances.
 */
class PlannedSavingsNativeHttpIntegrationTest extends ProductTransactionNativeTestSupport {
  static final String ROOT = "/v1/savings/planned-savings";
  static final String DATE = ProductTransactionNativeHttpIntegrationTest.DATE;

  @Test
  void configurationAndRealAccountPocketsPreserveNativeFinancialControlsAndAccounting()
      throws Exception {
    var api = new ProductTransactionNativeHttpIntegrationTest();
    api.call("PUT", "/v1/currencies", Map.of("currencies", List.of("MXN", "USD")), 200);
    api.cash = api.gl("BE10 cash", 1);
    api.control = api.gl("BE10 control", 2);
    api.interest = api.gl("BE10 expense", 5);
    api.income = api.gl("BE10 income", 4);
    api.suspense = api.gl("BE10 suspense", 2);
    api.post(
        "/v1/financialactivityaccounts",
        Map.of("financialActivityId", 200, "glAccountId", api.suspense));
    api.checker = "be10-checker-" + UUID.randomUUID().toString().substring(0, 6);
    user(api, api.checker, 1);
    api.payment =
        api.post(
            "/v1/paymenttypes", Map.of("name", "BE10 cash", "isCashPayment", true, "position", 1));
    api.client = api.client();
    long other = api.client();
    long employer =
        api.post(
            "/v1/clients",
            Map.of(
                "officeId",
                1,
                "legalFormId",
                2,
                "fullname",
                "Synthetic BE10 Employer",
                "active",
                true,
                "activationDate",
                DATE,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    long planned = api.product(0);
    policy(api, planned, "PLANNED_MAIN", true);
    var config = JsonParser.parseString(PlannedSavingsValidationTest.JSON).getAsJsonObject();
    var c = config.getAsJsonObject("configuration");
    c.addProperty("savingsProductId", planned);
    c.addProperty("employerClientId", employer);
    c.addProperty("startDate", DATE);
    c.addProperty("maturityDate", LocalDate.parse(DATE).plusDays(60).toString());
    String id = UUID.randomUUID().toString(),
        path = ROOT + "/clients/" + api.client + "/arrangements/" + id;
    String before = api.financial();
    api.call("PUT", path, config.toString(), 200);
    api.call("PUT", path, config.toString(), 409);
    var schedule = api.call("GET", path + "/contributions", null, 200);
    assertEquals(5, schedule.jsonPath().getList("$").size());
    assertNull(schedule.jsonPath().get("[0].nativeTransactionId"));
    assertFalse(schedule.jsonPath().getBoolean("[0].executionEnabled"));
    api.call(
        "POST",
        "/v1/savingsaccounts",
        Map.of(
            "clientId",
            api.client,
            "productId",
            planned,
            "submittedOnDate",
            DATE,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"),
        400);
    api.call("GET", ROOT + "/clients/" + other + "/arrangements/" + id, null, 400);
    var review =
        Map.of(
            "expectedRevision",
            1,
            "idempotencyKey",
            UUID.randomUUID().toString(),
            "kind",
            "MATURITY",
            "reason",
            "Synthetic maturity review");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<String> attempt =
          () -> {
            start.await();
            return api.call("POST", path + "/reviews", review, 422).asString();
          };
      var a = pool.submit(attempt);
      var b = pool.submit(attempt);
      start.countDown();
      assertEquals(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    }
    assertEquals(
        "1",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_savings_planned_review WHERE arrangement_id='" + id + "'"));
    assertEquals(before, api.financial());

    long mainProduct = api.product(0);
    api.policy(mainProduct, true, true, false);
    long main = api.apply(mainProduct, api.client);
    long pocketProduct = api.product(0);
    // Pre-existing native accounts with real native deposits. Enrollment adds plugin metadata only;
    // this does not enable configured activation or claim an approved historical migration path.
    long first = api.active(pocketProduct), second = api.active(pocketProduct);
    long firstTransaction =
        api.transaction(first, "deposit", "11.00", 200).jsonPath().getLong("resourceId");
    long secondTransaction =
        api.transaction(second, "deposit", "22.00", 200).jsonPath().getLong("resourceId");
    assertNotEquals(firstTransaction, secondTransaction);
    assertTrue(
        Long.parseLong(querySingleValueInDefaultTenant("SELECT COUNT(*) FROM acc_gl_journal_entry"))
            >= 4);
    assertEquals(
        0,
        new BigDecimal(
                querySingleValueInDefaultTenant(
                    "SELECT COALESCE(SUM(CASE WHEN type_enum=1 THEN amount ELSE -amount END),0) FROM acc_gl_journal_entry"))
            .signum());
    policy(api, pocketProduct, "POCKET", false);
    for (long pocket : List.of(first, second))
      executeSqlInDefaultTenant(
          "INSERT INTO m_savings_account_policy(savings_account_id,savings_product_id,version,purpose,bound_at) VALUES(%s,%s,1,'POCKET',CURRENT_TIMESTAMP);",
          pocket, pocketProduct);
    before = api.financial();
    for (long pocket : List.of(first, second)) {
      var body =
          Map.of(
              "expectedRevision",
              0,
              "mainSavingsAccountId",
              main,
              "policyVersion",
              1,
              "mainPolicyVersion",
              1,
              "name",
              "Pocket " + pocket);
      String pocketPath = ROOT + "/clients/" + api.client + "/pockets/" + pocket;
      var linked = api.call("PUT", pocketPath, body, 200);
      assertEquals(
          0,
          new BigDecimal(pocket == first ? "11.00" : "22.00")
              .compareTo(new BigDecimal(linked.jsonPath().getString("nativeAccount.balance"))));
      assertFalse(
          api.call("GET", pocketPath + "/transfer-preview?amount=1", null, 200)
              .jsonPath()
              .getBoolean("executionEnabled"));
      assertFalse(
          api.call(
                  "GET",
                  ROOT + "/clients/" + api.client + "/accounts/" + pocket + "/closure-eligibility",
                  null,
                  200)
              .jsonPath()
              .getBoolean("executionEnabled"));
      api.call("PUT", ROOT + "/clients/" + other + "/pockets/" + pocket, body, 400);
      api.transaction(pocket, "withdrawal", "1.00", 400);
      api.call("POST", "/v1/accounttransfers", api.transfer(pocket, main), 400);
      api.call("POST", pocketPath + "/transfers", Map.of("amount", 1), 404);
    }
    assertEquals(before, api.financial());
    assertEquals(
        2,
        api.call("GET", ROOT + "/clients/" + api.client + "/pockets", null, 200)
            .jsonPath()
            .getList("$")
            .size());
    long pending = api.apply(pocketProduct, api.client);
    api.approve(pending);
    before = api.financial();
    api.activate(pending, 400);
    assertEquals(before, api.financial());
    var capabilities = api.call("GET", ROOT + "/template", null, 200);
    for (String capability :
        List.of(
            "nativeAccountCreation",
            "payrollExecution",
            "contributionScheduler",
            "frozenFundRelease",
            "maturitySettlement",
            "pocketTransfer",
            "accountClosure")) assertFalse(capabilities.jsonPath().getBoolean(capability));

    long role =
        api.post(
            "/v1/roles",
            Map.of(
                "name", "BE10 denied " + UUID.randomUUID(), "description", "Permission rejection"));
    String denied = "be10-denied-" + UUID.randomUUID().toString().substring(0, 6);
    user(api, denied, role);
    api.call(denied, "GET", ROOT + "/template", null, 403);
    api.call(
        denied,
        "PUT",
        ROOT + "/clients/" + api.client + "/arrangements/" + UUID.randomUUID(),
        config.toString(),
        403);
    api.call(denied, "POST", path + "/reviews", review, 403);
    api.call(denied, "GET", ROOT + "/clients/" + api.client + "/pockets", null, 403);
  }

  static void policy(
      ProductTransactionNativeHttpIntegrationTest api,
      long product,
      String purpose,
      boolean frozen) {
    var body = JsonParser.parseString(ProductPolicyValidationTest.JSON).getAsJsonObject();
    var p = body.getAsJsonObject("policy");
    p.addProperty("purpose", purpose);
    if (purpose.equals("POCKET")) p.addProperty("multiplicity", "UNLIMITED");
    if (frozen) {
      p.addProperty("freezing", "TERM");
      p.addProperty("freezingTermDays", 60);
    }
    String path = "/v1/savingsproducts/policy/" + product;
    api.call("PUT", path, body.toString(), 200);
    api.call("POST", path + "/submit", Map.of("expectedRevision", 1, "effectiveDate", DATE), 200);
    api.call(
        api.checker,
        "POST",
        path + "/versions/1/approve",
        Map.of("reason", "Independent synthetic BE10 review"),
        200);
  }

  static void user(ProductTransactionNativeHttpIntegrationTest api, String name, long role) {
    api.post(
        "/v1/users",
        Map.ofEntries(
            Map.entry("username", name),
            Map.entry("firstname", "BE10"),
            Map.entry("lastname", "Reviewer"),
            Map.entry("email", name + "@example.invalid"),
            Map.entry("officeId", 1),
            Map.entry("roles", List.of(role)),
            Map.entry("sendPasswordToEmail", false),
            Map.entry("password", "Be06-review-26!"),
            Map.entry("repeatPassword", "Be06-review-26!"),
            Map.entry("passwordNeverExpires", true)));
  }
}
