/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import io.restassured.response.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Genuine Fineract HTTP verification; enable only against explicitly supplied disposable tenants.
 */
@EnabledIfSystemProperty(named = "policy.http.baseUrl", matches = ".+")
class ProductPolicyHttpIntegrationTest {
  static final String ROOT = "/v1/savingsproducts/policy";
  static final List<Map<String, Object>> evidence =
      java.util.Collections.synchronizedList(new ArrayList<>());
  static final Gson GSON = new Gson();
  static JdbcTemplate jdbc, secondary;
  static long product, legacy, account;
  static String reader, denied;
  static String coreBefore, financialBefore;

  @BeforeAll
  static void fixtures() throws Exception {
    jdbc = database("policy.http.jdbcUrl");
    secondary = database("policy.http.secondaryJdbcUrl");
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    reader = "policy-reader-" + suffix;
    denied = "policy-denied-" + suffix;
    for (String name : List.of(reader, denied)) {
      jdbc.update(
          "INSERT INTO m_role(name,description,is_disabled) VALUES (?, 'WEB-1350 disposable fixture',false)",
          name);
      jdbc.update(
          "INSERT INTO m_appuser(office_id,username,firstname,lastname,password,email,firsttime_login_remaining,nonexpired,nonlocked,nonexpired_credentials,enabled,last_time_password_updated,password_never_expires) SELECT 1,?,'Policy','Fixture',password,'policy@example.test',false,true,true,true,true,CURRENT_DATE,true FROM m_appuser WHERE username='mifos'",
          name);
      jdbc.update(
          "INSERT INTO m_appuser_role(appuser_id,role_id) SELECT u.id,r.id FROM m_appuser u,m_role r WHERE u.username=? AND r.name=?",
          name,
          name);
    }
    jdbc.update(
        "INSERT INTO m_role_permission(role_id,permission_id) SELECT r.id,p.id FROM m_role r,m_permission p WHERE r.name=? AND p.code='READ_SAVINGS_PRODUCT_POLICY'",
        reader);
    // Standard products are created using the authoritative Fineract API, never by policy storage.
    product = createProduct("Policy " + suffix, "P" + suffix.substring(0, 3));
    legacy = createProduct("Legacy " + suffix, "L" + suffix.substring(0, 3));
    String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    long client =
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
                        "Policy",
                        "lastname",
                        "Fixture",
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
    account =
        call(
                "mifos",
                "default",
                "POST",
                "/v1/savingsaccounts",
                GSON.toJson(
                    Map.of(
                        "clientId",
                        client,
                        "productId",
                        legacy,
                        "submittedOnDate",
                        today,
                        "dateFormat",
                        "yyyy-MM-dd",
                        "locale",
                        "en")),
                200)
            .jsonPath()
            .getLong("savingsId");
    coreBefore = snapshot("SELECT row_to_json(p)::text FROM m_savings_product p ORDER BY id");
    financialBefore = financialSnapshot();
  }

  static JdbcTemplate database(String property) {
    String url = System.getProperty(property, "");
    if (!url.matches("jdbc:postgresql://[^/]+/web1350_[a-zA-Z0-9_]+"))
      throw new IllegalArgumentException("Only disposable web1350_* databases are supported.");
    return new JdbcTemplate(
        new DriverManagerDataSource(
            url,
            System.getProperty("policy.it.dbUser", System.getProperty("user.name")),
            System.getProperty("policy.it.dbPassword", "")));
  }

  static long createProduct(String name, String shortName) {
    return call(
            "mifos",
            "default",
            "POST",
            "/v1/savingsproducts",
            GSON.toJson(
                Map.ofEntries(
                    Map.entry("name", name),
                    Map.entry("shortName", shortName),
                    Map.entry("description", "WEB-1350 disposable test"),
                    Map.entry("currencyCode", "MXN"),
                    Map.entry("digitsAfterDecimal", 2),
                    Map.entry("inMultiplesOf", 1),
                    Map.entry("nominalAnnualInterestRate", 0),
                    Map.entry("interestCompoundingPeriodType", 1),
                    Map.entry("interestPostingPeriodType", 4),
                    Map.entry("interestCalculationType", 1),
                    Map.entry("interestCalculationDaysInYearType", 365),
                    Map.entry("minRequiredOpeningBalance", 0),
                    Map.entry("accountingRule", 1),
                    Map.entry("locale", "en"))),
            200)
        .jsonPath()
        .getLong("resourceId");
  }

