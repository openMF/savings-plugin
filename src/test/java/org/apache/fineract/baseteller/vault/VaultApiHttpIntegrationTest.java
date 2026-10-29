/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import io.restassured.response.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Real Fineract HTTP and PostgreSQL, enabled explicitly against freshly migrated disposable
 * web1328_* databases. No mocked authentication, SQL schema substitutes, or cash fixtures.
 */
@EnabledIfSystemProperty(named = "vault.it.enabled", matches = "true")
class VaultApiHttpIntegrationTest {
  private static final Gson GSON = new Gson();
  private static final String ROOT = "/v2/base-teller/vault";
  private static final String SECRET = "Vault-context-test-1328!";
  private static final List<Map<String, Object>> evidence = new ArrayList<>();
  private static final List<String> FINANCIAL_TABLES =
      List.of(
          "m_cash_allocation",
          "m_cash_allocation_denomination",
          "m_cashier_transactions",
          "m_cashier_reconciliation",
          "m_cashier_reconciliation_denomination",
          "m_cashier_reconciliation_check",
          "m_cash_operation_transaction",
          "m_cash_operation_denomination",
          "m_cash_operation_check",
          "m_cash_exchange",
          "m_cash_exchange_detail",
          "m_cash_drawer_pickup",
          "m_cash_drawer_pickup_currency",
          "m_cash_drawer_pickup_cash",
          "m_cash_drawer_pickup_check",
          "m_cash_teller_transfer",
          "m_cash_teller_transfer_currency",
          "m_cash_teller_transfer_denomination",
          "m_base_teller_deposit",
          "m_base_teller_savings_opening",
          "m_base_teller_withdrawal",
          "m_base_teller_account_transfer",
          "m_base_teller_credit_payment",
          "m_service_payment",
          "m_end_of_day_closing",
          "m_end_of_day_closing_check",
          "m_transaction_reversal",
          "m_tellers",
          "m_cashiers",
          "m_fx_rate_draft",
          "m_fx_rate_audit",
          "m_savings_account",
          "m_savings_account_transaction",
          "m_loan",
          "m_loan_transaction",
          "acc_gl_journal_entry",
          "m_portfolio_command_source");
  private static Connection database;
  private static Connection secondary;
  private static long branchA, branchB;
  private static String reader, forbidden, cashier;
  private static Map<String, String> before;
  private static Map<String, String> secondaryBefore;

  @BeforeAll
  static void fixtures() throws Exception {
    database = connect("vault.it.jdbcUrl");
    secondary = connect("vault.it.secondaryJdbcUrl");
    String unique = UUID.randomUUID().toString().substring(0, 8);
    long readRole =
        post(
            "/v1/roles",
            Map.of("name", "Vault reader " + unique, "description", "Read only vault context"));
    long noRole =
        post("/v1/roles", Map.of("name", "No vault " + unique, "description", "No vault access"));
    ok(
        "mifos",
        "PUT",
        "/v1/roles/" + readRole + "/permissions",
        Map.of("permissions", Map.of(VaultReadPlatformService.PERMISSION, true)));
    branchA =
        post(
            "/v1/offices",
            Map.of(
                "name",
                "Vault A " + unique,
                "parentId",
                1,
                "openingDate",
                "01 January 2020",
                "dateFormat",
                "dd MMMM yyyy",
                "locale",
                "en"));
    branchB =
        post(
            "/v1/offices",
            Map.of(
                "name",
                "Vault B " + unique,
                "parentId",
                1,
                "openingDate",
                "01 January 2020",
                "dateFormat",
                "dd MMMM yyyy",
                "locale",
                "en"));
    reader = "vault-reader-" + unique;
    forbidden = "vault-forbidden-" + unique;
    cashier = "vault-cashier-" + unique;
    user(reader, branchA, readRole);
    user(forbidden, branchA, noRole);
    user(cashier, branchA, readRole);
    ok("mifos", "PUT", "/v1/currencies", Map.of("currencies", List.of("USD", "JPY")));

    // These are configuration and assignment fixtures only. No quantities, cash or opening records.
    sql(database, "DELETE FROM m_service_payment_denomination");
    sql(
        database,
        "INSERT INTO"
            + " m_service_payment_denomination(identifier,currency_code,value,denomination_type,active)"
            + " VALUES ('vault-note','USD',100.000000,'BILL',true),"
            + " ('vault-coin','USD',0.010000,'COIN',true),"
            + " ('vault-inactive','USD',50.000000,'BANKNOTE',false)");
    sql(
        database,
        "INSERT INTO m_staff(office_id,firstname,lastname,display_name,is_active,is_loan_officer)"
            + " VALUES ("
            + branchA
            + ",'Vault','Cashier','Vault fixture "
            + unique
            + "',true,false)");
    sql(
        database,
        "INSERT INTO m_tellers(office_id,name,description,state) VALUES ("
            + branchA
            + ",'Vault window "
            + unique
            + "','Read-only test assignment',300)");
    sql(
        database,
        "INSERT INTO m_cashiers(staff_id,teller_id,description,full_day) SELECT"
            + " s.id,t.id,'Read-only test',true FROM m_staff s,m_tellers t WHERE"
            + " s.display_name='Vault fixture "
            + unique
            + "' AND t.name='Vault window "
            + unique
            + "'");
    sql(
        database,
        "UPDATE m_appuser SET staff_id=(SELECT id FROM m_staff WHERE display_name='Vault fixture "
            + unique
            + "') WHERE username='"
            + cashier
            + "'");

    // Prove identical resource identifiers route to distinct actual tenant databases.
    sql(secondary, "UPDATE m_office SET name='WEB1328 Secondary Tenant' WHERE id=1");
    sql(secondary, "DELETE FROM m_service_payment_denomination");
    assertEquals(
        200,
        call(
                "mifos",
                "PUT",
                "/v1/currencies",
                Map.of("currencies", List.of("EUR")),
                "web1328-secondary")
            .statusCode());
    before = financialSnapshot(database);
    secondaryBefore = financialSnapshot(secondary);
  }

