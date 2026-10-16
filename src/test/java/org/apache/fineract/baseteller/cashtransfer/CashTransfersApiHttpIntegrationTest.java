package org.apache.fineract.baseteller.cashtransfer;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real Fineract commands and PostgreSQL. All financial test state is created through HTTP APIs. */
class CashTransfersApiHttpIntegrationTest extends CashTransferNativeTestSupport {
  private static final Gson GSON = new Gson();
  private static final String ROOT = "/v2/base-teller/cash-transfers";
  private static final String SECRET = "CashTransfer-test-17!";
  private static final List<String> CURRENCIES = List.of("USD", "EUR");
  private static String date, endDate;
  private static long superRole, tellerRole;
  private long office, teller, head, source, destination, third, sourceStaff, sourceUser;
  private String username, destinationUsername;

  private static Response call(String user, String method, String path, Object body) {
    var spec =
        given(
            SavingsTestUtils.requestSpecWithAuth(
                getFineractPort(), user, user.equals("mifos") ? "password" : SECRET));
    if (body != null) spec.body(body instanceof String ? body : GSON.toJson(body));
    return spec.request(method, SavingsTestUtils.CONTEXT_PATH + "/api" + path);
  }

  private static Response ok(String user, String method, String path, Object body) {
    Response r = call(user, method, path, body);
    assertEquals(200, r.statusCode(), () -> path + ": " + r.asString());
    return r;
  }

  private static long post(String path, Object body) {
    return ok("mifos", "POST", path, body).jsonPath().getLong("resourceId");
  }

