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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual Fineract authentication, routing, immutable database records and GET reconciliation. */
class VaultDepositHistoryHttpIntegrationTest {
  private static final String ROOT = "/v2/base-teller/vault-cash-deposits";
  private static final String SECRET = "History-fixture-1328!";
  private static final Gson GSON = new Gson();
  private static final List<Map<String, Object>> EVIDENCE = new ArrayList<>();
  private static final List<Map<String, Object>> RECONCILIATION = new ArrayList<>();
  private static VaultPreviewTestRuntime runtime;
  private static long branch, sibling;
  private static String operator, outsider, inventoryOnly, historyOnly, date, usd, jpy, multi;
  private Map<String, String> before, secondaryBefore;

  @BeforeAll
  static void fixtures() throws Exception {
    runtime = new VaultPreviewTestRuntime();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    date =
        ok("mifos", "GET", "/v2/base-teller/vault/context", null)
            .jsonPath()
            .getString("businessDate");
    branch = office("History A " + suffix);
    sibling = office("History B " + suffix);
    long read =
        role(
            "History reader " + suffix,
            Map.of(
                VaultReadPlatformService.PERMISSION,
                true,
                VaultDepositHistoryService.PERMISSION,
                true,
                VaultDepositExecutionService.PERMISSION,
                true));
    long inventory =
        role("History inventory only " + suffix, Map.of(VaultReadPlatformService.PERMISSION, true));
    long history =
        role("History only " + suffix, Map.of(VaultDepositHistoryService.PERMISSION, true));
    operator = "vh-operator-" + suffix;
    outsider = "vh-outside-" + suffix;
    inventoryOnly = "vh-inventory-" + suffix;
    historyOnly = "vh-history-" + suffix;
    user(operator, read, branch);
    user(outsider, read, sibling);
    user(inventoryOnly, inventory, branch);
    user(historyOnly, history, branch);
    ok("mifos", "PUT", "/v1/currencies", Map.of("currencies", List.of("USD", "JPY")));
    // Isolated catalog setup, matching the other vault suites. Existing snapshots do not depend on
    // it.
    sql(runtime.primary(), "DELETE FROM m_service_payment_denomination");
    sql(
        runtime.primary(),
        "INSERT INTO"
            + " m_service_payment_denomination(identifier,currency_code,value,denomination_type,active)"
            + " VALUES"
            + " ('vh-note','USD',100,'BILL',true),('vh-coin','USD',0.01,'COIN',true),('vh-yen','JPY',1000,'BILL',true)"
            + " ON CONFLICT(currency_code,identifier) DO NOTHING");
    // Every history fixture is a genuine Phase 3 blocked execution; no financial rows are seeded.
    usd = attempt("USD");
    jpy = attempt("JPY");
    multi = attempt("USD", "JPY");
  }

  @BeforeEach
  void capture() throws Exception {
    before = snapshot(runtime.primary());
    secondaryBefore = snapshot(runtime.secondary());
  }