  @AfterAll
  static void noMutationsAndEvidence() throws Exception {
    try {
      if (before != null)
        assertEquals(before, financialSnapshot(database), "Default tenant financial state changed");
      if (secondaryBefore != null)
        assertEquals(
            secondaryBefore,
            financialSnapshot(secondary),
            "Secondary tenant financial state changed");
      Files.createDirectories(Path.of("target"));
      Files.writeString(Path.of("target/web1328-http-evidence.json"), GSON.toJson(evidence));
      if (before != null)
        Files.writeString(
            Path.of("target/web1328-financial-snapshots.json"),
            GSON.toJson(
                Map.of(
                    "before",
                    before,
                    "after",
                    financialSnapshot(database),
                    "secondaryBefore",
                    secondaryBefore,
                    "secondaryAfter",
                    financialSnapshot(secondary))));
    } finally {
      if (database != null) database.close();
      if (secondary != null) secondary.close();
    }
  }

  @Test
  void adminAndLeastPrivilegeReaderReceiveActualContextAndCapabilities() {
    for (String user : List.of("mifos", reader)) {
      var context =
          ok(
                  user,
                  "GET",
                  ROOT + "/context" + (user.equals("mifos") ? "?officeId=" + branchA : ""),
                  null)
              .jsonPath();
      assertEquals(branchA, context.getLong("branch.id"));
      assertEquals("VAULT:" + branchA, context.getString("vault.key"));
      assertEquals("UNKNOWN", context.getString("vault.status"));
      assertTrue(context.getBoolean("capabilities.viewContext"));
      assertTrue(context.getBoolean("capabilities.previewDeposit"));
      assertFalse(context.getBoolean("capabilities.executeDeposit"));
      assertFalse(context.getBoolean("readiness.depositReady"));
      assertNotNull(context.getString("businessDate"));
    }
  }

  @Test
  void everyRouteRequiresAuthenticationPermissionAndBranchCustody() {
    for (String route :
        List.of(
            "context",
            "currencies",
            "denominations?currencyCode=USD",
            "inventory?currencyCode=USD")) {
      String path = ROOT + "/" + route;
      assertEquals(401, call(null, "GET", path, null, "default").statusCode());
      assertEquals(403, call(forbidden, "GET", path, null, "default").statusCode());
      assertEquals(403, call(cashier, "GET", path, null, "default").statusCode());
      assertEquals(
          403,
          call(
                  reader,
                  "GET",
                  path + (path.contains("?") ? "&" : "?") + "officeId=" + branchB,
                  null,
                  "default")
              .statusCode());
    }
  }

