/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyHttpIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Full authenticated lifecycle and genuine native administrative API bypass regression. */
@EnabledIfSystemProperty(named = "policy.http.baseUrl", matches = ".+")
class ProductPolicyWorkflowHttpIntegrationTest {
  static String admin, checker, executive;
  static long client;
  static String today;

  @BeforeAll
  static void fixtures() throws Exception {
    ProductPolicyHttpIntegrationTest.fixtures();
    client =
        jdbc.queryForObject(
            "SELECT client_id FROM m_savings_account WHERE id=?", Long.class, account);
    today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    admin = "be02-admin-" + suffix;
    checker = "be02-checker-" + suffix;
    executive = "be02-executive-" + suffix;
    for (String name : List.of(admin, checker, executive)) {
      jdbc.update(
          "INSERT INTO m_role(name,description,is_disabled) VALUES (?,'BE02 disposable"
              + " test',false)",
          name);
      jdbc.update(
          "INSERT INTO"
              + " m_appuser(office_id,username,firstname,lastname,password,email,firsttime_login_remaining,nonexpired,nonlocked,nonexpired_credentials,enabled,last_time_password_updated,password_never_expires)"
              + " SELECT"
              + " 1,?,'BE02','Fixture',password,'be02@example.test',false,true,true,true,true,CURRENT_DATE,true"
              + " FROM m_appuser WHERE username='mifos'",
          name);
      jdbc.update(
          "INSERT INTO m_appuser_role(appuser_id,role_id) SELECT u.id,r.id FROM m_appuser u,m_role"
              + " r WHERE u.username=? AND r.name=?",
          name,
          name);
      var permissions =
          name.equals(admin)
              ? List.of(
                  "READ_SAVINGS_PRODUCT_POLICY",
                  "UPDATE_SAVINGS_PRODUCT_POLICY",
                  "SUBMIT_SAVINGS_PRODUCT_POLICY")
              : name.equals(checker)
                  ? List.of("READ_SAVINGS_PRODUCT_POLICY", "CHECK_SAVINGS_PRODUCT_POLICY")
                  : List.of("CREATE_SAVINGSACCOUNT");
      for (String permission : permissions)
        jdbc.update(
            "INSERT INTO m_role_permission(role_id,permission_id) SELECT r.id,p.id FROM m_role"
                + " r,m_permission p WHERE r.name=? AND p.code=?",
            name,
            permission);
    }
  }

  static String submit(long revision, String date) {
    return GSON.toJson(Map.of("expectedRevision", revision, "effectiveDate", date));
  }

  static String nativeApplication(long product, boolean override) {
    var body = new LinkedHashMap<String, Object>();
    body.put("clientId", client);
    body.put("productId", product);
    body.put("submittedOnDate", today);
    body.put("dateFormat", "yyyy-MM-dd");
    body.put("locale", "en");
    if (override) body.put("nominalAnnualInterestRate", 9);
    return GSON.toJson(body);
  }

