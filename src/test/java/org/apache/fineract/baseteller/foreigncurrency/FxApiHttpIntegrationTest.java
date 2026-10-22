package org.apache.fineract.baseteller.foreigncurrency;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import io.restassured.response.Response;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Authenticated HTTP against an isolated native Fineract. No simulated financial responses. */
class FxApiHttpIntegrationTest {
  private static final String RATES = "/v2/foreign-currency/rates",
      TELLER = "/v2/base-teller/foreign-currency";
  private static final Gson GSON = new Gson();
  private static final String SECRET = "Fx-Infrastructure-17!";
  private static String root, date, reader;
  private static FxNativeTestSupport nativeTenant;
  private static final List<String> FINANCIAL =
      List.of(
          "m_cashier_transactions",
          "acc_gl_journal_entry",
          "m_cash_allocation",
          "m_cashier_reconciliation",
          "m_cash_exchange",
          "m_cash_teller_transfer",
          "m_cash_drawer_pickup",
          "m_base_teller_withdrawal",
          "m_end_of_day_closing",
          "m_base_teller_deposit");

  @BeforeAll
  static void setup() throws Exception {
    String base = System.getProperty("web1264.http.baseUrl");
    if (base == null) {
      // Currency configuration is tenant-wide. Do not mutate the tenant shared by older suites.
      nativeTenant = new FxNativeTestSupport();
      base = nativeTenant.baseUrl();
    }
    // Explicit external mode is limited to localhost test instances.
    assertTrue(base.matches("https?://(127\\.0\\.0\\.1|localhost):[0-9]+"));
    root = base + "/fineract-provider/api";
    date = ok("mifos", "GET", RATES + "/context", null).jsonPath().getString("businessDate");
    ok("mifos", "PUT", "/v1/currencies", Map.of("currencies", List.of("USD", "EUR")));
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    long role =
        ok(
                "mifos",
                "POST",
                "/v1/roles",
                Map.of("name", "FX reader " + suffix, "description", "Synthetic FX read-only role"))
            .jsonPath()
            .getLong("resourceId");
    ok(
        "mifos",
        "PUT",
        "/v1/roles/" + role + "/permissions",
        Map.of("permissions", Map.of("READ_BASE_TELLER_FX", true)));
    reader = "fxreader" + suffix;
    ok(
        "mifos",
        "POST",
        "/v1/users",
        Map.ofEntries(
            Map.entry("username", reader),
            Map.entry("firstname", "Synthetic"),
            Map.entry("lastname", "FX Reader"),
            Map.entry("email", reader + "@example.invalid"),
            Map.entry("officeId", 1),
            Map.entry("roles", List.of(role)),
            Map.entry("sendPasswordToEmail", false),
            Map.entry("password", SECRET),
            Map.entry("repeatPassword", SECRET),
            Map.entry("passwordNeverExpires", true)));
  }

  @AfterAll
  static void cleanup() {
    if (nativeTenant != null) nativeTenant.close();
  }

  private static Response call(String user, String method, String path, Object body) {
    var request =
        given()
            .relaxedHTTPSValidation()
            .header("Fineract-Platform-TenantId", "default")
            .contentType("application/json");
    if (user != null)
      request.auth().preemptive().basic(user, user.equals("mifos") ? "password" : SECRET);
    if (body != null) request.body(body instanceof String ? body : GSON.toJson(body));
    return request.request(method, root + path);
  }

  private static Response ok(String user, String method, String path, Object body) {
    var r = call(user, method, path, body);
    assertEquals(200, r.statusCode(), () -> path + ": " + r.asString());
    return r;
  }

  private static Map<String, Object> draft(long office, long version) {
    return new LinkedHashMap<>(
        Map.of(
            "effectiveDate",
            date,
            "baseCurrency",
            "USD",
            "quoteCurrency",
            "EUR",
            "dailyBaseRate",
            new java.math.BigDecimal("2.34"),
            "branches",
            List.of(
                Map.of(
                    "officeId",
                    office,
                    "expectedVersion",
                    version,
                    "buyPoints",
                    -1,
                    "sellPoints",
                    2))));
  }

