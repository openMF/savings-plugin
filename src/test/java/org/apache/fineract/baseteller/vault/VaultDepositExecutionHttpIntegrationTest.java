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
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real Fineract authentication, tenant routing, migration, SQL locks and immutable attempt
 * evidence.
 */
class VaultDepositExecutionHttpIntegrationTest {
  private static final String ROOT = "/v2/base-teller/vault-cash-deposits";
  private static final String SECRET = "Deposit-fixture-1328!";
  private static final Gson GSON = new Gson();
  private static final List<Map<String, Object>> EVIDENCE =
      Collections.synchronizedList(new ArrayList<>());
  private static final List<Map<String, Object>> RECONCILIATION = new ArrayList<>();
  private static VaultPreviewTestRuntime runtime;
  private static long office, sibling;
  private static String date, operator, other, reader, requestOnly, outside;
  private Map<String, String> before, secondaryBefore;
  private String inventoryBefore;

  @BeforeAll
  static void fixtures() throws Exception {
    runtime = new VaultPreviewTestRuntime();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    date =
        ok("mifos", "GET", "/v2/base-teller/vault/context", null)
            .jsonPath()
            .getString("businessDate");
    office = office("Deposit A " + suffix);
    sibling = office("Deposit B " + suffix);
    long both =
        role(
            "Deposit requester " + suffix,
            Map.of(
                VaultReadPlatformService.PERMISSION,
                true,
                VaultDepositExecutionService.PERMISSION,
                true));
    long read = role("Deposit reader " + suffix, Map.of(VaultReadPlatformService.PERMISSION, true));
    long request =
        role(
            "Deposit request only " + suffix,
            Map.of(VaultDepositExecutionService.PERMISSION, true));
    operator = "vd-operator-" + suffix;
    other = "vd-other-" + suffix;
    reader = "vd-reader-" + suffix;
    requestOnly = "vd-request-" + suffix;
    outside = "vd-outside-" + suffix;
    user(operator, both, office);
    user(other, both, office);
    user(reader, read, office);
    user(requestOnly, request, office);
    user(outside, both, sibling);
    ok("mifos", "PUT", "/v1/currencies", Map.of("currencies", List.of("USD", "JPY")));
    assertEquals(
        200,
        call(
                "mifos",
                "PUT",
                "/v1/currencies",
                Map.of("currencies", List.of("USD", "JPY")),
                "vault-preview-secondary")
            .statusCode());
    for (Connection c : List.of(runtime.primary(), runtime.secondary())) {
      // Synthetic catalog configuration only. No source cash, opening balance or journal is seeded.
      sql(c, "DELETE FROM m_service_payment_denomination");
      sql(
          c,
          "INSERT INTO m_service_payment_denomination(identifier,currency_code,value,denomination_type,active)"
              + " VALUES ('vd-note','USD',100,'BILL',true),('vd-coin','USD',0.01,'COIN',true)");
    }
  }

  @BeforeEach
  void captureBefore() throws Exception {
    before = snapshot(runtime.primary());
    secondaryBefore = snapshot(runtime.secondary());
    inventoryBefore =
        ok(operator, "GET", "/v2/base-teller/vault/inventory?currencyCode=USD", null).asString();
  }

  @AfterEach
  void reconcile(TestInfo info) throws Exception {
    var after = snapshot(runtime.primary());
    var secondaryAfter = snapshot(runtime.secondary());
    assertEquals(before, after, "No financial, custody, denomination or journal row may change");
    assertEquals(
        secondaryBefore, secondaryAfter, "The secondary tenant must have no financial mutations");
    assertEquals(
        inventoryBefore,
        ok(operator, "GET", "/v2/base-teller/vault/inventory?currencyCode=USD", null).asString());
    RECONCILIATION.add(
        Map.of(
            "test",
            info.getDisplayName(),
            "before",
            before,
            "after",
            after,
            "secondaryBefore",
            secondaryBefore,
            "secondaryAfter",
            secondaryAfter,
            "vaultBefore",
            JsonParser.parseString(inventoryBefore),
            "financialMutationCount",
            0));
  }