  @BeforeAll
  static void configuration() {
    superRole =
        ok("mifos", "GET", "/v1/roles", null)
            .jsonPath()
            .getLong("find { it.name == 'Super user' }.id");
    tellerRole =
        post(
            "/v1/roles",
            Map.of("name", "WEB1262 Base Teller", "description", "Cash transfer workflows"));
    var permissions = new HashMap<String, Boolean>();
    for (String permission :
        List.of(
            "READ_BASE_TELLER_CASH_TRANSFER",
            "CREATE_BASE_TELLER_CASH_TRANSFER",
            "REPRINT_BASE_TELLER_CASH_TRANSFER",
            "READ_BASE_TELLER_TRANSACTION_HISTORY",
            "READ_BASE_TELLER_CASH_INVENTORY",
            "READ_BASE_TELLER_END_OF_DAY_CLOSING",
            "CREATE_BASE_TELLER_END_OF_DAY_CLOSING")) permissions.put(permission, true);
    ok(
        "mifos",
        "PUT",
        "/v1/roles/" + tellerRole + "/permissions",
        Map.of("permissions", permissions));
    ok("mifos", "PUT", "/v1/currencies", Map.of("currencies", CURRENCIES));
    var context =
        ok("mifos", "GET", "/v2/base-teller/cash-allocations/context?officeId=1", null).jsonPath();
    date = context.getString("businessDate");
    endDate = LocalDate.parse(date).plusDays(30).toString();
    // Catalog metadata has no management API. These are configuration fixtures, never balances,
    // quantities, cashier movements, allocations or financial transactions.
    for (String currency : CURRENCIES)
      executeSqlInDefaultTenant(
          "INSERT INTO"
              + " m_service_payment_denomination(identifier,currency_code,value,denomination_type,active)"
              + " VALUES ("
              + sqlLiteral(currency + "-note")
              + ","
              + sqlLiteral(currency)
              + ",100,'BANKNOTE',true),("
              + sqlLiteral(currency + "-coin")
              + ","
              + sqlLiteral(currency)
              + ",1,'COIN',true)");
    for (int activity : List.of(101, 102, 300)) {
      long gl =
          post(
              "/v1/glaccounts",
              Map.of(
                  "name",
                  "Cash transfer test " + activity,
                  "glCode",
                  "CT" + activity,
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
  }

  @BeforeEach
  void openedDrawers() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    office =
        post(
            "/v1/offices",
            Map.of(
                "parentId",
                1,
                "name",
                "WEB1262 " + suffix,
                "openingDate",
                date,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    teller =
        post(
            "/v1/tellers",
            Map.of(
                "officeId",
                office,
                "name",
                "WEB1262 " + suffix,
                "startDate",
                date,
                "endDate",
                endDate,
                "status",
                300,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    long headStaff = staff("Head" + suffix),
        destStaff = staff("Destination" + suffix),
        thirdStaff = staff("Third" + suffix);
    sourceStaff = staff("Source" + suffix);
    head = cashier(headStaff);
    source = cashier(sourceStaff);
    destination = cashier(destStaff);
    third = cashier(thirdStaff);
    username = "source" + suffix;
    destinationUsername = "dest" + suffix;
    sourceUser = user(username, sourceStaff, List.of(tellerRole));
    user(destinationUsername, destStaff, List.of(tellerRole));
    for (String currency : CURRENCIES) {
      allocate("SAFE_VAULT_OPENING", currency, 100, null, null);
      allocate("HEAD_CASHIER_ALLOCATION", currency, 100, null, head);
      allocate("OPERATIONAL_TELLER_ALLOCATION", currency, 10, head, source);
      allocate("OPERATIONAL_TELLER_ALLOCATION", currency, 1, head, destination);
      allocate("OPERATIONAL_TELLER_ALLOCATION", currency, 1, head, third);
    }
  }

  private long staff(String name) {
    return post(
        "/v1/staff",
        Map.of(
            "officeId",
            office,
            "firstname",
            "WEB1262",
            "lastname",
            name,
            "isLoanOfficer",
            false,
            "isActive",
            true));
  }

  private long cashier(long staff) {
    ok(
        "mifos",
        "POST",
        "/v1/tellers/" + teller + "/cashiers",
        Map.of(
            "staffId",
            staff,
            "startDate",
            date,
            "endDate",
            endDate,
            "isFullDay",
            true,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"));
    return ok("mifos", "GET", "/v1/tellers/" + teller + "/cashiers", null)
        .jsonPath()
        .getLong("cashiers.find { it.staffId == " + staff + " }.id");
  }

  private long user(String name, long staff, List<Long> roles) {
    return post(
        "/v1/users",
        Map.ofEntries(
            Map.entry("username", name),
            Map.entry("firstname", "WEB1262"),
            Map.entry("lastname", "Teller"),
            Map.entry("email", name + "@example.invalid"),
            Map.entry("officeId", office),
            Map.entry("staffId", staff),
            Map.entry("roles", roles),
            Map.entry("sendPasswordToEmail", false),
            Map.entry("password", SECRET),
            Map.entry("repeatPassword", SECRET),
            Map.entry("passwordNeverExpires", true)));
  }

  private void allocate(String type, String currency, int quantity, Long from, Long to) {
    var body = new HashMap<String, Object>();
    body.put("idempotencyKey", UUID.randomUUID().toString());
    body.put("operationType", type);
    body.put("officeId", office);
    body.put("businessDate", date);
    body.put("currencyCode", currency);
    body.put("amount", quantity * 101);
    body.put(
        "denominations",
        List.of(
            Map.of("denominationId", currency + "-note", "value", 100, "quantity", quantity),
            Map.of("denominationId", currency + "-coin", "value", 1, "quantity", quantity)));
    body.put("sourceCashierId", from);
    body.put("destinationCashierId", to);
    ok("mifos", "POST", "/v2/base-teller/cash-allocations", body);
  }

  private Map<String, Object> request(int quantity, Long to, boolean multi) {
    List<Object> currencies = new ArrayList<>();
    for (String currency : multi ? CURRENCIES : List.of("USD"))
      currencies.add(
          Map.of(
              "currencyCode",
              currency,
              "denominations",
              List.of(
                  Map.of("denominationId", currency + "-note", "quantity", quantity),
                  Map.of("denominationId", currency + "-coin", "quantity", quantity))));
    return new HashMap<>(
        Map.of(
            "destinationCashierId",
            to,
            "idempotencyKey",
            UUID.randomUUID().toString(),
            "currencies",
            currencies));
  }

  private String financialState() {
    return querySingleValueInDefaultTenant(
            "SELECT"
                + " COALESCE(string_agg(CONCAT(id,':',cashier_id,':',txn_type,':',currency_code,':',txn_amount),','"
                + " ORDER BY id),'') FROM m_cashier_transactions WHERE cashier_id IN ("
                + source
                + ","
                + destination
                + ","
                + third
                + ")")
        + "|"
        + querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_cash_teller_transfer WHERE office_id=" + office)
        + "|"
        + querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM acc_gl_journal_entry WHERE office_id=" + office);
  }

  private CashTransferData.Context context(String user) {
    return GSON.fromJson(
        ok(user, "GET", ROOT + "/context", null).asString(), CashTransferData.Context.class);
  }

  private BigDecimal nativeBalance(long cashier, String currency) {
    return new BigDecimal(
        querySingleValueInDefaultTenant(
            "SELECT COALESCE(SUM(CASE WHEN txn_type IN (101,103) THEN txn_amount ELSE -txn_amount"
                + " END),0) FROM m_cashier_transactions WHERE cashier_id="
                + cashier
                + " AND currency_code="
                + sqlLiteral(currency)));
  }

  @Test
  void contextResolvesOwnedSourceAndOnlyOpenSameBranchDestinations() {
    var c = context(username);
    assertEquals(source, c.source().cashierId());
    assertTrue(c.destinations().stream().anyMatch(d -> d.cashierId() == destination));
    assertFalse(c.destinations().stream().anyMatch(d -> d.cashierId() == source));
    assertTrue(c.destinations().stream().allMatch(d -> d.officeId() == office));
    assertEquals(2, c.currencies().size());
    assertTrue(c.currencies().stream().allMatch(currency -> currency.denominations().size() == 2));
    assertTrue(
        c.currencies().stream()
            .flatMap(cu -> cu.denominations().stream())
            .allMatch(d -> d.availableQuantity() == 10));
  }

  @Test
  void previewIsBalancedBackendDrivenAndHasNoMutation() {
    String before = financialState();
    var p = ok(username, "POST", ROOT + "/preview", request(2, destination, true)).jsonPath();
    assertTrue(p.getBoolean("balanced"));
    assertEquals(p.getList("inflowsByCurrency"), p.getList("outflowsByCurrency"));
    assertEquals("PREVIEW", p.getString("status"));
    assertEquals(before, financialState());
  }

  @Test
  void multiCurrencyPostingConservesBothInventoriesAndHasOneLogicalHistoryRecord() {
    var request = request(2, destination, true);
    var receipt =
        GSON.fromJson(ok(username, "POST", ROOT, request).asString(), CashTransferData.class);
    assertEquals(2, receipt.currencies().size());
    for (String currency : CURRENCIES) {
      assertEquals(0, new BigDecimal("808").compareTo(nativeBalance(source, currency)));
      assertEquals(0, new BigDecimal("303").compareTo(nativeBalance(destination, currency)));
      assertEquals(
          8,
          context(username).currencies().stream()
              .filter(c -> c.currencyCode().equals(currency))
              .findFirst()
              .orElseThrow()
              .denominations()
              .getFirst()
              .availableQuantity());
      assertEquals(
          3,
          context(destinationUsername).currencies().stream()
              .filter(c -> c.currencyCode().equals(currency))
              .findFirst()
              .orElseThrow()
              .denominations()
              .getFirst()
              .availableQuantity());
      assertEquals(
          0,
          new BigDecimal("1111")
              .compareTo(
                  nativeBalance(source, currency).add(nativeBalance(destination, currency))));
    }
    var history =
        ok(
                username,
                "GET",
                "/v2/base-teller/transaction-history?reference=" + receipt.reference(),
                null)
            .jsonPath();
    assertEquals(1, history.getInt("totalFilteredRecords"));
    assertEquals("CASH_TRANSFER_BETWEEN_TELLERS", history.getString("items[0].operation"));
    assertEquals(2, history.getList("totalsByCurrency").size());
    for (var total : history.getList("totalsByCurrency", Map.class)) {
      assertEquals(
          0, new BigDecimal(total.get("totalInflows").toString()).compareTo(BigDecimal.ZERO));
      assertEquals(
          0,
          new BigDecimal(total.get("totalOutflows").toString()).compareTo(new BigDecimal("202")));
    }
    assertEquals(
        1,
        ok(
                destinationUsername,
                "GET",
                "/v2/base-teller/transaction-history?reference=" + receipt.reference(),
                null)
            .jsonPath()
            .getInt("totalFilteredRecords"));
    assertEquals(
        4,
        ok(
                username,
                "GET",
                "/v2/base-teller/transaction-history/CASH_TRANSFER:"
                    + receipt.id()
                    + "/denominations",
                null)
            .jsonPath()
            .getList("operationDenominations")
            .size());
    assertEquals(
        "4",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_cash_teller_transfer_currency c JOIN m_cashier_transactions t"
                + " ON t.id IN"
                + " (c.source_cashier_transaction_id,c.destination_cashier_transaction_id) WHERE"
                + " c.transfer_id="
                + receipt.id()));
  }

  @Test
  void receiptDetailsAndHtmlArePersistedAndReprintableFromEitherParticipant() {
    var receipt = ok(username, "POST", ROOT, request(2, destination, false)).asString();
    long id = GSON.fromJson(receipt, CashTransferData.class).id();
    assertEquals(
        GSON.fromJson(receipt, CashTransferData.class),
        GSON.fromJson(
            ok(username, "GET", ROOT + "/" + id, null).asString(), CashTransferData.class));
    assertEquals(
        GSON.fromJson(receipt, CashTransferData.class),
        GSON.fromJson(
            ok(destinationUsername, "GET", ROOT + "/" + id + "/receipt", null).asString(),
            CashTransferData.class));
    var html =
        given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), username, SECRET))
            .accept("text/html")
            .get(SavingsTestUtils.CONTEXT_PATH + "/api" + ROOT + "/" + id + "/receipt");
    assertEquals(200, html.statusCode());
    assertTrue(html.asString().contains("<table>"));
    assertTrue(
        html.asString().contains(GSON.fromJson(receipt, CashTransferData.class).reference()));
  }

  @Test
  void rejectsInsufficientSelfInvalidAndCrossCurrencySelectionsWithoutMutation() {
    String before = financialState();
    for (var r :
        List.of(
            request(11, destination, false),
            request(1, source, false),
            request(1, Long.MAX_VALUE, false))) {
      var response = call(username, "POST", ROOT, r);
      assertTrue(response.statusCode() >= 400 && response.statusCode() < 500, response.asString());
      assertEquals(before, financialState());
    }
    var r = request(1, destination, false);
    r.put(
        "currencies",
        List.of(
            Map.of(
                "currencyCode",
                "ZZZ",
                "denominations",
                List.of(Map.of("denominationId", "USD-note", "quantity", 1)))));
    assertEquals(403, call(username, "POST", ROOT, r).statusCode());
    assertEquals(before, financialState());
    r.put("sourceCashierId", destination);
    assertTrue(call(username, "POST", ROOT, r).statusCode() >= 400);
    assertEquals(before, financialState());
  }

  @Test
  void idempotentReplayAndConflictingReuse() {
    var r = request(2, destination, true);
    var first = GSON.fromJson(ok(username, "POST", ROOT, r).asString(), CashTransferData.class);
    String after = financialState();
    assertEquals(
        first, GSON.fromJson(ok(username, "POST", ROOT, r).asString(), CashTransferData.class));
    assertEquals(after, financialState());
    r.put("destinationCashierId", third);
    assertTrue(call(username, "POST", ROOT, r).statusCode() >= 400);
    assertEquals(after, financialState());
  }

  @Test
  void concurrentCompetingTransfersCannotConsumeSameDenominations() throws Exception {
    var a = request(10, destination, false);
    var b = request(10, third, false);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var results =
          pool.invokeAll(
              List.of(
                  (Callable<Response>) () -> call(username, "POST", ROOT, a),
                  () -> call(username, "POST", ROOT, b)));
      var codes = List.of(results.get(0).get().statusCode(), results.get(1).get().statusCode());
      assertEquals(1, codes.stream().filter(c -> c == 200).count(), codes.toString());
      assertEquals(1, codes.stream().filter(c -> c >= 400 && c < 500).count(), codes.toString());
    }
    assertEquals(0, nativeBalance(source, "USD").compareTo(BigDecimal.ZERO));
    assertEquals(
        "1",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_cash_teller_transfer WHERE office_id=" + office));
  }

  @Test
  void concurrentSameKeyPostsOnlyOnePair() throws Exception {
    var r = request(2, destination, false);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var results =
          pool.invokeAll(
              List.of(
                  (Callable<Response>) () -> call(username, "POST", ROOT, r),
                  () -> call(username, "POST", ROOT, r)));
      assertEquals(200, results.get(0).get().statusCode());
      assertEquals(200, results.get(1).get().statusCode());
      assertEquals(
          results.get(0).get().jsonPath().getLong("id"),
          results.get(1).get().jsonPath().getLong("id"));
    }
    assertEquals(0, new BigDecimal("808").compareTo(nativeBalance(source, "USD")));
  }

  @Test
  void inactiveDestinationAndDifferentOfficeAreRejectedWithoutMutation() {
    var otherOffice =
        post(
            "/v1/offices",
            Map.of(
                "parentId",
                1,
                "name",
                "Other " + UUID.randomUUID().toString().substring(0, 8),
                "openingDate",
                date,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    String before = financialState();
    ok(
        "mifos",
        "PUT",
        "/v1/staff/" + context(destinationUsername).source().staffId(),
        Map.of("isActive", false));
    assertTrue(
        context(username).destinations().stream().noneMatch(d -> d.cashierId() == destination));
    assertEquals(403, call(username, "POST", ROOT, request(1, destination, false)).statusCode());
    assertEquals(before, financialState());
    // Move a destination assignment to another office through the normal teller API.
    ok(
        "mifos",
        "PUT",
        "/v1/tellers/" + teller,
        Map.of(
            "officeId",
            otherOffice,
            "name",
            "Moved window",
            "startDate",
            date,
            "endDate",
            endDate,
            "status",
            300,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"));
    assertTrue(call(username, "POST", ROOT, request(1, third, false)).statusCode() >= 400);
    assertEquals(before, financialState());
  }

  @Test
  void historicalReplayStillWorksAfterAssignmentExpires() {
    var r = request(1, destination, false);
    var receipt = GSON.fromJson(ok(username, "POST", ROOT, r).asString(), CashTransferData.class);
    String tomorrow = LocalDate.parse(date).plusDays(1).toString();
    ok(
        "mifos",
        "PUT",
        "/v1/tellers/" + teller + "/cashiers/" + source,
        Map.of(
            "staffId",
            sourceStaff,
            "startDate",
            tomorrow,
            "endDate",
            endDate,
            "isFullDay",
            true,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"));
    String after = financialState();
    assertEquals(
        receipt, GSON.fromJson(ok(username, "POST", ROOT, r).asString(), CashTransferData.class));
    assertEquals(after, financialState());
    assertTrue(call(username, "POST", ROOT, request(1, destination, false)).statusCode() >= 400);
    assertEquals(after, financialState());
  }

  @Test
  void nativeSecondLegDatabaseFailureRollsBackFirstLegHeaderDetailsAndGl() {
    String before = financialState();
    // Database fault injection rejects the real destination leg, after the native source leg.
    // This constraint changes no financial values and is always removed after the assertion.
    executeSqlInDefaultTenant(
        "ALTER TABLE m_cashier_transactions ADD CONSTRAINT ct_test_destination_failure CHECK"
            + " (cashier_id <> "
            + destination
            + " OR txn_type <> 101) NOT VALID");
    try {
      var result = call(username, "POST", ROOT, request(2, destination, false));
      assertTrue(result.statusCode() >= 400, result.asString());
      assertEquals(before, financialState());
    } finally {
      executeSqlInDefaultTenant(
          "ALTER TABLE m_cashier_transactions DROP CONSTRAINT ct_test_destination_failure");
    }
  }

  @Test
  void unauthorizedUnassignedAndUnrelatedUsersCannotPostOrReadReceipt() {
    String before = financialState();
    assertTrue(call("mifos", "POST", ROOT, request(2, destination, false)).statusCode() >= 400);
    assertEquals(before, financialState());
    var receipt = ok(username, "POST", ROOT, request(1, destination, false)).jsonPath();
    long role =
        post(
            "/v1/roles",
            Map.of(
                "name",
                "WEB1262 reader " + UUID.randomUUID(),
                "description",
                "Participant receipts only"));
    ok(
        "mifos",
        "PUT",
        "/v1/roles/" + role + "/permissions",
        Map.of(
            "permissions",
            Map.of(
                "READ_BASE_TELLER_CASH_TRANSFER",
                true,
                "REPRINT_BASE_TELLER_CASH_TRANSFER",
                true)));
    String unrelated = "other" + UUID.randomUUID().toString().substring(0, 8);
    user(unrelated, staff(unrelated), List.of(role));
    assertTrue(
        call(unrelated, "GET", ROOT + "/" + receipt.getLong("id") + "/receipt", null).statusCode()
            >= 400);
    assertEquals(403, call(unrelated, "POST", ROOT, request(1, destination, false)).statusCode());
  }

  @Test
  void inventoryAndClosingReconcileBothDrawersWithoutShortageOrOverage() {
    ok(username, "POST", ROOT, request(2, destination, true));
    for (String user : List.of(username, destinationUsername)) {
      var context = context(user);
      List<Object> declarations = new ArrayList<>();
      for (var currency : context.currencies())
        declarations.add(
            Map.of(
                "currencyCode",
                currency.currencyCode(),
                "denominations",
                currency.denominations().stream()
                    .map(
                        d ->
                            Map.of(
                                "denominationId",
                                d.denominationId(),
                                "quantity",
                                d.availableQuantity()))
                    .toList(),
                "vouchers",
                0,
                "checks",
                List.of()));
      var closing =
          ok(
                  user,
                  "POST",
                  "/v2/base-teller/end-of-day-closing/preview",
                  Map.of(
                      "cashierId",
                      context.source().cashierId(),
                      "businessDate",
                      date,
                      "currencies",
                      declarations))
              .jsonPath();
      for (var balance : closing.getList("balancesByCurrency", Map.class)) {
        assertEquals(
            0, new BigDecimal(balance.get("shortage").toString()).compareTo(BigDecimal.ZERO));
        assertEquals(
            0, new BigDecimal(balance.get("overage").toString()).compareTo(BigDecimal.ZERO));
        assertEquals(
            0,
            new BigDecimal(balance.get("expectedCash").toString())
                .compareTo(
                    nativeBalance(
                        context.source().cashierId(), balance.get("currencyCode").toString())));
      }
      var inventory =
          ok(
                  user,
                  "GET",
                  "/v2/base-teller/cash-inventory?custodianKey=TELLER:"
                      + context.source().cashierId()
                      + "&transactionType=CASH",
                  null)
              .jsonPath()
              .getList("", Map.class);
      for (var row : inventory) assertNotNull(row.get("balance"));
    }
  }

  @Test
  void completedClosingsForBothDrawersReconcileAndHistoricalReplaySurvivesClosure() {
    var r = request(2, destination, true);
    var receipt = GSON.fromJson(ok(username, "POST", ROOT, r).asString(), CashTransferData.class);
    String authorizer = "head" + UUID.randomUUID().toString().substring(0, 8);
    long headStaff =
        Long.parseLong(
            querySingleValueInDefaultTenant("SELECT staff_id FROM m_cashiers WHERE id=" + head));
    user(authorizer, headStaff, List.of(superRole));
    for (String participant : List.of(destinationUsername, username)) {
      var context = context(participant);
      List<Object> declarations = new ArrayList<>();
      for (var currency : context.currencies())
        declarations.add(
            Map.of(
                "currencyCode",
                currency.currencyCode(),
                "denominations",
                currency.denominations().stream()
                    .map(
                        d ->
                            Map.of(
                                "denominationId",
                                d.denominationId(),
                                "quantity",
                                d.availableQuantity()))
                    .toList(),
                "vouchers",
                0,
                "checks",
                List.of()));
      var closing =
          ok(
                  "mifos",
                  "POST",
                  "/v2/base-teller/end-of-day-closing",
                  Map.of(
                      "cashierId",
                      context.source().cashierId(),
                      "businessDate",
                      date,
                      "idempotencyKey",
                      UUID.randomUUID().toString(),
                      "currencies",
                      declarations,
                      "authorization",
                      Map.of("username", authorizer, "credential", SECRET)))
              .jsonPath();
      assertEquals("COMPLETED", closing.getString("status"));
      for (var balance : closing.getList("balancesByCurrency", Map.class)) {
        assertEquals(
            0, new BigDecimal(balance.get("shortage").toString()).compareTo(BigDecimal.ZERO));
        assertEquals(
            0, new BigDecimal(balance.get("overage").toString()).compareTo(BigDecimal.ZERO));
        assertEquals(
            0,
            new BigDecimal(balance.get("expectedCash").toString())
                .compareTo(new BigDecimal(balance.get("settledCash").toString())));
      }
      String after = financialState();
      assertEquals(403, call(username, "POST", ROOT, request(1, destination, false)).statusCode());
      assertEquals(after, financialState());
    }
    assertEquals(
        receipt, GSON.fromJson(ok(username, "POST", ROOT, r).asString(), CashTransferData.class));
    assertEquals(
        receipt,
        GSON.fromJson(
            ok(destinationUsername, "GET", ROOT + "/" + receipt.id() + "/receipt", null).asString(),
            CashTransferData.class));
  }
}