  private static long branch() {
    return ok(
            "mifos",
            "POST",
            "/v1/offices",
            Map.of(
                "parentId",
                1,
                "name",
                "FX " + UUID.randomUUID(),
                "openingDate",
                date,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"))
        .jsonPath()
        .getLong("resourceId");
  }

  private static Map<String, String> snapshot() throws Exception {
    var result = new LinkedHashMap<String, String>();
    String url = System.getProperty("web1264.http.jdbcUrl");
    for (String table : FINANCIAL) {
      String sql =
          "SELECT COALESCE(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb)::text FROM "
              + table
              + " x";
      if (url == null) {
        assertNotNull(nativeTenant, "External HTTP mode also requires web1264.http.jdbcUrl.");
        result.put(table, nativeTenant.read(sql));
      } else
        try (var c =
                DriverManager.getConnection(
                    url,
                    System.getProperty("web1264.test.username", "postgres"),
                    System.getProperty("web1264.test.password", ""));
            var s = c.createStatement();
            var rs = s.executeQuery(sql)) {
          assertTrue(rs.next());
          result.put(table, rs.getString(1));
        }
    }
    return result;
  }

  @Test
  void nativeAuthenticationAndOperationPermissionsAreEnforced() {
    assertEquals(401, call(null, "GET", RATES + "/context", null).statusCode());
    assertEquals(403, call(reader, "GET", RATES + "/context", null).statusCode());
    assertEquals(403, call(reader, "POST", RATES + "/preview", draft(1, 0)).statusCode());
    for (String path : List.of("/sales", "/purchases", "/sales/preview", "/purchases/preview"))
      assertEquals(403, call(reader, "POST", TELLER + path, FxValidationTest.TRADE).statusCode());
  }

  @Test
  void draftsRemainInactiveWithImmutableVersionsAndAuditHistory() {
    long office = branch();
    var body = draft(office, 0);
    var preview = ok("mifos", "POST", RATES + "/preview", body);
    assertFalse(preview.jsonPath().getBoolean("capabilities.publicationEnabled"));
    assertEquals(
        0, ok("mifos", "GET", RATES + "?officeId=" + office, null).jsonPath().getList("$").size());
    body.put("previewToken", preview.jsonPath().getString("previewToken"));
    var first = ok("mifos", "POST", RATES, body);
    long id = first.jsonPath().getLong("[0].id");
    assertEquals("DRAFT", first.jsonPath().getString("[0].status"));
    assertEquals(403, call("mifos", "POST", RATES, body).statusCode());
    body = draft(office, 1);
    body.put(
        "previewToken",
        ok("mifos", "POST", RATES + "/preview", body).jsonPath().getString("previewToken"));
    var second = ok("mifos", "POST", RATES, body);
    long next = second.jsonPath().getLong("[0].id");
    assertEquals(2, second.jsonPath().getInt("[0].version"));
    assertEquals(1, ok("mifos", "GET", RATES + "/" + id, null).jsonPath().getInt("version"));
    assertEquals(
        2, ok("mifos", "GET", RATES + "/" + next + "/audit", null).jsonPath().getList("$").size());
  }

  @Test
  void privilegedConfirmationPublicationAndRetriesLeaveEveryFinancialTableUnchanged()
      throws Exception {
    var before = snapshot();
    for (int retry = 0; retry < 2; retry++)
      for (String path : List.of(TELLER + "/sales", TELLER + "/purchases", RATES + "/publish")) {
        var r = call("mifos", "POST", path, Map.of("execute", true, "active", true));
        assertEquals(403, r.statusCode());
        assertEquals(
            "error.msg.base.teller.fx.operation.unsupported",
            r.jsonPath().getString("errors[0].userMessageGlobalisationCode"));
      }
    assertEquals(before, snapshot());
    assertEquals(404, call("mifos", "GET", TELLER + "/transactions/1/receipt", null).statusCode());
  }

  @Test
  void existingConversionApiCannotSeeDraftRatesOrEnableTrading() {
    var r = ok("mifos", "GET", RATES + "/context", null);
    assertEquals("UNCONFIRMED", r.jsonPath().getString("rateConvention"));
    assertNull(r.jsonPath().getString("nationalCurrency"));
    assertTrue(
        ok("mifos", "GET", "/exchange-rates?sourceCurrencyCode=USD&targetCurrencyCode=EUR", null)
            .jsonPath()
            .getList("$")
            .isEmpty());
    assertEquals(
        403,
        call("mifos", "POST", RATES + "/publish", Map.of("formula", "base + points")).statusCode());
  }

  @Test
  void concurrentNativeDraftConfirmationCreatesExactlyOneRevision() throws Exception {
    long office = branch();
    var body = draft(office, 0);
    body.put(
        "previewToken",
        ok("mifos", "POST", RATES + "/preview", body).jsonPath().getString("previewToken"));
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> call("mifos", "POST", RATES, body).statusCode());
      var b = pool.submit(() -> call("mifos", "POST", RATES, body).statusCode());
      var statuses = new java.util.HashSet<>(List.of(a.get(), b.get()));
      assertEquals(java.util.Set.of(200, 403), statuses);
    }
    assertEquals(
        1, ok("mifos", "GET", RATES + "?officeId=" + office, null).jsonPath().getList("$").size());
  }

  @Test
  void unassignedOperatorCannotUseTellerLookupsAndInvalidDraftsDoNotWrite() throws Exception {
    var before = snapshot();
    for (String path :
        List.of("/context", "/denominations?currencyCode=USD", "/customers?clientNumber=0001"))
      assertEquals(403, call("mifos", "GET", TELLER + path, null).statusCode());
    var body = draft(branch(), 0);
    body.put("active", true);
    assertEquals(403, call("mifos", "POST", RATES + "/preview", body).statusCode());
    assertEquals(before, snapshot());
  }
}