  @Test
  void currenciesComeFromTenantConfigurationAndCatalogsRemainSeparated() {
    var configured = ok(reader, "GET", ROOT + "/currencies", null).jsonPath();
    assertEquals(List.of("JPY", "USD"), configured.getList("currencies.code"));
    var usd = ok(reader, "GET", ROOT + "/denominations?currencyCode=usd", null).jsonPath();
    assertEquals(List.of("BANKNOTE", "COIN"), usd.getList("denominations.type"));
    assertEquals(List.of("vault-note", "vault-coin"), usd.getList("denominations.denominationId"));
    assertEquals(List.of("USD", "USD"), usd.getList("denominations.currencyCode"));
    assertEquals(
        0,
        new java.math.BigDecimal(usd.getString("denominations[1].value"))
            .compareTo(new java.math.BigDecimal("0.01")));
    assertNotNull(usd.getLong("denominations[0].id"));
    assertEquals("AVAILABLE", usd.getString("status"));
    var jpy = ok(reader, "GET", ROOT + "/denominations?currencyCode=JPY", null).jsonPath();
    assertEquals("UNAVAILABLE", jpy.getString("status"));
    assertEquals(List.of(), jpy.getList("denominations"));
    assertTrue(
        ok(reader, "GET", ROOT + "/context", null)
            .jsonPath()
            .getList("readiness.limitations")
            .contains("DENOMINATIONS_UNAVAILABLE:JPY"));
  }

  @Test
  void queryErrorsAndUnknownVaultHaveStructuredPlatformResponses() {
    for (String query :
        List.of(
            "currencyCode=ZZZ",
            "currencyCode=123",
            "currencyCode=US",
            "currencyCode=",
            "officeId=-1&currencyCode=USD",
            "officeId=abc&currencyCode=USD",
            "officeId=9223372036854775808&currencyCode=USD")) {
      Response r = call(reader, "GET", ROOT + "/denominations?" + query, null, "default");
      assertEquals(400, r.statusCode(), r::asString);
      assertNotNull(r.jsonPath().getString("developerMessage"));
      assertFalse(r.jsonPath().getList("errors").isEmpty());
    }
    Response missing =
        call(reader, "GET", ROOT + "/context?officeId=9223372036854775807", null, "default");
    assertEquals(404, missing.statusCode());
    assertNotNull(missing.jsonPath().getString("developerMessage"));
  }

  @Test
  void missingInventoryIsUnavailableAndDoesNotFabricateAnOpeningBalance() {
    var result = ok(reader, "GET", ROOT + "/inventory?currencyCode=USD", null).jsonPath();
    assertEquals("UNAVAILABLE", result.getString("status"));
    assertEquals(List.of(), result.getList("balances"));
  }

  @Test
  void malformedConfigurationReturnsSanitizedStructuredServerError() throws Exception {
    sql(
        database,
        "UPDATE m_service_payment_denomination SET denomination_type='INVALID' WHERE"
            + " identifier='vault-note' AND currency_code='USD'");
    try {
      var response = call(reader, "GET", ROOT + "/denominations?currencyCode=USD", null, "default");
      assertEquals(500, response.statusCode());
      assertNotNull(response.jsonPath().getString("developerMessage"));
      assertFalse(response.asString().contains("SELECT"));
      assertFalse(response.asString().contains("org.postgresql"));
      assertFalse(response.asString().contains("m_service_payment_denomination"));
    } finally {
      sql(
          database,
          "UPDATE m_service_payment_denomination SET denomination_type='BILL' WHERE"
              + " identifier='vault-note' AND currency_code='USD'");
    }
  }

  @Test
  void tenantRoutingKeepsIdenticalOfficeIdsAndCurrencyCatalogsIsolated() {
    Response other = call("mifos", "GET", ROOT + "/context", null, "web1328-secondary");
    assertEquals(200, other.statusCode(), other::asString);
    assertEquals("WEB1328 Secondary Tenant", other.jsonPath().getString("branch.name"));
    assertEquals(List.of("EUR"), other.jsonPath().getList("supportedCurrencies.code"));
    assertEquals(
        400,
        call("mifos", "GET", ROOT + "/denominations?currencyCode=USD", null, "web1328-secondary")
            .statusCode());
    Response denied = call(reader, "GET", ROOT + "/context", null, "web1328-secondary");
    assertEquals(401, denied.statusCode());
    assertEquals(
        List.of("JPY", "USD"),
        ok("mifos", "GET", ROOT + "/currencies", null).jsonPath().getList("currencies.code"));
  }