  @AfterAll
  static void finish() throws Exception {
    Files.writeString(Path.of("target/vault-deposit-http-evidence.json"), GSON.toJson(EVIDENCE));
    Files.writeString(
        Path.of("target/vault-deposit-reconciliation.json"), GSON.toJson(RECONCILIATION));
    if (runtime != null) runtime.close();
  }

  @Test
  void authorizedAttemptIsDurablyBlockedAndCanonicalReplayUsesOneAuditRow() throws Exception {
    var body = body(office);
    String key = body.get("idempotencyKey").getAsString();
    var first = execute(operator, body);
    assertBlocked(first);
    assertEquals("200.03", first.jsonPath().getString("currencies[0].totalCash"));
    String reference = first.jsonPath().getString("attemptReference");
    assertTrue(reference.startsWith("VDA-"));
    assertEquals(1, count(runtime.primary(), key));
    assertEquals(first.asString(), execute(operator, body).asString());
    String canonical = GSON.toJson(body).replace("USD", "usd").replace("vd-note", " VD-NOTE ");
    assertEquals(first.asString(), execute(operator, canonical).asString());
    assertEquals(1, count(runtime.primary(), key));
    try (var s =
        runtime
            .primary()
            .prepareStatement(
                "SELECT status,snapshot FROM m_vault_deposit_attempt WHERE idempotency_key=?")) {
      s.setString(1, key);
      try (var r = s.executeQuery()) {
        assertTrue(r.next());
        assertEquals("BLOCKED", r.getString(1));
        assertEquals(
            reference,
            JsonParser.parseString(r.getString(2))
                .getAsJsonObject()
                .get("attemptReference")
                .getAsString());
      }
    }
  }

  @Test
  void changedPayloadAndDifferentActorConflictWithoutLeakingOriginalOutcome() throws Exception {
    var body = body(office);
    assertBlocked(execute(operator, body));
    var changed = body.deepCopy();
    changed.getAsJsonObject("deposit").addProperty("correlationReference", "changed");
    for (Response result : List.of(execute(operator, changed), execute(other, body))) {
      assertEquals(409, result.statusCode());
      assertTrue(result.asString().contains("idempotency.conflict"));
      assertFalse(result.asString().contains("VDA-"));
    }
    assertEquals(1, count(runtime.primary(), body.get("idempotencyKey").getAsString()));
  }