  @AfterEach
  void reconcile(TestInfo test) throws Exception {
    var after = snapshot(runtime.primary());
    var secondaryAfter = snapshot(runtime.secondary());
    assertEquals(
        before, after, "GET must not change inventory, balances, attempts, approvals or journals");
    assertEquals(secondaryBefore, secondaryAfter, "GET must not change another tenant");
    RECONCILIATION.add(
        Map.of(
            "test",
            test.getDisplayName(),
            "nonMutationOutcome",
            "PASS",
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
  static void finish() throws Exception {
    Files.writeString(Path.of("target/vault-history-http-evidence.json"), GSON.toJson(EVIDENCE));
    Files.writeString(
        Path.of("target/vault-history-reconciliation.json"), GSON.toJson(RECONCILIATION));
    if (runtime != null) runtime.close();
  }

  @Test
  void historyDetailsAuditAndReceiptUseSameAuthoritativeAttempt() throws Exception {
    var page = get(operator, "/history?reference=" + usd, 200);
    assertEquals(1, page.jsonPath().getLong("totalFilteredRecords"));
    assertEquals("DEPOSIT_ATTEMPT", page.jsonPath().getString("items[0].recordKind"));
    assertEquals("BLOCKED", page.jsonPath().getString("items[0].status"));
    assertEquals("200.03", page.jsonPath().getString("items[0].currencies[0].proposedCash"));
    var detail = get(operator, "/" + usd, 200);
    assertEquals(usd, detail.jsonPath().getString("attempt.attemptReference"));
    assertEquals("200.03", detail.jsonPath().getString("attempt.currencies[0].totalCash"));
    assertEquals(3, detail.jsonPath().getInt("attempt.currencies[0].coins[0].quantity"));
    assertNull(detail.jsonPath().getString("attempt.transactionId"));
    assertFalse(detail.jsonPath().getBoolean("receiptEligibility.eligible"));
    assertEquals(
        List.of("VIEW_DETAILS", "VIEW_AUDIT"), detail.jsonPath().getList("permittedActions"));
    var audit = get(operator, "/" + usd + "/audit", 200);
    assertEquals(1, audit.jsonPath().getList("events").size());
    assertEquals(usd, audit.jsonPath().getString("events[0].eventId"));
    assertEquals("BLOCKED_ATTEMPT_RECORDED", audit.jsonPath().getString("events[0].eventType"));
    assertEquals(
        detail.jsonPath().getLong("attempt.initiatedBy"),
        audit.jsonPath().getLong("events[0].actorId"));
    assertEquals(
        detail.jsonPath().getString("attempt.createdAt"),
        audit.jsonPath().getString("events[0].timestamp"));
    assertTrue(
        get(operator, "/" + usd + "/receipt", 409).asString().contains("receipt.not_posted"));
    try (var s =
        runtime
            .primary()
            .prepareStatement(
                "SELECT snapshot FROM m_vault_deposit_attempt WHERE attempt_reference=?")) {
      s.setString(1, usd);
      try (var rs = s.executeQuery()) {
        assertTrue(rs.next());
        var stored = JsonParser.parseString(rs.getString(1)).getAsJsonObject();
        assertEquals(
            stored, JsonParser.parseString(detail.asString()).getAsJsonObject().get("attempt"));
      }
    }
  }

  @Test
  void filtersAndPaginationAreDatabaseScopedAndStable() {
    var all = get(operator, "/history", 200);
    assertEquals(3, all.jsonPath().getLong("totalFilteredRecords"));
    assertEquals(List.of(multi, jpy, usd), all.jsonPath().getList("items.attemptReference"));
    var first = get(operator, "/history?limit=1", 200);
    var second = get(operator, "/history?limit=1&offset=1", 200);
    assertEquals(multi, first.jsonPath().getString("items[0].attemptReference"));
    assertEquals(jpy, second.jsonPath().getString("items[0].attemptReference"));
    assertEquals(3, second.jsonPath().getLong("totalFilteredRecords"));
    assertEquals(
        List.of(multi, usd),
        get(operator, "/history?currencyCode=usd", 200)
            .jsonPath()
            .getList("items.attemptReference"));
    assertEquals(
        List.of(usd),
        get(operator, "/history?reference=" + usd, 200)
            .jsonPath()
            .getList("items.attemptReference"));
    for (String filter :
        List.of(
            "officeId=" + branch,
            "vaultKey=VAULT:" + branch,
            "status=BLOCKED",
            "fromDate=" + date + "&toDate=" + date))
      assertEquals(
          3, get(operator, "/history?" + filter, 200).jsonPath().getLong("totalFilteredRecords"));
    for (String filter : List.of("currencyCode=EUR", "toDate=2000-01-01", "offset=99"))
      assertTrue(get(operator, "/history?" + filter, 200).jsonPath().getList("items").isEmpty());
    assertEquals(
        "JPY",
        get(operator, "/" + jpy, 200).jsonPath().getString("attempt.currencies[0].currencyCode"));
    assertEquals(
        "1000",
        get(operator, "/" + jpy, 200).jsonPath().getString("attempt.currencies[0].totalCash"));
    var currencies = get(operator, "/" + multi, 200).jsonPath();
    assertEquals(List.of("JPY", "USD"), currencies.getList("attempt.currencies.currencyCode"));
    assertEquals("1000", currencies.getString("attempt.currencies[0].totalCash"));
    assertEquals("200.03", currencies.getString("attempt.currencies[1].totalCash"));
  }

  @Test
  void authenticationPermissionsBranchAndTenantIsolationApplyToEveryRoute() {
    for (String suffix :
        List.of("/history", "/" + usd, "/" + usd + "/audit", "/" + usd + "/receipt")) {
      get(null, suffix, 401);
      get(inventoryOnly, suffix, 403);
      get(historyOnly, suffix, 403);
      if (!suffix.equals("/history")) {
        get(outsider, suffix, 404);
        expect(call("mifos", "GET", ROOT + suffix, null, "vault-preview-secondary"), 404);
      }
    }
    assertTrue(get(outsider, "/history", 200).jsonPath().getList("items").isEmpty());
    get(operator, "/history?officeId=" + sibling, 403);
    get(operator, "/history?vaultKey=VAULT:" + sibling, 403);
    assertTrue(
        expect(
                call(
                    "mifos",
                    "GET",
                    ROOT + "/history?reference=" + usd,
                    null,
                    "vault-preview-secondary"),
                200)
            .jsonPath()
            .getList("items")
            .isEmpty());
    expect(call("mifos", "GET", ROOT + "/history", null, "unknown-tenant"), 400);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "limit=0",
        "limit=101",
        "limit=abc",
        "offset=-1",
        "offset=2147483648",
        "currencyCode=US",
        "status=POSTED",
        "status=PENDING_APPROVAL",
        "status=REJECTED",
        "status=FAILED",
        "fromDate=2026-02-30",
        "fromDate=2026-10-29&toDate=2026-10-01",
        "officeId=0",
        "vaultKey=TRANSIT:1",
        "reference=anything"
      })
  void invalidQueriesReturnStructuredErrors(String query) {
    assertTrue(
        get(operator, "/history?" + query, 400).asString().contains("history.filter.invalid"));
  }

  @Test
  void missingAndWrongTypeReferencesHaveNoReceiptOrAudit() {
    for (String ref : List.of("VDA-00000000-0000-0000-0000-000000000000", "SAVINGS_DEPOSIT:1"))
      for (String suffix : List.of("", "/audit", "/receipt"))
        assertTrue(get(operator, "/" + ref + suffix, 404).asString().contains("attempt.not.found"));
  }

  @Test
  void existingFinancialHistoryDoesNotCountBlockedVaultAttemptsAsPostedInflows() {
    var shared =
        expect(
            call(
                "mifos",
                "GET",
                "/v2/base-teller/transaction-history?reference=" + usd,
                null,
                "default"),
            200);
    assertTrue(shared.jsonPath().getList("items").isEmpty());
    assertEquals(0, shared.jsonPath().getLong("totalFilteredRecords"));
    assertEquals(
        1,
        get(operator, "/history?reference=" + usd, 200).jsonPath().getLong("totalFilteredRecords"));
  }

  @Test
  void changingCatalogAndNamesCannotRewriteHistoricalEvidence() throws Exception {
    var original = get(operator, "/" + usd, 200).asString();
    String branchName;
    try (var s = runtime.primary().createStatement();
        var rs = s.executeQuery("SELECT name FROM m_office WHERE id=" + branch)) {
      rs.next();
      branchName = rs.getString(1);
    }
    try {
      sql(
          runtime.primary(),
          "UPDATE m_service_payment_denomination SET value=500,active=false WHERE"
              + " identifier='vh-note'");
      sql(
          runtime.primary(),
          "UPDATE m_office SET name='Renamed history branch' WHERE id=" + branch);
      var changed = snapshot(runtime.primary());
      assertEquals(original, get(operator, "/" + usd, 200).asString());
      assertEquals(
          "200.03",
          get(operator, "/history?reference=" + usd, 200)
              .jsonPath()
              .getString("items[0].currencies[0].proposedCash"));
      get(operator, "/" + usd + "/receipt", 409);
      assertEquals(changed, snapshot(runtime.primary()));
    } finally {
      sql(
          runtime.primary(),
          "UPDATE m_service_payment_denomination SET value=100,active=true WHERE"
              + " identifier='vh-note'");
      try (var s = runtime.primary().prepareStatement("UPDATE m_office SET name=? WHERE id=?")) {
        s.setString(1, branchName);
        s.setLong(2, branch);
        s.executeUpdate();
      }
    }
  }

  private static String attempt(String... codes) {
    var currencies =
        Arrays.stream(codes)
            .map(
                currency ->
                    Map.of(
                        "currencyCode",
                        currency,
                        "denominations",
                        currency.equals("USD")
                            ? List.of(
                                Map.of("denominationId", "vh-note", "quantity", 2),
                                Map.of("denominationId", "vh-coin", "quantity", 3))
                            : List.of(Map.of("denominationId", "vh-yen", "quantity", 1))))
            .toList();
    var body =
        Map.of(
            "vaultKey",
            "VAULT:" + branch,
            "idempotencyKey",
            UUID.randomUUID().toString(),
            "deposit",
            Map.of("officeId", branch, "businessDate", date, "currencies", currencies));
    var response = call(operator, "POST", ROOT, body, "default");
    expect(response, 409);
    assertEquals("BLOCKED", response.jsonPath().getString("status"));
    return response.jsonPath().getString("attemptReference");
  }

  private static long office(String name) {
    return ok(
            "mifos",
            "POST",
            "/v1/offices",
            Map.of(
                "name",
                name,
                "parentId",
                1,
                "openingDate",
                date,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"))
        .jsonPath()
        .getLong("resourceId");
  }

  private static long role(String name, Map<String, Boolean> permissions) {
    long id =
        ok(
                "mifos",
                "POST",
                "/v1/roles",
                Map.of("name", name, "description", "Disposable history verification"))
            .jsonPath()
            .getLong("resourceId");
    ok("mifos", "PUT", "/v1/roles/" + id + "/permissions", Map.of("permissions", permissions));
    return id;
  }

  private static void user(String name, long role, long office) {
    var body =
        new HashMap<String, Object>(
            Map.of(
                "username",
                name,
                "firstname",
                "History",
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
    ok("mifos", "POST", "/v1/users", body);
  }

  private static Response get(String user, String suffix, int status) {
    return expect(call(user, "GET", ROOT + suffix, null, "default"), status);
  }

  private static Response expect(Response response, int status) {
    var entry = EVIDENCE.getLast();
    entry.put("expectedStatus", status);
    entry.put("outcome", status == response.statusCode() ? "PASS" : "FAIL");
    assertEquals(status, response.statusCode(), response::asString);
    return response;
  }

  private static Response ok(String user, String method, String path, Object body) {
    var response = call(user, method, path, body, "default");
    assertEquals(200, response.statusCode(), response::asString);
    return response;
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
    if (body != null) request.body(GSON.toJson(body));
    var response = request.request(method, "/fineract-provider/api" + path);
    if (path.startsWith(ROOT) || path.startsWith("/v2/base-teller/transaction-history"))
      EVIDENCE.add(
          new LinkedHashMap<>(
              Map.of(
                  "method",
                  method,
                  "endpoint",
                  "/fineract-provider/api" + path,
                  "baseUrl",
                  runtime.baseUrl(),
                  "tenant",
                  tenant,
                  "authenticated",
                  user != null,
                  "status",
                  response.statusCode(),
                  "response",
                  JsonParser.parseString(response.asString()),
                  "authorizationResult",
                  switch (response.statusCode()) {
                    case 401 -> "UNAUTHENTICATED";
                    case 403 -> "DENIED";
                    case 404 -> "SCOPED_NOT_FOUND";
                    case 400 -> "INVALID_REQUEST";
                    default -> "AUTHORIZED";
                  })));
    return response;
  }

  private static void sql(Connection connection, String sql) throws Exception {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static Map<String, String> snapshot(Connection connection) throws Exception {
    var tables = new ArrayList<String>();
    try (var s = connection.createStatement();
        var rs =
            s.executeQuery(
                "SELECT tablename FROM pg_tables WHERE schemaname='public' AND (tablename LIKE"
                    + " 'm_cash%' OR tablename LIKE 'm_base_teller%' OR tablename LIKE"
                    + " 'm_service_payment%' OR tablename LIKE 'm_end_of_day%' OR tablename LIKE"
                    + " 'm_fx%' OR tablename LIKE 'm_transaction_reversal%' OR tablename IN"
                    + " ('m_vault_deposit_attempt','acc_gl_journal_entry','m_savings_account','m_savings_account_transaction','m_loan','m_loan_transaction','m_portfolio_command_source'))"
                    + " ORDER BY tablename")) {
      while (rs.next()) tables.add(rs.getString(1));
    }
    var result = new LinkedHashMap<String, String>();
    for (String table : tables)
      try (var s = connection.createStatement();
          var rs =
              s.executeQuery(
                  "SELECT count(*)||':'||md5(COALESCE(string_agg(row_to_json(t)::text,'' ORDER BY"
                      + " row_to_json(t)::text),'')) FROM "
                      + table
                      + " t")) {
        rs.next();
        result.put(table, rs.getString(1));
      }
    return result;
  }
}
