package org.apache.fineract.baseteller.withdrawal;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
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
class WithdrawalsApiHttpIntegrationTest extends WithdrawalNativeTestSupport {
  private static final Gson GSON = new Gson();
  private static final String ROOT = "/v2/base-teller/withdrawals";
  private static final String SECRET = "Withdrawal-test-17!";
  private static final List<String> CURRENCIES = List.of("USD");
  private static String date, endDate;
  private static long superRole, tellerRole, product, payment, identificationType;
  private long client, otherClient, account;
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
            Map.of("name", "WEB1263 Base Teller", "description", "Cash transfer workflows"));
    var permissions = new HashMap<String, Boolean>();
    for (String permission :
        List.of(
            "READ_BASE_TELLER_WITHDRAWAL",
            "CREATE_BASE_TELLER_WITHDRAWAL",
            "REPRINT_BASE_TELLER_WITHDRAWAL",
            "WITHDRAWAL_SAVINGSACCOUNT",
            "READ_SAVINGSACCOUNT",
            "READ_CLIENT",
            "READ_BASE_TELLER_TRANSACTION_REVERSAL",
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
    payment =
        post(
            "/v1/paymenttypes",
            Map.of("name", "Withdrawal cash", "isCashPayment", true, "position", 1));
    long code =
        ok("mifos", "GET", "/v1/codes", null)
            .jsonPath()
            .getLong("find { it.name == 'Customer Identifier' }.id");
    identificationType =
        ok(
                "mifos",
                "POST",
                "/v1/codes/" + code + "/codevalues",
                Map.of("name", "Withdrawal test ID", "isActive", true, "position", 1))
            .jsonPath()
            .getLong("subResourceId");
    product =
        post(
            "/v1/savingsproducts",
            Map.ofEntries(
                Map.entry("name", "Withdrawal test savings"), Map.entry("shortName", "WD63"),
                Map.entry("description", "Native withdrawal acceptance"),
                    Map.entry("currencyCode", "USD"),
                Map.entry("digitsAfterDecimal", 2), Map.entry("nominalAnnualInterestRate", 0),
                Map.entry("interestCompoundingPeriodType", 1),
                    Map.entry("interestPostingPeriodType", 4),
                Map.entry("interestCalculationType", 1),
                    Map.entry("interestCalculationDaysInYearType", 365),
                Map.entry("accountingRule", 1), Map.entry("locale", "en")));
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
                "WEB1263 " + suffix,
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
                "WEB1263 " + suffix,
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
    client = client(office, "Owner" + suffix);
    otherClient = client(office, "Other" + suffix);
    account = account(client, "1000");
    ok(
        "mifos",
        "POST",
        "/v1/clients/" + client + "/identifiers",
        Map.of(
            "documentTypeId",
            identificationType,
            "documentKey",
            "secret-" + client,
            "status",
            "ACTIVE"));
  }

  private long staff(String name) {
    return post(
        "/v1/staff",
        Map.of(
            "officeId",
            office,
            "firstname",
            "WEB1263",
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
            Map.entry("firstname", "WEB1263"),
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

  private long client(long officeId, String name) {
    return post(
        "/v1/clients",
        Map.of(
            "officeId",
            officeId,
            "legalFormId",
            1,
            "firstname",
            "Withdrawal",
            "lastname",
            name,
            "active",
            true,
            "activationDate",
            date,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"));
  }

  private long account(long owner, String funding) {
    long id =
        post(
            "/v1/savingsaccounts",
            Map.of(
                "clientId",
                owner,
                "productId",
                product,
                "submittedOnDate",
                date,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    ok(
        "mifos",
        "POST",
        "/v1/savingsaccounts/" + id + "?command=approve",
        Map.of("approvedOnDate", date, "dateFormat", "yyyy-MM-dd", "locale", "en"));
    ok(
        "mifos",
        "POST",
        "/v1/savingsaccounts/" + id + "?command=activate",
        Map.of("activatedOnDate", date, "dateFormat", "yyyy-MM-dd", "locale", "en"));
    if (new BigDecimal(funding).signum() > 0)
      ok(
          "mifos",
          "POST",
          "/v1/savingsaccounts/" + id + "/transactions?command=deposit",
          Map.of(
              "transactionDate",
              date,
              "transactionAmount",
              funding,
              "paymentTypeId",
              payment,
              "locale",
              "en",
              "dateFormat",
              "yyyy-MM-dd"));
    return id;
  }

  private Map<String, Object> request(int notes, int coins) {
    return new HashMap<>(
        Map.of(
            "clientId",
            client,
            "accountId",
            account,
            "method",
            "CASH",
            "amount",
            notes * 100 + coins,
            "paymentTypeId",
            payment,
            "identificationTypeId",
            identificationType,
            "identificationReference",
            "secret-" + client,
            "idempotencyKey",
            UUID.randomUUID().toString(),
            "denominations",
            List.of(
                Map.of("denominationId", "USD-note", "quantity", notes),
                Map.of("denominationId", "USD-coin", "quantity", coins))));
  }

  private WithdrawalData.Context context() {
    return GSON.fromJson(
        ok(username, "GET", ROOT + "/context", null).asString(), WithdrawalData.Context.class);
  }

  private BigDecimal balance() {
    return new BigDecimal(
        querySingleValueInDefaultTenant(
            "SELECT account_balance_derived FROM m_savings_account WHERE id=" + account));
  }

  private BigDecimal cash() {
    return new BigDecimal(
        ok(
                "mifos",
                "GET",
                "/v1/tellers/"
                    + teller
                    + "/cashiers/"
                    + source
                    + "/summaryandtransactions?currencyCode=USD",
                null)
            .jsonPath()
            .getString("netCash"));
  }

  private String state() {
    return balance()
        + "|"
        + cash()
        + "|"
        + querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_savings_account_transaction WHERE savings_account_id="
                + account)
        + "|"
        + querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_base_teller_withdrawal WHERE office_id=" + office)
        + "|"
        + context().denominations();
  }

  private WithdrawalData create(Map<String, Object> r) {
    return GSON.fromJson(ok(username, "POST", ROOT, r).asString(), WithdrawalData.class);
  }

  private void rejected(Map<String, Object> r) {
    String before = state();
    var response = call(username, "POST", ROOT, r);
    assertTrue(response.statusCode() >= 400 && response.statusCode() < 500, response.asString());
    assertEquals(before, state());
  }

  @Test
  void contextSearchAccountsAndPreviewUseNativeStateWithoutMutation() {
    var c = context();
    assertEquals(source, c.cashier().cashierId());
    assertEquals(List.of(WithdrawalRequest.Method.CASH), c.supportedMethods());
    assertFalse(c.checkCapabilities().supported());
    assertTrue(c.denominations().stream().allMatch(d -> d.availableQuantity() == 10));
    String number =
        ok("mifos", "GET", "/v1/clients/" + client, null).jsonPath().getString("accountNo");
    assertEquals(
        client,
        ok(username, "GET", ROOT + "/customers?clientNumber=" + number, null)
            .jsonPath()
            .getLong("pageItems[0].clientId"));
    assertTrue(
        ok(username, "GET", ROOT + "/customers?name=Withdrawal&limit=1", null)
                .jsonPath()
                .getList("pageItems")
                .size()
            <= 1);
    assertEquals(
        account,
        ok(username, "GET", ROOT + "/customers/" + client + "/accounts", null)
            .jsonPath()
            .getLong("[0].accountId"));
    assertEquals(
        new BigDecimal("1000"),
        GSON.fromJson(
                ok(username, "GET", ROOT + "/accounts/" + account, null).asString(),
                WithdrawalData.Account.class)
            .availableAmount()
            .stripTrailingZeros()
            .setScale(0));
    String before = state();
    var p =
        GSON.fromJson(
            ok(username, "POST", ROOT + "/preview", request(2, 2)).asString(),
            WithdrawalData.class);
    assertEquals("PREVIEW", p.status());
    assertTrue(p.inflowsByCurrency().isEmpty());
    assertEquals(0, p.physicalCashOutflow().compareTo(new BigDecimal("202")));
    assertEquals(WithdrawalValidation.mask("secret-" + client), p.recipient().maskedReference());
    assertEquals(before, state());
  }

  @Test
  void cashPostsOnceWithExactInventoryReceiptHistoryAndClosingReconciliation() {
    String cashierRows =
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_cashier_transactions WHERE cashier_id=" + source);
    String journalRows =
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM acc_gl_journal_entry WHERE office_id=" + office);
    var receipt = create(request(2, 2));
    assertEquals(0, balance().compareTo(new BigDecimal("798")));
    assertEquals(0, cash().compareTo(new BigDecimal("808")));
    assertTrue(context().denominations().stream().allMatch(d -> d.availableQuantity() == 8));
    assertNotNull(receipt.savingsTransactionId());
    assertNull(receipt.cashierTransactionId());
    assertEquals(
        cashierRows,
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_cashier_transactions WHERE cashier_id=" + source));
    assertEquals(
        journalRows,
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM acc_gl_journal_entry WHERE office_id=" + office));
    assertNull(receipt.account().availableAmount());
    assertFalse(receipt.reversible());
    assertEquals(
        receipt,
        GSON.fromJson(
            ok(username, "GET", ROOT + "/" + receipt.id(), null).asString(), WithdrawalData.class));
    var printed = ok(username, "GET", ROOT + "/" + receipt.id() + "/receipt", null);
    assertFalse(printed.asString().contains("secret-" + client));
    assertEquals(receipt, GSON.fromJson(printed.asString(), WithdrawalData.class));
    var html =
        given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), username, SECRET))
            .accept("text/html")
            .get(SavingsTestUtils.CONTEXT_PATH + "/api" + ROOT + "/" + receipt.id() + "/receipt");
    assertEquals(200, html.statusCode());
    assertTrue(html.asString().contains(receipt.reference()));
    var history =
        ok(
                username,
                "GET",
                "/v2/base-teller/transaction-history?reference=" + receipt.reference(),
                null)
            .jsonPath();
    assertEquals(1, history.getInt("totalFilteredRecords"));
    assertEquals("CASH_WITHDRAWAL", history.getString("items[0].operation"));
    assertEquals(
        2,
        ok(
                username,
                "GET",
                "/v2/base-teller/transaction-history/SAVINGS_WITHDRAWAL:"
                    + receipt.id()
                    + "/denominations",
                null)
            .jsonPath()
            .getList("operationDenominations")
            .size());
    var inv =
        ok(
                username,
                "GET",
                "/v2/base-teller/cash-inventory?custodianKey=TELLER:"
                    + source
                    + "&transactionType=CASH",
                null)
            .jsonPath()
            .getList("", Map.class);
    assertEquals(0, new BigDecimal(inv.getFirst().get("balance").toString()).compareTo(cash()));
    assertEquals(
        0,
        new BigDecimal(inv.getFirst().get("accumulatedOutflows").toString())
            .compareTo(new BigDecimal("202")));
    var closing =
        ok(
                username,
                "POST",
                "/v2/base-teller/end-of-day-closing/preview",
                Map.of(
                    "cashierId",
                    source,
                    "businessDate",
                    date,
                    "currencies",
                    List.of(
                        Map.of(
                            "currencyCode",
                            "USD",
                            "denominations",
                            context().denominations().stream()
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
                            List.of()))))
            .jsonPath();
    assertEquals(0, new BigDecimal(closing.getString("balancesByCurrency[0].shortage")).signum());
    assertEquals(0, new BigDecimal(closing.getString("balancesByCurrency[0].overage")).signum());
  }

  @Test
  void finalSubmissionRevalidatesFundsAfterPreviewAndNativeHolds() {
    var r = request(8, 0);
    ok(username, "POST", ROOT + "/preview", r);
    ok(
        "mifos",
        "POST",
        "/v1/savingsaccounts/" + account + "/transactions?command=holdAmount",
        Map.of(
            "transactionDate",
            date,
            "transactionAmount",
            300,
            "lienAllowed",
            false,
            "reasonForBlock",
            "Withdrawal hold verification",
            "locale",
            "en",
            "dateFormat",
            "yyyy-MM-dd"));
    rejected(r);
  }

  @Test
  void invalidIdentityOwnershipAmountsAndInventoryCannotPost() {
    for (var change :
        List.of(
            Map.of("identificationReference", "wrong"),
            Map.of("identificationTypeId", Long.MAX_VALUE),
            Map.of("clientId", otherClient),
            Map.of("amount", 0),
            Map.of("amount", 1),
            Map.of("amount", 100.001),
            Map.of("paymentTypeId", Long.MAX_VALUE))) {
      var r = request(2, 2);
      r.putAll(change);
      rejected(r);
    }
    var noFunds = request(11, 0);
    rejected(noFunds);
    var noCash = request(0, 11);
    rejected(noCash);
    var wrongDenom = request(1, 0);
    wrongDenom.put("denominations", List.of(Map.of("denominationId", "unknown", "quantity", 1)));
    rejected(wrongDenom);
    var missing = request(1, 0);
    missing.remove("identificationReference");
    rejected(missing);
    long inactive =
        post(
            "/v1/savingsaccounts",
            Map.of(
                "clientId",
                client,
                "productId",
                product,
                "submittedOnDate",
                date,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    var badAccount = request(1, 0);
    badAccount.put("accountId", inactive);
    rejected(badAccount);
  }

  @Test
  void checkCatalogsAndIssuanceFailClosedWithoutChangingAccountOrCash() {
    assertTrue(ok(username, "GET", ROOT + "/banks", null).jsonPath().getList("").isEmpty());
    assertTrue(ok(username, "GET", ROOT + "/check-types", null).jsonPath().getList("").isEmpty());
    var r = request(1, 0);
    r.put("method", "CHECK");
    rejected(r);
    assertEquals(
        "0",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_base_teller_withdrawal WHERE office_id=" + office));
  }

  @Test
  void duplicateAndConflictingKeyAreSafe() {
    var r = request(2, 2);
    var first = create(r);
    String after = state();
    assertEquals(first, create(r));
    assertEquals(after, state());
    r.put("amount", 203);
    rejected(r);
  }

  @Test
  void concurrentSameKeyHasOneDebitAndOneCashMovement() throws Exception {
    var r = request(2, 2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var result =
          pool.invokeAll(
              List.of(
                  (Callable<Response>) () -> call(username, "POST", ROOT, r),
                  () -> call(username, "POST", ROOT, r)));
      assertEquals(200, result.get(0).get().statusCode());
      assertEquals(200, result.get(1).get().statusCode());
      assertEquals(
          GSON.fromJson(result.get(0).get().asString(), WithdrawalData.class),
          GSON.fromJson(result.get(1).get().asString(), WithdrawalData.class));
    }
    assertEquals(0, balance().compareTo(new BigDecimal("798")));
    assertEquals(0, cash().compareTo(new BigDecimal("808")));
  }

  @Test
  void concurrentCompetingWithdrawalsCannotOverspendFunds() throws Exception {
    compete(request(8, 0), request(8, 0));
    assertEquals(0, balance().compareTo(new BigDecimal("200")));
    assertEquals(0, cash().compareTo(new BigDecimal("210")));
  }

  @Test
  void concurrentCompetingWithdrawalsCannotReuseLastCoins() throws Exception {
    compete(request(0, 10), request(0, 10));
    assertEquals(0, balance().compareTo(new BigDecimal("990")));
    assertEquals(0, cash().compareTo(new BigDecimal("1000")));
    assertEquals(
        0,
        context().denominations().stream()
            .filter(d -> d.denominationType().equals("COIN"))
            .findFirst()
            .orElseThrow()
            .availableQuantity());
  }

  private void compete(Map<String, Object> a, Map<String, Object> b) throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var result =
          pool.invokeAll(
              List.of(
                  (Callable<Response>) () -> call(username, "POST", ROOT, a),
                  () -> call(username, "POST", ROOT, b)));
      var codes = List.of(result.get(0).get().statusCode(), result.get(1).get().statusCode());
      assertEquals(
          1,
          codes.stream().filter(c -> c == 200).count(),
          codes + " " + result.get(0).get().asString() + " " + result.get(1).get().asString());
      assertEquals(
          1,
          codes.stream().filter(c -> c >= 400 && c < 500).count(),
          codes + " " + result.get(0).get().asString() + " " + result.get(1).get().asString());
    }
    assertEquals(
        "1",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_base_teller_withdrawal WHERE office_id=" + office));
  }

  @Test
  void failedCompletionRollsBackNativeDebitCashCustodyAndReceipt() {
    String before = state();
    var r = request(2, 2);
    executeSqlInDefaultTenant(
        "CREATE FUNCTION web1263_fail() RETURNS trigger LANGUAGE plpgsql AS $body$ BEGIN IF"
            + " NEW.idempotency_key="
            + sqlLiteral(r.get("idempotencyKey").toString())
            + " AND NEW.status='COMPLETED' THEN RAISE EXCEPTION 'injected completion failure'; END"
            + " IF; RETURN NEW; END $body$");
    executeSqlInDefaultTenant(
        "CREATE TRIGGER web1263_failure BEFORE UPDATE ON m_base_teller_withdrawal FOR EACH ROW"
            + " EXECUTE FUNCTION web1263_fail()");
    try {
      assertEquals(500, call(username, "POST", ROOT, r).statusCode());
    } finally {
      executeSqlInDefaultTenant("DROP TRIGGER web1263_failure ON m_base_teller_withdrawal");
      executeSqlInDefaultTenant("DROP FUNCTION web1263_fail()");
    }
    assertEquals(before, state());
    assertEquals(0, balance().compareTo(new BigDecimal("1000")));
    assertEquals(0, cash().compareTo(new BigDecimal("1010")));
    create(r); // the rolled-back command/key must remain retryable
  }

  @Test
  void nativeCashWithdrawalWithoutDenominationsBlocksFurtherCustodyOperations() {
    ok(
        username,
        "POST",
        "/v1/savingsaccounts/" + account + "/transactions?command=withdrawal",
        Map.of(
            "transactionDate",
            date,
            "transactionAmount",
            1,
            "paymentTypeId",
            payment,
            "locale",
            "en",
            "dateFormat",
            "yyyy-MM-dd"));
    String transactions =
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_savings_account_transaction WHERE savings_account_id="
                + account);
    assertEquals(0, balance().compareTo(new BigDecimal("999")));
    assertEquals(403, call(username, "GET", ROOT + "/context", null).statusCode());
    assertEquals(403, call(username, "POST", ROOT, request(2, 2)).statusCode());
    assertEquals(0, balance().compareTo(new BigDecimal("999")));
    assertEquals(
        transactions,
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_savings_account_transaction WHERE savings_account_id="
                + account));
    assertEquals(
        "0",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_base_teller_withdrawal WHERE office_id=" + office));
  }

  @Test
  void permissionsOfficeScopeAndSourceCannotBeForged() {
    long outsideClient = client(1, "Outside" + UUID.randomUUID().toString().substring(0, 8));
    long outside = account(outsideClient, "1000");
    var r = request(1, 0);
    r.put("clientId", outsideClient);
    r.put("accountId", outside);
    rejected(r);
    assertTrue(call(username, "GET", ROOT + "/accounts/" + outside, null).statusCode() >= 400);
    r = request(1, 0);
    r.put("sourceCashierId", destination);
    rejected(r);
    assertTrue(call("mifos", "GET", ROOT + "/context", null).statusCode() >= 400);
    var receipt = create(request(1, 0));
    assertTrue(
        call(destinationUsername, "GET", ROOT + "/" + receipt.id() + "/receipt", null).statusCode()
            >= 400);
    long readRole =
        post(
            "/v1/roles",
            Map.of(
                "name", "Withdrawal read " + UUID.randomUUID(), "description", "No native debit"));
    ok(
        "mifos",
        "PUT",
        "/v1/roles/" + readRole + "/permissions",
        Map.of(
            "permissions",
            Map.of(
                "READ_BASE_TELLER_WITHDRAWAL",
                true,
                "CREATE_BASE_TELLER_WITHDRAWAL",
                true,
                "READ_CLIENT",
                true,
                "READ_SAVINGSACCOUNT",
                true)));
    String restricted = "limited" + UUID.randomUUID().toString().substring(0, 8);
    user(restricted, sourceStaff, List.of(readRole));
    String before = state();
    assertEquals(403, call(restricted, "POST", ROOT, request(1, 0)).statusCode());
    assertEquals(before, state());
  }
}