  @Test
  void authenticationPermissionsAndBranchCustodyAreRevalidated() {
    assertEquals(401, execute(null, body(office)).statusCode());
    assertEquals(403, execute(reader, "not-json").statusCode());
    assertEquals(403, execute(requestOnly, body(office)).statusCode());
    assertEquals(403, execute(outside, body(office)).statusCode());
    assertEquals(403, execute(operator, body(sibling)).statusCode());
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "0.5", "1e2", "1000000001", "9223372036854775808", "0"})
  void invalidQuantitiesNeverPersistAnAttempt(String quantity) throws Exception {
    var body = body(office);
    var rows =
        body.getAsJsonObject("deposit")
            .getAsJsonArray("currencies")
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("denominations");
    rows.get(0).getAsJsonObject().add("quantity", JsonParser.parseString(quantity));
    rows.get(1).getAsJsonObject().addProperty("quantity", 0);
    assertRejectedWithoutAudit(operator, body, 400);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "denomination",
        "duplicate",
        "currency",
        "date",
        "vault",
        "source",
        "approval",
        "account",
        "status",
        "key"
      })
  void invalidOrServerOwnedFieldsCannotBypassGuards(String kind) throws Exception {
    var body = body(office);
    var deposit = body.getAsJsonObject("deposit");
    var currency = deposit.getAsJsonArray("currencies").get(0).getAsJsonObject();
    var rows = currency.getAsJsonArray("denominations");
    switch (kind) {
      case "denomination" -> rows.get(0).getAsJsonObject().addProperty("denominationId", "unknown");
      case "duplicate" -> rows.add(rows.get(0).deepCopy());
      case "currency" -> currency.addProperty("currencyCode", "EUR");
      case "date" -> deposit.addProperty("businessDate", "2000-01-01");
      case "vault" -> body.addProperty("vaultKey", "VAULT:" + sibling);
      case "source" ->
          body.add(
              "source", JsonParser.parseString("{\"type\":\"ARBITRARY\",\"reference\":\"fake\"}"));
      case "approval" -> body.addProperty("approved", true);
      case "account" -> body.addProperty("glAccountId", 1);
      case "status" -> body.addProperty("status", "POSTED");
      case "key" -> body.remove("idempotencyKey");
      default -> fail("Unknown case");
    }
    assertRejectedWithoutAudit(operator, body, 400);
  }

  @Test
  void configurationIsRevalidatedAfterPreviewAndAReplayDoesNotBecomeANewOperation()
      throws Exception {
    var body = body(office);
    assertEquals(
        200,
        call(operator, "POST", ROOT + "/preview", body.get("deposit"), "default").statusCode());
    sql(
        runtime.primary(),
        "UPDATE m_service_payment_denomination SET active=false WHERE identifier='vd-note'");
    try {
      assertRejectedWithoutAudit(operator, body, 400);
    } finally {
      sql(
          runtime.primary(),
          "UPDATE m_service_payment_denomination SET active=true WHERE identifier='vd-note'");
    }
    var first = execute(operator, body);
    assertBlocked(first);
    sql(
        runtime.primary(),
        "UPDATE m_service_payment_denomination SET active=false WHERE identifier='vd-note'");
    try {
      assertEquals(first.asString(), execute(operator, body).asString());
    } finally {
      sql(
          runtime.primary(),
          "UPDATE m_service_payment_denomination SET active=true WHERE identifier='vd-note'");
    }
  }

  @Test
  void concurrentSameKeyRequestsProduceOneImmutableOutcome() throws Exception {
    var body = body(office);
    String key = body.get("idempotencyKey").getAsString();
    try (var workers = Executors.newFixedThreadPool(6)) {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<Response>>();
      for (int i = 0; i < 6; i++)
        futures.add(
            workers.submit(
                () -> {
                  start.await();
                  return execute(operator, body);
                }));
      start.countDown();
      Set<String> references = new HashSet<>();
      for (var future : futures) {
        var r = future.get(45, TimeUnit.SECONDS);
        assertBlocked(r);
        references.add(r.jsonPath().getString("attemptReference"));
      }
      assertEquals(1, references.size());
      assertEquals(1, count(runtime.primary(), key));
    }
  }

  @Test
  void twoOperatorsWithDifferentKeysRemainBlocked() throws Exception {
    try (var workers = Executors.newFixedThreadPool(2)) {
      var a = workers.submit(() -> execute(operator, body(office)));
      var b = workers.submit(() -> execute(other, body(office)));
      assertBlocked(a.get(45, TimeUnit.SECONDS));
      assertBlocked(b.get(45, TimeUnit.SECONDS));
    }
  }

  @Test
  void concurrentDifferentActorsCannotShareAnIdempotencyOutcome() throws Exception {
    var body = body(office);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var a =
          workers.submit(
              () -> {
                start.await();
                return execute(operator, body);
              });
      var b =
          workers.submit(
              () -> {
                start.await();
                return execute(other, body);
              });
      start.countDown();
      var results = List.of(a.get(45, TimeUnit.SECONDS), b.get(45, TimeUnit.SECONDS));
      assertEquals(
          1,
          results.stream().filter(r -> "BLOCKED".equals(r.jsonPath().getString("status"))).count());
      assertEquals(
          1, results.stream().filter(r -> r.asString().contains("idempotency.conflict")).count());
      assertEquals(1, count(runtime.primary(), body.get("idempotencyKey").getAsString()));
    }
  }

  @Test
  void databaseUniquenessProtectsTheKeyIndependentlyOfTheServiceLock() throws Exception {
    var body = body(office);
    assertBlocked(execute(operator, body));
    String key = body.get("idempotencyKey").getAsString();
    try (var statement =
        runtime
            .primary()
            .prepareStatement(
                "INSERT INTO m_vault_deposit_attempt (idempotency_key,request_fingerprint,attempt_reference,"
                    + "status,office_id,initiated_by,business_date,created_at,snapshot)"
                    + " SELECT idempotency_key,request_fingerprint,?,status,office_id,initiated_by,business_date,created_at,snapshot"
                    + " FROM m_vault_deposit_attempt WHERE idempotency_key=?")) {
      statement.setString(1, "VDA-" + UUID.randomUUID());
      statement.setString(2, key);
      assertEquals(
          "23505", assertThrows(SQLException.class, statement::executeUpdate).getSQLState());
    }
    assertEquals(1, count(runtime.primary(), key));
  }

  @Test
  void sameKeyIsIsolatedByTenant() throws Exception {
    var body = body(1);
    String key = body.get("idempotencyKey").getAsString();
    var a = execute("mifos", body);
    var b = call("mifos", "POST", ROOT, body, "vault-preview-secondary");
    assertBlocked(a);
    assertBlocked(b);
    assertNotEquals(
        a.jsonPath().getString("attemptReference"), b.jsonPath().getString("attemptReference"));
    assertEquals(1, count(runtime.primary(), key));
    assertEquals(1, count(runtime.secondary(), key));
  }

  @Test
  void auditEvidenceRejectsUpdateAndDeleteAtDatabaseBoundary() throws Exception {
    var body = body(office);
    assertBlocked(execute(operator, body));
    String key = body.get("idempotencyKey").getAsString();
    for (String statement :
        List.of(
            "UPDATE m_vault_deposit_attempt SET status='BLOCKED'",
            "DELETE FROM m_vault_deposit_attempt"))
      assertThrows(
          SQLException.class,
          () -> sql(runtime.primary(), statement + " WHERE idempotency_key='" + key + "'"));
    assertEquals(1, count(runtime.primary(), key));
  }

  @Test
  void mandatoryAuditFailureRollsBackAndNeverReturnsFinancialSuccess() throws Exception {
    var body = body(office);
    String key = body.get("idempotencyKey").getAsString();
    sql(
        runtime.primary(),
        "CREATE FUNCTION vda_test_fail_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test-scoped audit failure'; END; $$");
    sql(
        runtime.primary(),
        "CREATE TRIGGER vda_test_failure BEFORE INSERT ON m_vault_deposit_attempt FOR EACH ROW EXECUTE FUNCTION vda_test_fail_insert()");
    try {
      var result = execute(operator, body);
      assertEquals(500, result.statusCode());
      assertFalse(result.asString().contains("test-scoped"));
      assertFalse(result.asString().contains("INSERT INTO"));
      assertEquals(0, count(runtime.primary(), key));
    } finally {
      sql(runtime.primary(), "DROP TRIGGER vda_test_failure ON m_vault_deposit_attempt");
      sql(runtime.primary(), "DROP FUNCTION vda_test_fail_insert()");
    }
    assertBlocked(execute(operator, body));
    assertBlocked(execute(operator, body));
    assertEquals(1, count(runtime.primary(), key));
  }

  @Test
  void phaseOneAndTwoStayReadOnlyAndThereIsNoApprovalOrReceiptEndpoint() {
    assertEquals(200, ok(operator, "GET", "/v2/base-teller/vault/context", null).statusCode());
    var preview = ok(operator, "POST", ROOT + "/preview", body(office).get("deposit"));
    assertFalse(preview.jsonPath().getBoolean("executionReadiness.executionAllowed"));
    assertEquals(
        404, call(operator, "POST", ROOT + "/1/approve", Map.of(), "default").statusCode());
    assertEquals(404, call(operator, "GET", ROOT + "/1/receipt", null, "default").statusCode());
  }

  private static void assertBlocked(Response response) {
    assertEquals(409, response.statusCode(), response::asString);
    assertEquals("BLOCKED", response.jsonPath().getString("status"));
    assertEquals("NOT_POSTED", response.jsonPath().getString("accountingStatus"));
    assertFalse(response.jsonPath().getBoolean("executionAllowed"));
    assertNull(response.jsonPath().getString("transactionId"));
    assertTrue(
        response
            .jsonPath()
            .getList("blockers.code")
            .containsAll(
                List.of(
                    "SOURCE_POLICY_UNAVAILABLE",
                    "ACCOUNTING_POLICY_UNAVAILABLE",
                    "VAULT_STATUS_UNKNOWN",
                    "APPROVAL_POLICY_UNAVAILABLE",
                    "POSTING_MODEL_UNAVAILABLE")));
  }

  private static void assertRejectedWithoutAudit(String user, JsonObject body, int status)
      throws Exception {
    assertEquals(status, execute(user, body).statusCode());
    String key = body.has("idempotencyKey") ? body.get("idempotencyKey").getAsString() : "missing";
    assertEquals(0, count(runtime.primary(), key));
  }

  private static JsonObject body(long officeId) {
    return JsonParser.parseString(
            GSON.toJson(
                Map.of(
                    "vaultKey",
                    "VAULT:" + officeId,
                    "idempotencyKey",
                    UUID.randomUUID().toString(),
                    "deposit",
                    Map.of(
                        "officeId",
                        officeId,
                        "businessDate",
                        date,
                        "currencies",
                        List.of(
                            Map.of(
                                "currencyCode",
                                "USD",
                                "denominations",
                                List.of(
                                    Map.of("denominationId", "vd-note", "quantity", 2),
                                    Map.of("denominationId", "vd-coin", "quantity", 3))))))))
        .getAsJsonObject();
  }

  private static long office(String name) {
    return post(
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
            "en"));
  }

  private static long role(String name, Map<String, Boolean> permissions) {
    long id =
        post("/v1/roles", Map.of("name", name, "description", "Synthetic guarded deposit test"));
    ok("mifos", "PUT", "/v1/roles/" + id + "/permissions", Map.of("permissions", permissions));
    return id;
  }

  private static void user(String name, long role, long branch) {
    var body =
        new HashMap<String, Object>(
            Map.of(
                "username",
                name,
                "firstname",
                "Deposit",
                "lastname",
                "Operator",
                "email",
                name + "@example.test",
                "officeId",
                branch,
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
    post("/v1/users", body);
  }

  private static long post(String path, Object body) {
    return ok("mifos", "POST", path, body).jsonPath().getLong("resourceId");
  }

  private static Response execute(String user, Object body) {
    return call(user, "POST", ROOT, body, "default");
  }

  private static Response ok(String user, String method, String path, Object body) {
    var r = call(user, method, path, body, "default");
    assertEquals(200, r.statusCode(), r::asString);
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
    var result = request.request(method, "/fineract-provider/api" + path);
    if (path.equals(ROOT)) {
      var entry = new LinkedHashMap<String, Object>();
      entry.put("method", method);
      entry.put("endpoint", "/fineract-provider/api" + path);
      entry.put("baseUrl", runtime.baseUrl());
      entry.put("httpStatus", result.statusCode());
      entry.put("tenant", tenant);
      entry.put("outcome", JsonParser.parseString(result.asString()));
      entry.put("financialMutations", 0);
      EVIDENCE.add(entry);
    }
    return result;
  }

  private static long count(Connection c, String key) throws Exception {
    try (var s =
        c.prepareStatement(
            "SELECT count(*) FROM m_vault_deposit_attempt WHERE idempotency_key=?")) {
      s.setString(1, key);
      try (var r = s.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }

  private static void sql(Connection c, String sql) throws SQLException {
    try (var s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private static Map<String, String> snapshot(Connection c) throws Exception {
    var tables = new ArrayList<String>();
    try (var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT tablename FROM pg_tables WHERE schemaname='public' AND"
                    + " (tablename LIKE 'm_cash%' OR tablename LIKE 'm_base_teller%' OR tablename LIKE 'm_service_payment%'"
                    + " OR tablename LIKE 'm_end_of_day%' OR tablename LIKE 'm_fx%' OR tablename LIKE 'm_transaction_reversal%'"
                    + " OR tablename IN ('acc_gl_journal_entry','m_savings_account_transaction','m_loan_transaction','m_portfolio_command_source')) ORDER BY tablename")) {
      while (r.next()) tables.add(r.getString(1));
    }
    var result = new LinkedHashMap<String, String>();
    for (String table : tables)
      try (var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT count(*)||':'||md5(COALESCE(string_agg(row_to_json(t)::text,'' ORDER BY row_to_json(t)::text),'')) FROM "
                      + table
                      + " t")) {
        r.next();
        result.put(table, r.getString(1));
      }
    return result;
  }
}