  @Test
  void saveReadUpdateConflictAndLegacyContract() {
    var saved =
        call(
            "mifos", "default", "PUT", ROOT + "/" + product, ProductPolicyValidationTest.JSON, 200);
    assertEquals("DRAFT", saved.jsonPath().getString("status"));
    assertFalse(saved.jsonPath().getBoolean("operational"));
    assertFalse(saved.jsonPath().getBoolean("capabilities.activation"));
    assertEquals(1, saved.jsonPath().getInt("revision"));
    var read = call(reader, "default", "GET", ROOT + "/" + product, null, 200);
    assertEquals(saved.jsonPath().getMap("policy"), read.jsonPath().getMap("policy"));
    call("mifos", "default", "PUT", ROOT + "/" + product, ProductPolicyValidationTest.JSON, 409);
    call(
        "mifos",
        "default",
        "PUT",
        ROOT + "/" + product,
        ProductPolicyValidationTest.JSON.replace(
            "\"expectedRevision\":0", "\"expectedRevision\":1"),
        200);
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT revision FROM m_savings_product_policy WHERE savings_product_id=?",
            Long.class,
            product));
    String update =
        ProductPolicyValidationTest.JSON.replace(
            "\"expectedRevision\":0", "\"expectedRevision\":2");
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Integer> writer =
          () -> {
            start.await();
            return call("mifos", "default", "PUT", ROOT + "/" + product, update, 0).statusCode();
          };
      var a = workers.submit(writer);
      var b = workers.submit(writer);
      start.countDown();
      assertEquals(java.util.Set.of(200, 409), java.util.Set.of(a.get(), b.get()));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT revision FROM m_savings_product_policy WHERE savings_product_id=?",
            Long.class,
            product));
    var old = call("mifos", "default", "GET", ROOT + "/" + legacy, null, 200);
    assertEquals("UNCONFIGURED", old.jsonPath().getString("status"));
    assertNull(old.jsonPath().get("policy"));
    call("mifos", "default", "GET", "/v1/savingsproducts/" + legacy, null, 200);
    call("mifos", "default", "GET", "/v1/savingsaccounts", null, 200);
    call("mifos", "default", "GET", "/v1/savingsaccounts/" + account, null, 200);
  }

  @Test
  void authenticationReadWritePermissionsAndCapabilities() {
    for (String path : List.of(ROOT + "/template", ROOT + "/" + product)) {
      call(null, "default", "GET", path, null, 401);
      call(denied, "default", "GET", path, null, 403);
      call(reader, "default", "GET", path, null, 200);
    }
    call(null, "default", "PUT", ROOT + "/" + product, ProductPolicyValidationTest.JSON, 401);
    call(denied, "default", "PUT", ROOT + "/" + product, ProductPolicyValidationTest.JSON, 403);
    call(reader, "default", "PUT", ROOT + "/" + product, ProductPolicyValidationTest.JSON, 403);
    var capabilities = call("mifos", "default", "GET", ROOT + "/template", null, 200);
    assertFalse(capabilities.jsonPath().getBoolean("activation"));
    assertFalse(capabilities.jsonPath().getBoolean("financialEnforcement"));
    assertEquals(3, capabilities.jsonPath().getList("classifications").size());
  }

  @Test
  void malformedUnknownAndContradictoryRequestsHavePlatformErrors() {
    for (String body :
        List.of(
            "{",
            ProductPolicyValidationTest.JSON.replace("SIGHT", "BOGUS"),
            ProductPolicyValidationTest.JSON.replace("55000000", "-1"),
            ProductPolicyValidationTest.JSON.replace("55000000", "1e2147483647"),
            ProductPolicyValidationTest.JSON.replace(
                "\"interestMode\":\"NONE\"", "\"interestMode\":\"TIERED\""),
            ProductPolicyValidationTest.JSON.replace("55000000", "0.001"),
            ProductPolicyValidationTest.JSON.replace(
                "\"expectedRevision\":0", "\"active\":true,\"expectedRevision\":0"))) {
      var response = call("mifos", "default", "PUT", ROOT + "/" + legacy, body, 400);
      assertNotNull(response.jsonPath().getString("developerMessage"));
      assertNotNull(response.jsonPath().getString("userMessageGlobalisationCode"));
    }
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_savings_product_policy WHERE savings_product_id=?",
            Integer.class,
            legacy));
    call("mifos", "default", "GET", ROOT + "/99999999", null, 404);
  }

  @Test
  void tenantBoundaryCannotReadOrWriteAnotherTenantProduct() {
    assertEquals(
        0,
        secondary.queryForObject(
            "SELECT count(*) FROM m_savings_product WHERE id=?", Integer.class, product));
    call("mifos", "policy-secondary", "GET", ROOT + "/" + product, null, 404);
    call(
        "mifos",
        "policy-secondary",
        "PUT",
        ROOT + "/" + product,
        ProductPolicyValidationTest.JSON,
        404);
    assertEquals(
        0,
        secondary.queryForObject("SELECT count(*) FROM m_savings_product_policy", Integer.class));
  }

  static Response call(
      String user, String tenant, String method, String path, String body, int expected) {
    var request =
        given()
            .relaxedHTTPSValidation()
            .baseUri(System.getProperty("policy.http.baseUrl"))
            .header("Fineract-Platform-TenantId", tenant)
            .contentType("application/json");
    if (user != null)
      request
          .auth()
          .preemptive()
          .basic(user, System.getProperty("policy.http.password", "password"));
    if (body != null) request.body(body);
    Response response = request.request(method, "/fineract-provider/api" + path);
    evidence.add(
        Map.of(
            "method",
            method,
            "path",
            path,
            "tenant",
            tenant,
            "actor",
            user == null ? "UNAUTHENTICATED" : user,
            "expectedStatus",
            expected,
            "actualStatus",
            response.statusCode(),
            "response",
            response.asString()));
    if (expected == 0)
      assertTrue(List.of(200, 409).contains(response.statusCode()), response.asString());
    else
      assertEquals(
          expected, response.statusCode(), method + " " + path + " " + response.asString());
    return response;
  }

  static String snapshot(String sql) {
    return jdbc.queryForList(sql).toString();
  }

  static String financialSnapshot() {
    StringBuilder result = new StringBuilder();
    for (String table :
        List.of(
            "m_savings_account",
            "m_savings_account_transaction",
            "m_loan",
            "m_loan_transaction",
            "acc_gl_journal_entry",
            "m_portfolio_command_source"))
      result
          .append(table)
          .append(snapshot("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY id"));
    return result.toString();
  }

  @AfterAll
  static void verifyAndEvidence() throws Exception {
    Files.writeString(Path.of("target/product-policy-http-evidence.json"), GSON.toJson(evidence));
    if (coreBefore != null) {
      assertEquals(
          coreBefore, snapshot("SELECT row_to_json(p)::text FROM m_savings_product p ORDER BY id"));
      assertEquals(financialBefore, financialSnapshot());
    }
  }
}