  @Test
  void existingBaseTellerReadRoutesStillWork() {
    for (String route :
        List.of(
            "/cash-inventory/context",
            "/cash-inventory?custodianKey=VAULT:1&transactionType=CASH&currencyCode=USD",
            "/cash-allocations/context?officeId=1",
            "/cash-exchanges/context",
            "/transaction-history/context")) {
      ok("mifos", "GET", "/v2/base-teller" + route, null);
    }
    // WEB-1264 requires an opened, funded drawer. Admin without a drawer remains rejected.
    var fx = call("mifos", "GET", "/v2/base-teller/foreign-currency/context", null, "default");
    assertEquals(403, fx.statusCode());
    assertEquals(
        "error.msg.base.teller.fx.cashier.required",
        fx.jsonPath().getString("errors[0].userMessageGlobalisationCode"));
  }

  private static Connection connect(String property) throws Exception {
    String url = System.getProperty(property);
    assertNotNull(url, property + " must target a disposable web1328_* database");
    Connection c =
        DriverManager.getConnection(
            url,
            System.getProperty("vault.it.dbUser", System.getProperty("user.name")),
            System.getProperty("vault.it.dbPassword", ""));
    try (var s = c.createStatement();
        var r = s.executeQuery("SELECT current_database()")) {
      r.next();
      if (!r.getString(1).startsWith("web1328_") && !r.getString(1).startsWith("web1328p3_")) {
        c.close();
        throw new IllegalStateException(
            "Refusing fixture writes outside a disposable web1328_* or web1328p3_* database");
      }
    }
    return c;
  }

  private static void sql(Connection c, String statement) throws Exception {
    try (var s = c.createStatement()) {
      s.execute(statement);
    }
  }

  private static Map<String, String> financialSnapshot(Connection c) throws Exception {
    var result = new LinkedHashMap<String, String>();
    for (String table : FINANCIAL_TABLES) {
      try (var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT count(*)||':'||md5(COALESCE(string_agg(row_to_json(t)::text,'' ORDER BY"
                      + " row_to_json(t)::text),'')) FROM "
                      + table
                      + " t")) {
        r.next();
        result.put(table, r.getString(1));
      }
    }
    assertTrue(result.containsKey("m_cash_allocation"));
    assertTrue(result.containsKey("acc_gl_journal_entry"));
    return result;
  }

  private static long post(String path, Object body) {
    return ok("mifos", "POST", path, body).jsonPath().getLong("resourceId");
  }

  private static void user(String name, long office, long role) {
    post(
        "/v1/users",
        Map.of(
            "username",
            name,
            "firstname",
            "Vault",
            "lastname",
            "Reader",
            "email",
            name + "@example.test",
            "officeId",
            office,
            "roles",
            List.of(role),
            "sendPasswordToEmail",
            false,
            "password",
            SECRET,
            "repeatPassword",
            SECRET,
            "passwordNeverExpires",
            true));
  }

  private static Response ok(String user, String method, String path, Object body) {
    Response r = call(user, method, path, body, "default");
    assertEquals(200, r.statusCode(), () -> path + ": " + r.asString());
    return r;
  }

  private static Response call(
      String user, String method, String path, Object body, String tenant) {
    var request =
        given()
            .relaxedHTTPSValidation()
            .baseUri("https://localhost")
            .port(Integer.getInteger("vault.it.port", 18443))
            .contentType("application/json")
            .accept("application/json")
            .header("Fineract-Platform-TenantId", tenant);
    if (user != null)
      request.header(
          "Authorization",
          SavingsTestUtils.basicAuthHeader(user, user.equals("mifos") ? "password" : SECRET));
    if (body != null) request.body(GSON.toJson(body));
    Response response = request.request(method, "/fineract-provider/api" + path);
    // Record no credentials, tokens or user-response bodies.
    evidence.add(
        Map.of(
            "method",
            method,
            "endpoint",
            path,
            "tenant",
            tenant,
            "caller",
            user == null ? "anonymous" : user.equals("mifos") ? "admin" : "synthetic-operator",
            "status",
            response.statusCode()));
    return response;
  }
}
