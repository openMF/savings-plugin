/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real authenticated HTTP and persisted financial state; no mocked services or responses. */
class VaultDepositPreviewHttpIntegrationTest {
  private static final String ROOT = "/v2/base-teller/vault-cash-deposits/preview";
  private static final Gson GSON = new Gson();
  private static final String SECRET = "Preview-fixture-1328!";
  private static final List<Map<String, Object>> evidence = new ArrayList<>();
  private static final List<Map<String, Object>> reconciliation = new ArrayList<>();
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
  private static VaultPreviewTestRuntime runtime;
  private static String date, reader, forbidden, assigned;
  private static long office, sibling, cashier;
  private Map<String, String> before, secondaryBefore;
  private String vaultBefore, drawerBefore;

  @BeforeAll
  static void fixtures() throws Exception {
    runtime = new VaultPreviewTestRuntime();
    String unique = UUID.randomUUID().toString().substring(0, 8);
    date =
        ok("mifos", "GET", "/v2/base-teller/vault/context", null)
            .jsonPath()
            .getString("businessDate");
    office =
        post(
            "/v1/offices",
            Map.of(
                "name",
                "Preview A " + unique,
                "parentId",
                1,
                "openingDate",
                date,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    sibling =
        post(
            "/v1/offices",
            Map.of(
                "name",
                "Preview B " + unique,
                "parentId",
                1,
                "openingDate",
                date,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    long role =
        post(
            "/v1/roles",
            Map.of("name", "Preview reader " + unique, "description", "Read only vault preview"));
    long noRole =
        post("/v1/roles", Map.of("name", "No preview " + unique, "description", "No vault access"));
    ok(
        "mifos",
        "PUT",
        "/v1/roles/" + role + "/permissions",
        Map.of("permissions", Map.of(VaultReadPlatformService.PERMISSION, true)));
    reader = "preview-reader-" + unique;
    forbidden = "preview-denied-" + unique;
    assigned = "preview-cashier-" + unique;
    user(reader, role, null);
    user(forbidden, noRole, null);
    ok("mifos", "PUT", "/v1/currencies", Map.of("currencies", List.of("USD", "JPY")));
    sql(runtime.primary(), "DELETE FROM m_service_payment_denomination");
    sql(
        runtime.primary(),
        "INSERT INTO"
            + " m_service_payment_denomination(identifier,currency_code,value,denomination_type,active)"
            + " VALUES"
            + "('note','USD',100,'BILL',true),('coin','USD',0.01,'COIN',true),('five','USD',0.05,'COIN',true),"
            + "('ten','USD',0.10,'COIN',true),('unit','USD',1,'COIN',true),('inactive','USD',50,'BANKNOTE',false),('yen','JPY',1000,'BANKNOTE',true)");
    long staff =
        post(
            "/v1/staff",
            Map.of(
                "officeId",
                office,
                "firstname",
                "Preview",
                "lastname",
                unique,
                "isLoanOfficer",
                false,
                "isActive",
                true));
    user(assigned, role, staff);
    long teller =
        post(
            "/v1/tellers",
            Map.of(
                "officeId",
                office,
                "name",
                "Preview " + unique,
                "startDate",
                date,
                "endDate",
                java.time.LocalDate.parse(date).plusDays(30).toString(),
                "status",
                300,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    post(
        "/v1/tellers/" + teller + "/cashiers",
        Map.of(
            "staffId",
            staff,
            "startDate",
            date,
            "endDate",
            java.time.LocalDate.parse(date).plusDays(30).toString(),
            "isFullDay",
            true,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"));
    cashier =
        ok("mifos", "GET", "/v1/tellers/" + teller + "/cashiers", null)
            .jsonPath()
            .getLong("cashiers.find { it.staffId == " + staff + " }.id");
    // Seed nonzero balances only via established allocation APIs inside disposable fixture setup.
    // Previews run after these postings and must preserve their exact financial records.
    for (int activity : List.of(101, 102, 300)) {
      long gl =
          post(
              "/v1/glaccounts",
              Map.of(
                  "name",
                  "Preview fixture " + activity + " " + unique,
                  "glCode",
                  "VP" + unique + activity,
                  "type",
                  activity == 300 ? 3 : 1,
                  "usage",
                  1,
                  "manualEntriesAllowed",
                  true));
      post(
          "/v1/financialactivityaccounts",
          Map.of("financialActivityId", activity, "glAccountId", gl));
    }
    allocate("SAFE_VAULT_OPENING", 20, null);
    allocate("HEAD_CASHIER_ALLOCATION", 5, cashier);
    sql(
        runtime.secondary(),
        "UPDATE m_office SET name='Preview isolated second tenant' WHERE id=1");
    assertEquals(
        200,
        call(
                "mifos",
                "PUT",
                "/v1/currencies",
                Map.of("currencies", List.of("EUR")),
                "vault-preview-secondary")
            .statusCode());
    assertMoney("1500", vaultCash());
    assertMoney("500", drawerCash());
  }

  @BeforeEach
  void recordFinancialState() throws Exception {
    before = snapshot(runtime.primary());
    secondaryBefore = snapshot(runtime.secondary());
    vaultBefore = vaultCash();
    drawerBefore = drawerCash();
  }

  @AfterEach
  void proveNoMutation(TestInfo test) throws Exception {
    var after = snapshot(runtime.primary());
    var secondaryAfter = snapshot(runtime.secondary());
    assertEquals(before, after, "Preview changed default-tenant financial state");
    assertEquals(secondaryBefore, secondaryAfter, "Preview changed second-tenant financial state");
    assertEquals(vaultBefore, vaultCash());
    assertEquals(drawerBefore, drawerCash());
    reconciliation.add(
        Map.of(
            "test",
            test.getDisplayName(),
            "vaultBefore",
            vaultBefore,
            "vaultAfter",
            vaultCash(),
            "drawerBefore",
            drawerBefore,
            "drawerAfter",
            drawerCash(),
            "before",
            before,
            "after",
            after,
            "secondaryBefore",
            secondaryBefore,
            "secondaryAfter",
            secondaryAfter));
  }

  @AfterAll
  static void closeAndWriteEvidence() throws Exception {
    try {
      Files.createDirectories(Path.of("target"));
      Files.writeString(Path.of("target/vault-preview-http-evidence.json"), GSON.toJson(evidence));
      Files.writeString(
          Path.of("target/vault-preview-reconciliation.json"), GSON.toJson(reconciliation));
    } finally {
      if (runtime != null) runtime.close();
    }
  }

  @Test
  void exactPersistedDenominationsPopulateGeneralTransactionAndPolicyBlockers() {
    var r = preview(reader, body());
    assertEquals(200, r.statusCode(), r::asString);
    var j = r.jsonPath();
    assertEquals("VALID_PREVIEW", j.getString("validationStatus"));
    assertEquals("PROPOSED", j.getString("movementStatus"));
    assertEquals("VAULT_CASH_DEPOSIT", j.getString("operationType"));
    assertEquals("VAULT:" + office, j.getString("vault.key"));
    assertMoney("200", j.getString("currencies[0].banknotesTotal"));
    assertMoney("0.03", j.getString("currencies[0].coinsTotal"));
    assertMoney("200.03", j.getString("currencies[0].totalCash"));
    assertMoney("200.03", j.getString("currencies[0].generalTransaction.inflows.cash"));
    assertMoney("0", j.getString("currencies[0].generalTransaction.outflows.cash"));
    assertFalse(j.getBoolean("currencies[0].generalTransaction.inflows.checks.supported"));
    assertNull(j.get("currencies[0].generalTransaction.outflows.vouchers.amount"));
    assertFalse(j.getBoolean("executionReadiness.executionAllowed"));
    assertEquals("BLOCKED_BY_POLICY", j.getString("executionReadiness.status"));
    assertTrue(
        j.getList("executionReadiness.blockers.code")
            .containsAll(
                List.of(
                    "SOURCE_POLICY_UNAVAILABLE",
                    "ACCOUNTING_POLICY_UNAVAILABLE",
                    "EXECUTION_NOT_IMPLEMENTED")));
    assertFalse(r.asString().contains("COMPLETED"));
    assertNull(j.get("transactionId"));
    assertNull(j.get("receiptId"));
  }

  @Test
  void allConfiguredCoinValuesAndBanknotesAreCalculatedExactly() {
    var b = body();
    var currencies = b.getAsJsonArray("currencies");
    var usd = currencies.get(0).getAsJsonObject();
    usd.getAsJsonArray("denominations").add(row("five", 2));
    usd.getAsJsonArray("denominations").add(row("ten", 3));
    usd.getAsJsonArray("denominations").add(row("unit", 1));
    var r = preview(reader, b);
    assertEquals(200, r.statusCode(), r::asString);
    assertMoney("201.43", r.jsonPath().getString("currencies[0].totalCash"));
  }

  @Test
  void multiCurrencyPreviewHasNoCrossCurrencyTotalOrConversion() {
    var b = body();
    b.getAsJsonArray("currencies").add(currency("JPY", row("yen", 2)));
    var r = preview(reader, b);
    assertEquals(200, r.statusCode(), r::asString);
    assertEquals(List.of("JPY", "USD"), r.jsonPath().getList("currencies.currencyCode"));
    assertMoney("2000", r.jsonPath().getString("currencies[0].totalCash"));
    assertMoney("200.03", r.jsonPath().getString("currencies[1].totalCash"));
    assertNull(r.jsonPath().get("totalCash"));
    assertNull(r.jsonPath().get("exchangeRate"));
  }

  @Test
  void repeatedIdenticalPreviewsAreStableAndOnlyEchoCorrelation() {
    var b = body();
    b.addProperty("correlationReference", "ui-preview-1");
    var first = preview(reader, b);
    var second = preview(reader, b);
    assertEquals(200, first.statusCode());
    assertEquals(200, second.statusCode());
    assertEquals(first.asString(), second.asString());
    assertEquals("ui-preview-1", first.jsonPath().getString("correlationReference"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"-1", "1.5", "1.0", "null", "\"1\"", "true", "1000000001", "9223372036854775808"})
  void invalidQuantityRejectedWithFieldErrors(String q) {
    var b = body();
    b.getAsJsonArray("currencies")
        .get(0)
        .getAsJsonObject()
        .getAsJsonArray("denominations")
        .get(0)
        .getAsJsonObject()
        .add("quantity", JsonParser.parseString(q));
    var r = preview(reader, b);
    assertEquals(400, r.statusCode(), r::asString);
    assertNotNull(r.jsonPath().get("errors[0].parameterName"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "inactive", "yen"})
  void unknownInactiveAndOtherCurrencyDenominationsAreRejected(String id) {
    var b = body();
    b.getAsJsonArray("currencies")
        .get(0)
        .getAsJsonObject()
        .getAsJsonArray("denominations")
        .get(0)
        .getAsJsonObject()
        .addProperty("denominationId", id);
    var r = preview(reader, b);
    assertEquals(400, r.statusCode());
    assertTrue(
        r.jsonPath()
            .getString("errors[0].userMessageGlobalisationCode")
            .contains("denomination.unsupported"));
  }

  @Test
  void duplicatesEmptyAndAllZeroSelectionsAreRejected() {
    var b = body();
    var rows =
        b.getAsJsonArray("currencies").get(0).getAsJsonObject().getAsJsonArray("denominations");
    rows.add(row(" NOTE ", 1));
    assertEquals(400, preview(reader, b).statusCode());
    rows.remove(2);
    rows.get(0).getAsJsonObject().addProperty("quantity", 0);
    rows.get(1).getAsJsonObject().addProperty("quantity", 0);
    assertEquals(400, preview(reader, b).statusCode());
    rows.remove(1);
    rows.remove(0);
    assertEquals(400, preview(reader, b).statusCode());
  }

  @Test
  void authenticationPermissionsBranchCustodyAndMissingVaultAreEnforced() {
    assertEquals(401, preview(null, body()).statusCode());
    assertEquals(403, preview(forbidden, body()).statusCode());
    assertEquals(403, preview(assigned, body()).statusCode());
    var b = body();
    b.addProperty("officeId", sibling);
    assertEquals(403, preview(reader, b).statusCode());
    b.addProperty("officeId", Long.MAX_VALUE);
    assertEquals(404, preview(reader, b).statusCode());
  }

  @Test
  void datesCurrenciesAndClientOverridesAreValidated() {
    var b = body();
    b.addProperty("businessDate", java.time.LocalDate.parse(date).minusDays(1).toString());
    assertEquals(400, preview(reader, b).statusCode());
    b = body();
    b.getAsJsonArray("currencies").get(0).getAsJsonObject().addProperty("currencyCode", "EUR");
    assertEquals(400, preview(reader, b).statusCode());
    for (String field :
        List.of("amount", "userId", "sourceCashierId", "glAccountId", "checks", "vouchers")) {
      b = body();
      b.addProperty(field, 1);
      assertEquals(400, preview(reader, b).statusCode());
    }
    b = body();
    b.getAsJsonArray("currencies")
        .get(0)
        .getAsJsonObject()
        .getAsJsonArray("denominations")
        .get(0)
        .getAsJsonObject()
        .addProperty("value", 0.01);
    assertEquals(400, preview(reader, b).statusCode());
  }

  @Test
  void tenantCredentialsAndConfigurationAreIsolated() {
    assertEquals(401, call(reader, "POST", ROOT, body(), "vault-preview-secondary").statusCode());
    var b = body();
    b.addProperty("officeId", 1);
    var denied = call("mifos", "POST", ROOT, b, "vault-preview-secondary");
    assertEquals(400, denied.statusCode());
    var context =
        call("mifos", "GET", "/v2/base-teller/vault/context", null, "vault-preview-secondary");
    assertEquals(200, context.statusCode());
    assertEquals(List.of("EUR"), context.jsonPath().getList("supportedCurrencies.code"));
  }

  @Test
  void overprecisionConfigurationFailsWithoutRoundingOrLeakingInternals() throws Exception {
    sql(
        runtime.primary(),
        "UPDATE m_service_payment_denomination SET value=0.001 WHERE identifier='coin' AND"
            + " currency_code='USD'");
    try {
      var r = preview(reader, body());
      assertEquals(500, r.statusCode());
      assertNotNull(r.jsonPath().get("developerMessage"));
      assertFalse(r.asString().contains("SELECT"));
      assertFalse(r.asString().contains("org.postgresql"));
    } finally {
      sql(
          runtime.primary(),
          "UPDATE m_service_payment_denomination SET value=0.01 WHERE identifier='coin' AND"
              + " currency_code='USD'");
    }
  }

  @Test
  void phaseOneRoutesRemainAvailableAndPreviewPermissionCannotExecute() {
    for (String path :
        List.of(
            "/v2/base-teller/vault/context",
            "/v2/base-teller/vault/currencies",
            "/v2/base-teller/vault/denominations?currencyCode=USD",
            "/v2/base-teller/vault/inventory?currencyCode=USD"))
      assertEquals(200, ok(reader, "GET", path, null).statusCode());
    assertTrue(
        ok(reader, "GET", "/v2/base-teller/vault/context", null)
            .jsonPath()
            .getBoolean("capabilities.previewDeposit"));
    assertFalse(
        ok(reader, "GET", "/v2/base-teller/vault/context", null)
            .jsonPath()
            .getBoolean("capabilities.executeDeposit"));
    assertEquals(
        403,
        call(reader, "POST", "/v2/base-teller/vault-cash-deposits", body(), "default")
            .statusCode());
  }

  private static Response preview(String user, JsonObject body) {
    return call(user, "POST", ROOT, body, "default");
  }

  private static JsonObject body() {
    var b = new JsonObject();
    b.addProperty("officeId", office);
    b.addProperty("businessDate", date);
    var c = new JsonArray();
    c.add(currency("USD", row("note", 2), row("coin", 3)));
    b.add("currencies", c);
    return b;
  }

  private static JsonObject currency(String code, JsonObject... rows) {
    var c = new JsonObject();
    c.addProperty("currencyCode", code);
    var d = new JsonArray();
    for (var r : rows) d.add(r);
    c.add("denominations", d);
    return c;
  }

  private static JsonObject row(String id, long q) {
    var r = new JsonObject();
    r.addProperty("denominationId", id);
    r.addProperty("quantity", q);
    return r;
  }

  private static void allocate(String type, int count, Long destination) {
    var b = new HashMap<String, Object>();
    b.put("operationType", type);
    b.put("officeId", office);
    b.put("businessDate", date);
    b.put("currencyCode", "USD");
    b.put("amount", count * 100);
    b.put("idempotencyKey", UUID.randomUUID().toString());
    b.put("denominations", List.of(Map.of("denominationId", "note", "quantity", count)));
    b.put("destinationCashierId", destination);
    ok("mifos", "POST", "/v2/base-teller/cash-allocations", b);
  }

  private static void user(String name, long role, Long staff) {
    var b =
        new HashMap<String, Object>(
            Map.of(
                "username",
                name,
                "firstname",
                "Preview",
                "lastname",
                "Operator",
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
    if (staff != null) b.put("staffId", staff);
    post("/v1/users", b);
  }

  private static long post(String path, Object b) {
    return ok("mifos", "POST", path, b).jsonPath().getLong("resourceId");
  }

  private static Response ok(String user, String method, String path, Object b) {
    var r = call(user, method, path, b, "default");
    assertEquals(200, r.statusCode(), () -> path + ": " + r.asString());
    return r;
  }

  private static Response call(
      String user, String method, String path, Object body, String tenant) {
    var request =
        given()
            .relaxedHTTPSValidation()
            .baseUri(runtime.baseUrl())
            .contentType("application/json")
            .accept("application/json")
            .header("Fineract-Platform-TenantId", tenant);
    if (user != null)
      request.header(
          "Authorization",
          SavingsTestUtils.basicAuthHeader(user, user.equals("mifos") ? "password" : SECRET));
    if (body != null) request.body(body instanceof String ? (String) body : GSON.toJson(body));
    var r = request.request(method, "/fineract-provider/api" + path);
    if (path.equals(ROOT)) {
      var entry = new LinkedHashMap<String, Object>();
      entry.put("method", method);
      entry.put("endpoint", path);
      entry.put("baseUrl", runtime.baseUrl());
      entry.put("status", r.statusCode());
      entry.put(
          "validationResult",
          r.statusCode() == 200 ? r.jsonPath().getString("validationStatus") : "REJECTED");
      if (r.statusCode() == 200) {
        entry.put("amounts", r.jsonPath().getList("currencies.totalCash"));
        entry.put(
            "executionAllowed", r.jsonPath().getBoolean("executionReadiness.executionAllowed"));
      } else
        entry.put("errorCode", r.jsonPath().getString("errors[0].userMessageGlobalisationCode"));
      evidence.add(entry);
    }
    return r;
  }

  private static void sql(Connection c, String sql) throws Exception {
    try (var s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private static Map<String, String> snapshot(Connection c) throws Exception {
    var result = new LinkedHashMap<String, String>();
    for (String table : FINANCIAL_TABLES)
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
    return result;
  }

  private static String vaultCash() {
    return ok(reader, "GET", "/v2/base-teller/vault/inventory?currencyCode=USD", null)
        .jsonPath()
        .getString("balances[0].balance");
  }

  private static String drawerCash() {
    return ok(
            "mifos",
            "GET",
            "/v2/base-teller/cash-inventory?custodianKey=TELLER:"
                + cashier
                + "&transactionType=CASH&currencyCode=USD",
            null)
        .jsonPath()
        .getString("[0].balance");
  }

  private static void assertMoney(String expected, String actual) {
    assertNotNull(actual);
    assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual)));
  }
}