  @Test
  void realMandatoryReviewAndBothAccountOpeningPaths() {
    String path = ROOT + "/" + product;
    call(admin, "default", "PUT", path, ProductPolicyValidationTest.JSON, 200);
    call(executive, "default", "PUT", path, ProductPolicyValidationTest.JSON, 403);
    call(executive, "default", "POST", path + "/submit", submit(1, today), 403);
    call(admin, "default", "POST", path + "/submit", submit(1, today), 200);
    call(admin, "default", "POST", path + "/versions/1/approve", "{}", 403);
    call(executive, "default", "POST", path + "/versions/1/approve", "{}", 403);
    call(checker, "default", "GET", ROOT + "/pending", null, 200);
    assertFalse(
        call(checker, "default", "GET", path + "/versions/1", null, 200)
            .jsonPath()
            .getList("changedFields")
            .isEmpty());
    call("mifos", "default", "POST", "/v1/savingsaccounts", nativeApplication(product, false), 400);
    call(
        admin,
        "default",
        "PUT",
        path,
        ProductPolicyValidationTest.JSON.replace(
            "\"expectedRevision\":0", "\"expectedRevision\":1"),
        409);
    var approved =
        call(
            checker,
            "default",
            "POST",
            path + "/versions/1/approve",
            "{\"reason\":\"Independently reviewed\"}",
            200);
    assertEquals("ACTIVE", approved.jsonPath().getString("status"));
    call(checker, "default", "POST", path + "/versions/1/approve", "{}", 409);
    call("mifos", "default", "POST", "/v1/savingsaccounts", nativeApplication(product, true), 400);
    String application = GSON.toJson(Map.of("clientId", client, "expectedPolicyVersion", 1));
    call(
        executive,
        "default",
        "POST",
        path + "/applications",
        application.replace("}", ",\"nominalAnnualInterestRate\":9}"),
        400);
    long id =
        call(executive, "default", "POST", path + "/applications", application, 200)
            .jsonPath()
            .getLong("savingsId");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT version FROM m_savings_account_policy WHERE savings_account_id=?",
            Integer.class,
            id));
    assertEquals(
        0,
        jdbc.queryForObject(
                "SELECT nominal_annual_interest_rate FROM m_savings_account WHERE id=?",
                java.math.BigDecimal.class,
                id)
            .signum());
    call(
        "mifos",
        "default",
        "POST",
        "/v1/savingsaccounts",
        nativeApplication(product, false),
        400); // SINGLE across native path
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_savings_account WHERE product_id=?", Integer.class, product));
    assertEquals(
        4,
        call(admin, "default", "GET", path + "/audit", null, 200).jsonPath().getList("$").size());
    call(executive, "default", "GET", path + "/audit", null, 403);
    call(
        checker,
        "policy-secondary",
        "GET",
        path + "/versions",
        null,
        401); // identity is not in another tenant
    call("mifos", "policy-secondary", "GET", path + "/versions", null, 404);
    call(
        admin,
        "default",
        "PUT",
        path,
        ProductPolicyValidationTest.JSON.replace(
            "\"expectedRevision\":0", "\"expectedRevision\":1"),
        200);
    call(
        admin,
        "default",
        "POST",
        path + "/submit",
        submit(2, LocalDate.parse(today).plusDays(1).toString()),
        200);
    call(checker, "default", "POST", path + "/versions/2/reject", "{}", 400);
    call(
        checker,
        "default",
        "POST",
        path + "/versions/2/reject",
        "{\"reason\":\"Retain the existing approved settings\"}",
        200);
    call(checker, "default", "POST", path + "/versions/2/approve", "{}", 409);
    assertEquals(
        1, call(admin, "default", "GET", path + "/active", null, 200).jsonPath().getInt("version"));
  }

  @Test
  void privilegedMakerCannotSelfReviewAndFuturePolicyRemainsIneligible() {
    long p =
        createProduct(
            "BE02 future " + UUID.randomUUID(), "F" + UUID.randomUUID().toString().substring(0, 3));
    String path = ROOT + "/" + p;
    call("mifos", "default", "PUT", path, ProductPolicyValidationTest.JSON, 200);
    call(
        "mifos",
        "default",
        "POST",
        path + "/submit",
        submit(1, LocalDate.parse(today).plusDays(2).toString()),
        200);
    call("mifos", "default", "POST", path + "/versions/1/approve", "{}", 403);
    call(
        "mifos",
        "default",
        "POST",
        path + "/versions/1/reject",
        "{\"reason\":\"Self review prohibited\"}",
        403);
    assertEquals(
        "APPROVED",
        call(checker, "default", "POST", path + "/versions/1/approve", "{}", 200)
            .jsonPath()
            .getString("status"));
    call(admin, "default", "GET", path + "/active", null, 204);
    call("mifos", "default", "POST", "/v1/savingsaccounts", nativeApplication(p, false), 400);
    call(
        executive,
        "default",
        "POST",
        path + "/applications",
        GSON.toJson(Map.of("clientId", client, "expectedPolicyVersion", 1)),
        409);
  }

  @Test
  void nativeOverridesAndNormalApprovalActivationUseTheSameSnapshot() {
    long p =
        createProduct(
            "BE02 native " + UUID.randomUUID(), "N" + UUID.randomUUID().toString().substring(0, 3));
    String path = ROOT + "/" + p;
    call(
        admin,
        "default",
        "PUT",
        path,
        ProductPolicyValidationTest.JSON
            .replace("\"purpose\":\"MAIN\"", "\"purpose\":\"POCKET\"")
            .replace("\"multiplicity\":\"SINGLE\"", "\"multiplicity\":\"UNLIMITED\""),
        200);
    call(admin, "default", "POST", path + "/submit", submit(1, today), 200);
    call(checker, "default", "POST", path + "/versions/1/approve", "{}", 200);
    for (var override :
        List.of(
            Map.of("minRequiredOpeningBalance", 5),
            Map.of("interestCompoundingPeriodType", 4),
            Map.of("interestPostingPeriodType", 6),
            Map.of("interestCalculationType", 2),
            Map.of("interestCalculationDaysInYearType", 360),
            Map.of("lockinPeriodFrequency", 2, "lockinPeriodFrequencyType", 2),
            Map.of("allowOverdraft", true, "overdraftLimit", 10),
            Map.of("enforceMinRequiredBalance", true, "minRequiredBalance", 5),
            Map.of("withHoldTax", true),
            Map.of("purpose", "GUARANTEED"))) {
      var body =
          com.google.gson.JsonParser.parseString(nativeApplication(p, false)).getAsJsonObject();
      for (var entry : override.entrySet())
        body.add(entry.getKey(), GSON.toJsonTree(entry.getValue()));
      call("mifos", "default", "POST", "/v1/savingsaccounts", body.toString(), 400);
    }
    long nativeId =
        call("mifos", "default", "POST", "/v1/savingsaccounts", nativeApplication(p, false), 200)
            .jsonPath()
            .getLong("savingsId");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT version FROM m_savings_account_policy WHERE savings_account_id=?",
            Integer.class,
            nativeId));
    String date =
        GSON.toJson(Map.of("approvedOnDate", today, "dateFormat", "yyyy-MM-dd", "locale", "en"));
    call(
        "mifos",
        "default",
        "POST",
        "/v1/savingsaccounts/" + nativeId + "?command=approve",
        date,
        200);
    date =
        GSON.toJson(Map.of("activatedOnDate", today, "dateFormat", "yyyy-MM-dd", "locale", "en"));
    call(
        "mifos",
        "default",
        "POST",
        "/v1/savingsaccounts/" + nativeId + "?command=activate",
        date,
        200);
    assertEquals(
        300,
        jdbc.queryForObject(
            "SELECT status_enum FROM m_savings_account WHERE id=?", Integer.class, nativeId));
  }

  @Test
  void clientAutomaticOpeningCannotActivateAFuturePolicy() {
    long p =
        createProduct(
            "BE02 client " + UUID.randomUUID(), "C" + UUID.randomUUID().toString().substring(0, 3));
    String path = ROOT + "/" + p;
    call(admin, "default", "PUT", path, ProductPolicyValidationTest.JSON, 200);
    call(
        admin,
        "default",
        "POST",
        path + "/submit",
        submit(1, LocalDate.parse(today).plusDays(2).toString()),
        200);
    call(checker, "default", "POST", path + "/versions/1/approve", "{}", 200);
    call(
        "mifos",
        "default",
        "POST",
        "/v1/clients",
        GSON.toJson(
            Map.of(
                "officeId",
                1,
                "legalFormId",
                1,
                "firstname",
                "BE02",
                "lastname",
                "AutoOpening",
                "active",
                true,
                "activationDate",
                today,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en",
                "savingsProductId",
                p)),
        400);
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_savings_account WHERE product_id=?", Integer.class, p));
    long current =
        createProduct(
            "BE02 auto active " + UUID.randomUUID(),
            "A" + UUID.randomUUID().toString().substring(0, 3));
    String currentPath = ROOT + "/" + current;
    call(admin, "default", "PUT", currentPath, ProductPolicyValidationTest.JSON, 200);
    call(admin, "default", "POST", currentPath + "/submit", submit(1, today), 200);
    call(checker, "default", "POST", currentPath + "/versions/1/approve", "{}", 200);
    long individual =
        call(
                "mifos",
                "default",
                "POST",
                "/v1/clients",
                GSON.toJson(
                    Map.of(
                        "officeId",
                        1,
                        "legalFormId",
                        1,
                        "firstname",
                        "BE02",
                        "lastname",
                        "AutoApproved",
                        "active",
                        true,
                        "activationDate",
                        today,
                        "dateFormat",
                        "yyyy-MM-dd",
                        "locale",
                        "en",
                        "savingsProductId",
                        current)),
                200)
            .jsonPath()
            .getLong("clientId");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_savings_account a JOIN m_savings_account_policy b ON"
                + " b.savings_account_id=a.id WHERE a.client_id=? AND a.product_id=? AND"
                + " a.status_enum=300",
            Integer.class,
            individual,
            current));
  }

  @Test
  void chargedProductsInheritChargesAndRejectAmountOverrides() {
    long charge =
        call(
                "mifos",
                "default",
                "POST",
                "/v1/charges",
                GSON.toJson(
                    Map.of(
                        "name",
                        "BE02 fee " + UUID.randomUUID(),
                        "amount",
                        10,
                        "currencyCode",
                        "MXN",
                        "chargeAppliesTo",
                        2,
                        "chargeTimeType",
                        3,
                        "chargeCalculationType",
                        1,
                        "active",
                        true,
                        "penalty",
                        false,
                        "locale",
                        "en")),
                200)
            .jsonPath()
            .getLong("resourceId");
    long p =
        createProduct(
            "BE02 charges " + UUID.randomUUID(),
            "Q" + UUID.randomUUID().toString().substring(0, 3));
    call(
        "mifos",
        "default",
        "PUT",
        "/v1/savingsproducts/" + p,
        GSON.toJson(Map.of("charges", List.of(Map.of("id", charge)))),
        200);
    String path = ROOT + "/" + p;
    call(
        admin,
        "default",
        "PUT",
        path,
        ProductPolicyValidationTest.JSON
            .replace("\"purpose\":\"MAIN\"", "\"purpose\":\"POCKET\"")
            .replace("\"multiplicity\":\"SINGLE\"", "\"multiplicity\":\"UNLIMITED\""),
        200);
    call(admin, "default", "POST", path + "/submit", submit(1, today), 200);
    call(checker, "default", "POST", path + "/versions/1/approve", "{}", 200);
    var body =
        com.google.gson.JsonParser.parseString(nativeApplication(p, false)).getAsJsonObject();
    body.add("charges", GSON.toJsonTree(List.of(Map.of("chargeId", charge, "amount", 20))));
    call("mifos", "default", "POST", "/v1/savingsaccounts", body.toString(), 400);
    body.add("charges", GSON.toJsonTree(List.of()));
    call("mifos", "default", "POST", "/v1/savingsaccounts", body.toString(), 400);
    body.add("charges", GSON.toJsonTree(List.of(Map.of("chargeId", charge, "amount", 10))));
    long id =
        call("mifos", "default", "POST", "/v1/savingsaccounts", body.toString(), 200)
            .jsonPath()
            .getLong("savingsId");
    long derived =
        call(
                executive,
                "default",
                "POST",
                path + "/applications",
                GSON.toJson(Map.of("clientId", client, "expectedPolicyVersion", 1)),
                200)
            .jsonPath()
            .getLong("savingsId");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_savings_account_charge WHERE savings_account_id=?",
            Integer.class,
            derived));
    assertEquals(
        0,
        new java.math.BigDecimal("10")
            .compareTo(
                jdbc.queryForObject(
                    "SELECT amount FROM m_savings_account_charge WHERE savings_account_id=?",
                    java.math.BigDecimal.class,
                    id)));
  }

  @Test
  void nativeProductChangesRemainACoreAuthorizationGapButOpeningsFailClosed() {
    long p =
        createProduct(
            "BE02 drift " + UUID.randomUUID(), "D" + UUID.randomUUID().toString().substring(0, 3));
    String path = ROOT + "/" + p;
    call(admin, "default", "PUT", path, ProductPolicyValidationTest.JSON, 200);
    call(admin, "default", "POST", path + "/submit", submit(1, today), 200);
    call(checker, "default", "POST", path + "/versions/1/approve", "{}", 200);
    // Documents an unresolved core gap, not a permitted plugin policy transition.
    call(
        "mifos",
        "default",
        "PUT",
        "/v1/savingsproducts/" + p,
        "{\"nominalAnnualInterestRate\":1,\"locale\":\"en\"}",
        200);
    call("mifos", "default", "POST", "/v1/savingsaccounts", nativeApplication(p, false), 400);
    assertEquals(
        "APPROVED",
        jdbc.queryForObject(
            "SELECT status FROM m_savings_product_policy_version WHERE savings_product_id=? AND"
                + " version=1",
            String.class,
            p));
  }

  @Test
  void businessDateTransitionActivatesOneVersionAndPreservesHistory() {
    long p =
        createProduct(
            "BE02 dated " + UUID.randomUUID(), "B" + UUID.randomUUID().toString().substring(0, 3));
    String path = ROOT + "/" + p;
    call(admin, "default", "PUT", path, ProductPolicyValidationTest.JSON, 200);
    call(admin, "default", "POST", path + "/submit", submit(1, today), 200);
    call(checker, "default", "POST", path + "/versions/1/approve", "{}", 200);
    call(
        admin,
        "default",
        "PUT",
        path,
        ProductPolicyValidationTest.JSON.replace(
            "\"expectedRevision\":0", "\"expectedRevision\":1"),
        200);
    String future = LocalDate.parse(today).plusDays(2).toString();
    call(admin, "default", "POST", path + "/submit", submit(2, future), 200);
    call(checker, "default", "POST", path + "/versions/2/approve", "{}", 200);
    assertEquals(
        1, call(admin, "default", "GET", path + "/active", null, 200).jsonPath().getInt("version"));
    long configuration =
        jdbc.queryForObject(
            "SELECT id FROM c_configuration WHERE name='enable-business-date'", Long.class);
    call(
        "mifos",
        "default",
        "PUT",
        "/v1/configurations/" + configuration,
        "{\"enabled\":true}",
        200);
    try {
      call(
          "mifos",
          "default",
          "POST",
          "/v1/businessdate",
          GSON.toJson(
              Map.of(
                  "type",
                  "BUSINESS_DATE",
                  "date",
                  future,
                  "dateFormat",
                  "yyyy-MM-dd",
                  "locale",
                  "en")),
          200);
      assertEquals(
          2,
          call(admin, "default", "GET", path + "/active", null, 200).jsonPath().getInt("version"));
      assertEquals(
          "SUPERSEDED",
          call(admin, "default", "GET", path + "/versions", null, 200)
              .jsonPath()
              .getString("[0].status"));
      int count =
          call(admin, "default", "GET", path + "/audit", null, 200).jsonPath().getList("$").size();
      call(admin, "default", "GET", path + "/active", null, 200);
      assertEquals(
          count,
          call(admin, "default", "GET", path + "/audit", null, 200).jsonPath().getList("$").size());
    } finally {
      call(
          "mifos",
          "default",
          "POST",
          "/v1/businessdate",
          GSON.toJson(
              Map.of(
                  "type",
                  "BUSINESS_DATE",
                  "date",
                  today,
                  "dateFormat",
                  "yyyy-MM-dd",
                  "locale",
                  "en")),
          200);
      call(
          "mifos",
          "default",
          "PUT",
          "/v1/configurations/" + configuration,
          "{\"enabled\":false}",
          200);
    }
  }

  @Test
  void concurrentNativeSingleAccountOpeningsCannotBothCommit() throws Exception {
    long p =
        createProduct(
            "BE02 concurrent " + UUID.randomUUID(),
            "R" + UUID.randomUUID().toString().substring(0, 3));
    String path = ROOT + "/" + p;
    call(admin, "default", "PUT", path, ProductPolicyValidationTest.JSON, 200);
    call(admin, "default", "POST", path + "/submit", submit(1, today), 200);
    call(checker, "default", "POST", path + "/versions/1/approve", "{}", 200);
    long individual =
        call(
                "mifos",
                "default",
                "POST",
                "/v1/clients",
                GSON.toJson(
                    Map.of(
                        "officeId",
                        1,
                        "legalFormId",
                        1,
                        "firstname",
                        "BE02",
                        "lastname",
                        "Concurrent",
                        "active",
                        true,
                        "activationDate",
                        today,
                        "dateFormat",
                        "yyyy-MM-dd",
                        "locale",
                        "en")),
                200)
            .jsonPath()
            .getLong("clientId");
    var body =
        com.google.gson.JsonParser.parseString(nativeApplication(p, false)).getAsJsonObject();
    body.addProperty("clientId", individual);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Integer> opening =
          () -> {
            start.await();
            return call("mifos", "default", "POST", "/v1/savingsaccounts", body.toString(), -1)
                .statusCode();
          };
      var a = workers.submit(opening);
      var b = workers.submit(opening);
      start.countDown();
      var statuses = List.of(a.get(), b.get());
      assertEquals(1, statuses.stream().filter(code -> code == 200).count(), statuses.toString());
      assertTrue(
          statuses.stream().allMatch(code -> List.of(200, 400, 409).contains(code)),
          statuses.toString());
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_savings_account WHERE product_id=?", Integer.class, p));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_savings_policy_opening_slot WHERE client_id=?",
            Integer.class,
            individual));
  }

  @AfterAll
  static void evidence() throws Exception {
    Files.writeString(
        Path.of("target/product-policy-be02-http-evidence.json"),
        GSON.toJson(ProductPolicyHttpIntegrationTest.evidence));
  }
}
