package org.apache.fineract.baseteller.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import org.apache.fineract.testing.support.SavingsIntegrationTestBase;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.*;

/** Real HTTP/native posting against the packaged plugin; no mocked financial sources. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransactionReversalsApiHttpIntegrationTest extends SavingsIntegrationTestBase {
  private static final String API = SavingsTestUtils.CONTEXT_PATH + "/api";
  private static final String ROOT = "/v2/base-teller/transaction-reversals";
  private static final String ACTOR = "web1259-cashier";
  private static String currency, date, note, small, privateHistory;
  private static long cashier, actorId, serviceId, cashType, checkType, client, account, bank;

  private static Response call(String username, String method, String path, String body) {
    var request =
        given(
                SavingsTestUtils.requestSpecWithAuth(
                    getFineractPort(),
                    username,
                    username.equals("mifos") ? "password" : "WEB1259-Test-A1!"))
            .contentType(ContentType.JSON);
    if (body != null) request.body(body);
    return request.request(method, API + path);
  }

  private static Response ok(String username, String method, String path, String body) {
    Response response = call(username, method, path, body);
    // Never print authorization bodies/responses in assertions or diagnostics.
    assertEquals(200, response.statusCode(), "Unexpected HTTP status at " + path);
    return response;
  }

  private static long resource(Response response) {
    return response.jsonPath().getLong("resourceId");
  }

  private static long gl(String name, int type) {
    return resource(
        ok(
            "mifos",
            "POST",
            "/v1/glaccounts",
            "{\"name\":\""
                + name
                + "\",\"glCode\":\""
                + name
                + "\",\"type\":"
                + type
                + ",\"usage\":1,\"manualEntriesAllowed\":true}"));
  }

  @BeforeAll
  static void setup() {
    currency =
        querySingleValueInDefaultTenant(
            "SELECT code FROM m_organisation_currency ORDER BY code LIMIT 1");
    date =
        ok("mifos", "GET", "/v2/base-teller/cash-allocations/context?officeId=1", null)
            .jsonPath()
            .getString("businessDate");
    long staff =
        resource(
            ok(
                "mifos",
                "POST",
                "/v1/staff",
                "{\"officeId\":1,\"firstname\":\"WEB1259\",\"lastname\":\"Cashier\",\"isLoanOfficer\":false}"));
    long teller =
        resource(
            ok(
                "mifos",
                "POST",
                "/v1/tellers",
                "{\"officeId\":1,\"name\":\"WEB1259 Window\",\"description\":\"Reversal"
                    + " test\",\"status\":300,\"startDate\":\""
                    + java.time.LocalDate.parse(date).minusDays(1)
                    + "\",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}"));
    cashier =
        resource(
            ok(
                "mifos",
                "POST",
                "/v1/tellers/" + teller + "/cashiers",
                "{\"staffId\":"
                    + staff
                    + ",\"description\":\"Reversal cashier\",\"isFullDay\":true,\"startDate\":\""
                    + java.time.LocalDate.parse(date).minusDays(1)
                    + "\",\"endDate\":\""
                    + java.time.LocalDate.parse(date).plusDays(1)
                    + "\",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}"));
    long role =
        Long.parseLong(
            querySingleValueInDefaultTenant(
                "SELECT role_id FROM m_appuser_role JOIN m_appuser u ON u.id=appuser_id WHERE"
                    + " u.username='mifos' ORDER BY role_id LIMIT 1"));
    actorId =
        resource(
            ok(
                "mifos",
                "POST",
                "/v1/users",
                "{\"username\":\""
                    + ACTOR
                    + "\",\"firstname\":\"WEB1259\",\"lastname\":\"Cashier\",\"email\":\"web1259@example.test\",\"officeId\":1,\"staffId\":"
                    + staff
                    + ",\"roles\":["
                    + role
                    + "],\"password\":\"WEB1259-Test-A1!\",\"repeatPassword\":\"WEB1259-Test-A1!\",\"sendPasswordToEmail\":false}"));
    cashType =
        resource(
            ok(
                "mifos",
                "POST",
                "/v1/paymenttypes",
                "{\"name\":\"WEB1259 Cash\",\"isCashPayment\":true,\"position\":1}"));
    checkType =
        resource(
            ok(
                "mifos",
                "POST",
                "/v1/paymenttypes",
                "{\"name\":\"WEB1259 Check\",\"isCashPayment\":false,\"position\":2}"));
    long cashGl = gl("WEB1259-CASH", 1), settlementGl = gl("WEB1259-SETTLEMENT", 2);
    executeSqlInDefaultTenant(
        """
        INSERT INTO m_service_payment_denomination(identifier,currency_code,value,denomination_type,active)
        VALUES('web1259-note',%s,100,'BANKNOTE',true),('web1259-small',%s,10,'BANKNOTE',true)
        ON CONFLICT(currency_code,value) DO UPDATE SET active=true;
        INSERT INTO m_service_payment_service(code,name,active,currency_code,commission_type,commission_value,commission_vat_rate,cash_gl_account_id,settlement_gl_account_id)
        VALUES('WEB1259-SERVICE','Reversible utility',true,%s,'FIXED',0,0,%s,%s);
        INSERT INTO m_base_teller_bank(code,name,active) VALUES('WEB1259-BANK','Reversal Bank',true);
        """,
        currency, currency, currency, cashGl, settlementGl);
    serviceId =
        Long.parseLong(
            querySingleValueInDefaultTenant(
                "SELECT id FROM m_service_payment_service WHERE code='WEB1259-SERVICE'"));
    bank =
        Long.parseLong(
            querySingleValueInDefaultTenant(
                "SELECT id FROM m_base_teller_bank WHERE code='WEB1259-BANK'"));
    note =
        querySingleValueInDefaultTenant(
            "SELECT identifier FROM m_service_payment_denomination WHERE currency_code="
                + sqlLiteral(currency)
                + " AND value=100");
    small =
        querySingleValueInDefaultTenant(
            "SELECT identifier FROM m_service_payment_denomination WHERE currency_code="
                + sqlLiteral(currency)
                + " AND value=10");
    client =
        resource(
            ok(
                "mifos",
                "POST",
                "/v1/clients",
                "{\"officeId\":1,\"legalFormId\":1,\"firstname\":\"WEB1259\",\"lastname\":\"Depositor\",\"active\":true,\"activationDate\":\""
                    + date
                    + "\",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}"));
    long savingsControl = gl("WEB1259-SAVINGS", 2),
        interest = gl("WEB1259-INTEREST", 5),
        fee = gl("WEB1259-FEE", 4),
        suspense = gl("WEB1259-SUSPENSE", 2);
    long product =
        resource(
            ok(
                "mifos",
                "POST",
                "/v1/savingsproducts",
                "{\"name\":\"WEB1259 Savings\",\"shortName\":\"R259\",\"description\":\"Native"
                    + " reversal test\",\"currencyCode\":\""
                    + currency
                    + "\",\"digitsAfterDecimal\":2,\"nominalAnnualInterestRate\":0,\"interestCompoundingPeriodType\":1,\"interestPostingPeriodType\":4,\"interestCalculationType\":1,\"interestCalculationDaysInYearType\":365,\"accountingRule\":2,\"savingsReferenceAccountId\":"
                    + cashGl
                    + ",\"savingsControlAccountId\":"
                    + savingsControl
                    + ",\"interestOnSavingsAccountId\":"
                    + interest
                    + ",\"incomeFromFeeAccountId\":"
                    + fee
                    + ",\"incomeFromPenaltyAccountId\":"
                    + fee
                    + ",\"transfersInSuspenseAccountId\":"
                    + suspense
                    + ",\"overdraftPortfolioControlId\":"
                    + cashGl
                    + ",\"incomeFromInterestId\":"
                    + fee
                    + ",\"writeOffAccountId\":"
                    + interest
                    + ",\"locale\":\"en\"}"));
    account =
        resource(
            ok(
                "mifos",
                "POST",
                "/v1/savingsaccounts",
                "{\"clientId\":"
                    + client
                    + ",\"productId\":"
                    + product
                    + ",\"submittedOnDate\":\""
                    + date
                    + "\",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}"));
    String dates =
        "{\"approvedOnDate\":\"" + date + "\",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}";
    ok("mifos", "POST", "/v1/savingsaccounts/" + account + "?command=approve", dates);
    ok(
        "mifos",
        "POST",
        "/v1/savingsaccounts/" + account + "?command=activate",
        dates.replace("approvedOnDate", "activatedOnDate"));
  }

  private static long service(String key) {
    return ok(
            ACTOR,
            "POST",
            "/v2/base-teller/service-payments",
            "{\"idempotencyKey\":\""
                + key
                + "\",\"payerType\":\"NON_CLIENT\",\"payerName\":\"WEB1259 Payer\",\"serviceId\":"
                + serviceId
                + ",\"serviceReference\":\""
                + key
                + "\",\"baseAmount\":1000,\"currencyCode\":\""
                + currency
                + "\",\"businessDate\":\""
                + date
                + "\",\"paymentTypeId\":"
                + cashType
                + ",\"denominations\":[{\"denominationId\":\""
                + note
                + "\",\"quantity\":10}]}")
        .jsonPath()
        .getLong("transactionId");
  }

  private static String authorization(String historyId) {
    return ok(
            "mifos",
            "POST",
            ROOT + "/authorizations",
            "{\"historyId\":\"" + historyId + "\",\"cashierUserId\":" + actorId + "}")
        .jsonPath()
        .getString("authorizationNumber");
  }

  private static String body(String historyId, String key, String number, long quantity) {
    return "{\"historyId\":\""
        + historyId
        + "\",\"idempotencyKey\":\""
        + key
        + "\",\"authorizationNumber\":\""
        + number
        + "\",\"cashDenominations\":[{\"denominationId\":\""
        + note
        + "\",\"quantity\":"
        + quantity
        + "}]}";
  }

  private static void rejected(Response response) {
    assertTrue(
        response.statusCode() >= 400 && response.statusCode() < 500,
        "Expected business validation response");
  }

  private static BigDecimal cashBalance() {
    Response response =
        ok(
            ACTOR,
            "GET",
            "/v2/base-teller/cash-inventory?custodianKey=TELLER:"
                + cashier
                + "&transactionType=CASH&currencyCode="
                + currency,
            null);
    return new BigDecimal(response.jsonPath().getString("[0].balance"));
  }

  private static long quantity(String denomination) {
    return ok(
            ACTOR,
            "GET",
            "/v2/base-teller/cash-exchanges/denominations?cashierId="
                + cashier
                + "&currencyCode="
                + currency,
            null)
        .jsonPath()
        .getLong(
            "denominations.find { it.denominationId == '" + denomination + "' }.availableQuantity");
  }

  private static String snapshot() {
    return querySingleValueInDefaultTenant(
        "SELECT CONCAT((SELECT COUNT(*) FROM m_transaction_reversal WHERE cashier_id="
            + cashier
            + "),':',(SELECT COUNT(*) FROM acc_gl_journal_entry),':',(SELECT COUNT(*) FROM"
            + " acc_gl_journal_entry WHERE reversed),':',(SELECT COALESCE(SUM(quantity),0) FROM"
            + " m_transaction_reversal_denomination),':',(SELECT COUNT(*) FROM"
            + " m_transaction_reversal_authorization WHERE consumed_at IS NOT NULL))");
  }

  @Test
  @Order(1)
  void searchPreviewAuthorizationAndNativeJournalReversalAreAtomicAndIdempotent() {
    long id = service("web1259-service-success");
    String history = "SERVICE_PAYMENT:" + id;
    String code = authorization(history),
        request = body(history, "web1259-reversal-success", code, 10);
    ok(ACTOR, "GET", ROOT + "/context", null);
    assertTrue(
        ok(ACTOR, "GET", ROOT + "/transactions?transactionDate=" + date + "&limit=1&offset=0", null)
                .jsonPath()
                .getLong("totalFilteredRecords")
            > 0);
    assertEquals(
        0,
        ok(ACTOR, "GET", ROOT + "/transactions?transactionDate=2000-01-01", null)
            .jsonPath()
            .getLong("totalFilteredRecords"));
    assertTrue(
        ok(ACTOR, "GET", ROOT + "/" + history, null)
            .jsonPath()
            .getBoolean("eligibility.reversible"));
    String before = snapshot();
    rejected(call(ACTOR, "POST", ROOT, body(history, "bad-auth", "invalid", 10)));
    rejected(call(ACTOR, "POST", ROOT, body(history, "bad-cash", code, 9)));
    rejected(
        call(
            ACTOR,
            "POST",
            ROOT,
            request.replace(note, small).replace("\"quantity\":10", "\"quantity\":100")));
    assertEquals(before, snapshot());
    ok(ACTOR, "POST", ROOT + "/preview", request);
    assertEquals(before, snapshot());
    BigDecimal prior = cashBalance();
    long priorQuantity = quantity(note);
    Response reversed = ok(ACTOR, "POST", ROOT, request);
    long reversalId = reversed.jsonPath().getLong("id");
    assertEquals(prior.subtract(new BigDecimal("1000")), cashBalance());
    assertEquals(priorQuantity - 10, quantity(note));
    assertEquals(
        "t",
        querySingleValueInDefaultTenant(
            "SELECT BOOL_AND(j.reversed) FROM acc_gl_journal_entry j JOIN m_service_payment p ON"
                + " p.accounting_transaction_id=j.transaction_id WHERE p.id="
                + id));
    assertEquals(reversalId, ok(ACTOR, "POST", ROOT, request).jsonPath().getLong("id"));
    rejected(call(ACTOR, "POST", ROOT, body(history, "different-key", code, 10)));
    assertEquals(
        "REVERSED",
        ok(ACTOR, "GET", "/v2/base-teller/transaction-history/" + history, null)
            .jsonPath()
            .getString("status"));
    assertEquals(
        history,
        ok(ACTOR, "GET", ROOT + "/reversals/" + reversalId, null)
            .jsonPath()
            .getString("originalHistoryId"));
    assertEquals(
        1,
        Long.parseLong(
            querySingleValueInDefaultTenant(
                "SELECT COUNT(*) FROM m_transaction_reversal WHERE original_history_id="
                    + sqlLiteral(history))));
  }

  @Test
  @Order(2)
  void concurrentDifferentRequestsReverseExactlyOnce() throws Exception {
    String history = "SERVICE_PAYMENT:" + service("web1259-service-race");
    String code = authorization(history);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var results =
          pool.invokeAll(
              List.<java.util.concurrent.Callable<Response>>of(
                  () -> call(ACTOR, "POST", ROOT, body(history, "race-a", code, 10)),
                  () -> call(ACTOR, "POST", ROOT, body(history, "race-b", code, 10))));
      int successes = 0;
      for (var result : results) {
        Response response = result.get();
        if (response.statusCode() == 200) successes++;
        else rejected(response);
      }
      assertEquals(1, successes);
    }
    assertEquals(
        "1",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_transaction_reversal WHERE original_history_id="
                + sqlLiteral(history)));
  }

  @Test
  @Order(3)
  void auditFailureRollsBackNativeAccountingAndAuthorization() {
    long id = service("web1259-service-rollback");
    String history = "SERVICE_PAYMENT:" + id,
        code = authorization(history),
        request = body(history, "rollback", code, 10);
    String before = snapshot();
    BigDecimal balance = cashBalance();
    executeSqlInDefaultTenant(
        "CREATE FUNCTION web1259_fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE"
            + " EXCEPTION 'WEB1259 injected audit failure'; END $$; CREATE TRIGGER web1259_fail"
            + " BEFORE INSERT ON m_transaction_reversal_denomination FOR EACH ROW EXECUTE FUNCTION"
            + " web1259_fail_audit();");
    try {
      assertTrue(call(ACTOR, "POST", ROOT, request).statusCode() >= 400);
    } finally {
      executeSqlInDefaultTenant(
          "DROP TRIGGER web1259_fail ON m_transaction_reversal_denomination; DROP FUNCTION"
              + " web1259_fail_audit();");
    }
    assertEquals(before, snapshot());
    assertEquals(balance, cashBalance());
    ok(ACTOR, "POST", ROOT, request);
  }

  @Test
  @Order(4)
  void authorizationCannotBeForgedExpiredTransferredOrSelfApproved() {
    String history = "SERVICE_PAYMENT:" + service("web1259-service-auth");
    String code = authorization(history);
    rejected(
        call(
            ACTOR,
            "POST",
            ROOT + "/authorizations",
            "{\"historyId\":\"" + history + "\",\"cashierUserId\":" + actorId + "}"));
    rejected(call(ACTOR, "POST", ROOT, body(history, "missing", "", 10)));
    executeSqlInDefaultTenant(
        "UPDATE m_transaction_reversal_authorization SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1"
            + " minute' WHERE history_id=%s;",
        history);
    rejected(call(ACTOR, "POST", ROOT + "/preview", body(history, "expired", code, 10)));
    String other = "SERVICE_PAYMENT:" + service("web1259-service-auth-other");
    rejected(call(ACTOR, "POST", ROOT, body(other, "transferred", authorization(history), 10)));
    ok(ACTOR, "POST", ROOT, body(other, "other-valid", authorization(other), 10));
    ok(ACTOR, "POST", ROOT, body(history, "auth-valid", authorization(history), 10));
  }

  private static Response deposit(String key, boolean check) {
    String funding =
        check
            ? "{\"type\":\"CHECK\",\"amount\":1000,\"currencyCode\":\""
                + currency
                + "\",\"paymentTypeId\":"
                + checkType
                + ",\"checks\":[{\"checkType\":\"OWN_BANK\",\"bank\":\"Reversal"
                + " Bank\",\"checkNumber\":\""
                + key
                + "\",\"amount\":1000,\"clearingStatus\":\"CLEARED\"}]}"
            : "{\"type\":\"CASH\",\"amount\":1000,\"currencyCode\":\""
                + currency
                + "\",\"paymentTypeId\":"
                + cashType
                + ",\"denominations\":[{\"denominationId\":\""
                + note
                + "\",\"value\":100,\"quantity\":10}]}";
    return ok(
        ACTOR,
        "POST",
        "/v2/base-teller/deposits",
        "{\"idempotencyKey\":\""
            + key
            + "\",\"clientId\":"
            + client
            + ",\"savingsAccountId\":"
            + account
            + ",\"transactionDate\":\""
            + date
            + "\",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\",\"funding\":"
            + funding
            + "}");
  }

  @Test
  @Order(5)
  void savingsCashUndoRestoresAccountAndDrawer() {
    BigDecimal balance =
        new BigDecimal(
            querySingleValueInDefaultTenant(
                "SELECT account_balance_derived FROM m_savings_account WHERE id=" + account));
    Response deposit = deposit("web1259-savings-cash", false);
    long id =
        Long.parseLong(
            querySingleValueInDefaultTenant(
                "SELECT id FROM m_base_teller_deposit WHERE receipt_number="
                    + sqlLiteral(deposit.jsonPath().getString("receiptNumber"))));
    String history = "SAVINGS_DEPOSIT:" + id;
    ok(ACTOR, "POST", ROOT, body(history, "savings-cash-undo", authorization(history), 10));
    assertEquals(
        0,
        balance.compareTo(
            new BigDecimal(
                querySingleValueInDefaultTenant(
                    "SELECT account_balance_derived FROM m_savings_account WHERE id=" + account))));
    assertEquals(
        "t",
        querySingleValueInDefaultTenant(
            "SELECT st.is_reversed FROM m_savings_account_transaction st JOIN m_base_teller_deposit"
                + " d ON d.savings_transaction_id=st.id WHERE d.id="
                + id));
  }

  @Test
  @Order(6)
  void savingsCheckIsLinkedValidatedRemovedFromCustodyAndCannotBeSelectedAgain() {
    Response deposit = deposit("web1259-savings-check", true);
    long id =
        Long.parseLong(
            querySingleValueInDefaultTenant(
                "SELECT id FROM m_base_teller_deposit WHERE receipt_number="
                    + sqlLiteral(deposit.jsonPath().getString("receiptNumber"))));
    String history = "SAVINGS_DEPOSIT:" + id;
    Response detail = ok(ACTOR, "GET", ROOT + "/" + history, null);
    long check = detail.jsonPath().getLong("checks[0].id");
    String code = authorization(history);
    String request =
        "{\"historyId\":\""
            + history
            + "\",\"idempotencyKey\":\"check-undo\",\"authorizationNumber\":\""
            + code
            + "\",\"checks\":[{\"id\":"
            + check
            + ",\"bankId\":"
            + bank
            + ",\"checkNumber\":\"web1259-savings-check\",\"amount\":1000}]}";
    for (String invalid :
        List.of(
            request.replace("\"bankId\":" + bank, "\"bankId\":999999"),
            request.replace("\"amount\":1000", "\"amount\":999"),
            request.replace("web1259-savings-check", "unrelated")))
      rejected(call(ACTOR, "POST", ROOT, invalid));
    ok(ACTOR, "POST", ROOT, request);
    assertEquals(
        "1",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_transaction_reversal_check WHERE source_type='SAVINGS_DEPOSIT'"
                + " AND source_check_id="
                + check));
    Response closing =
        ok(ACTOR, "GET", "/v2/base-teller/end-of-day-closing/checks?cashierId=" + cashier, null);
    assertFalse(closing.jsonPath().getList("id", Long.class).contains(check));
  }

  private static Response postAdmin(String path, String body) {
    return ok("mifos", "POST", "/v1" + path, body);
  }

  @Test
  @Order(7)
  void nativeLoanCashAndClearedCheckUndoRestoreLoanAndCustody() {
    final Long fund = gl("WEB1259-L-FUND", 1);
    final Long portfolio = gl("WEB1259-L-PORT", 1);
    final Long interest = gl("WEB1259-L-INT", 4);
    final Long fee = gl("WEB1259-L-FEE", 4);
    final Long penalty = gl("WEB1259-L-PEN", 4);
    final Long recovery = gl("WEB1259-L-REC", 4);
    final Long writeOff = gl("WEB1259-L-WOFF", 5);
    final Long suspense = gl("WEB1259-L-SUSP", 1);
    final Long overpayment = gl("WEB1259-L-OVER", 2);
    final String product =
        "{\"name\":\"WEB-1259 Loan\",\"shortName\":\"R25L\",\"currencyCode\":\""
            + currency
            + "\",\"digitsAfterDecimal\":2,\"inMultiplesOf\":0,\"principal\":1000,\"minPrincipal\":100,\"maxPrincipal\":2000,"
            + "\"numberOfRepayments\":10,\"repaymentEvery\":1,\"repaymentFrequencyType\":2,\"interestRatePerPeriod\":10,"
            + "\"interestRateFrequencyType\":3,\"amortizationType\":1,\"interestType\":0,\"interestCalculationPeriodType\":1,"
            + "\"daysInYearType\":365,\"daysInMonthType\":30,\"isInterestRecalculationEnabled\":false,"
            + "\"transactionProcessingStrategyCode\":\"mifos-standard-strategy\",\"accountingRule\":2,\"fundSourceAccountId\":"
            + fund
            + ",\"loanPortfolioAccountId\":"
            + portfolio
            + ",\"interestOnLoanAccountId\":"
            + interest
            + ",\"incomeFromFeeAccountId\":"
            + fee
            + ",\"incomeFromPenaltyAccountId\":"
            + penalty
            + ",\"incomeFromRecoveryAccountId\":"
            + recovery
            + ",\"writeOffAccountId\":"
            + writeOff
            + ",\"transfersInSuspenseAccountId\":"
            + suspense
            + ",\"overpaymentLiabilityAccountId\":"
            + overpayment
            + ",\"locale\":\"en\"}";
    final Long productId = resource(postAdmin("/loanproducts", product));
    final String application =
        "{\"clientId\":"
            + client
            + ",\"loanType\":\"individual\",\"productId\":"
            + productId
            + ",\"principal\":1000,\"loanTermFrequency\":10,\"loanTermFrequencyType\":2,\"numberOfRepayments\":10,"
            + "\"repaymentEvery\":1,\"repaymentFrequencyType\":2,\"interestRatePerPeriod\":10,\"interestRateFrequencyType\":3,"
            + "\"amortizationType\":1,\"interestType\":0,\"interestCalculationPeriodType\":1,"
            + "\"transactionProcessingStrategyCode\":\"mifos-standard-strategy\",\"expectedDisbursementDate\":\""
            + date
            + "\",\"submittedOnDate\":\""
            + date
            + "\",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}";
    long loanId = resource(postAdmin("/loans", application));
    postAdmin(
        "/loans/" + loanId + "?command=approve",
        "{\"approvedLoanAmount\":1000,\"approvedOnDate\":\""
            + date
            + "\",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}");
    postAdmin(
        "/loans/" + loanId + "?command=disburse",
        "{\"actualDisbursementDate\":\""
            + date
            + "\",\"transactionAmount\":1000,\"paymentTypeId\":"
            + cashType
            + ",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}");

    String principalBefore =
        querySingleValueInDefaultTenant(
            "SELECT principal_outstanding_derived FROM m_loan WHERE id=" + loanId);
    for (boolean check : List.of(false, true)) {
      String key = check ? "web1259-loan-check" : "web1259-loan-cash";
      String detail =
          check
              ? "\"check\":{\"bankId\":"
                  + bank
                  + ",\"checkType\":\"PERSONAL\",\"checkNumber\":\"loan-check\",\"classification\":\"CLEARED_FUNDS\"}"
              : "\"denominations\":[{\"denominationId\":\"" + note + "\",\"quantity\":1}]";
      Response payment =
          ok(
              ACTOR,
              "POST",
              "/v2/base-teller/credit-payments",
              "{\"idempotencyKey\":\""
                  + key
                  + "\",\"clientId\":"
                  + client
                  + ",\"loanId\":"
                  + loanId
                  + ",\"paymentMethod\":\""
                  + (check ? "CHECK" : "CASH")
                  + "\",\"amount\":100,\"currencyCode\":\""
                  + currency
                  + "\",\"paymentTypeId\":"
                  + (check ? checkType : cashType)
                  + ","
                  + detail
                  + "}");
      String history = "CREDIT_PAYMENT:" + payment.jsonPath().getLong("id"),
          number = authorization(history);
      String reversal =
          check
              ? "{\"historyId\":\""
                  + history
                  + "\",\"idempotencyKey\":\""
                  + key
                  + "-undo\",\"authorizationNumber\":\""
                  + number
                  + "\",\"checks\":[{\"id\":"
                  + payment.jsonPath().getLong("check.id")
                  + ",\"bankId\":"
                  + bank
                  + ",\"checkNumber\":\"loan-check\",\"amount\":100}]}"
              : body(history, key + "-undo", number, 1);
      ok(ACTOR, "POST", ROOT, reversal);
      assertEquals(
          principalBefore,
          querySingleValueInDefaultTenant(
              "SELECT principal_outstanding_derived FROM m_loan WHERE id=" + loanId));
      assertEquals(
          "t",
          querySingleValueInDefaultTenant(
              "SELECT is_reversed FROM m_loan_transaction WHERE id="
                  + payment.jsonPath().getLong("loanTransactionId")));
      if (check)
        rejected(
            call(
                ACTOR,
                "POST",
                "/v2/base-teller/credit-payments/checks/"
                    + payment.jsonPath().getLong("check.id")
                    + "/return",
                "{\"idempotencyKey\":\"already-reversed-check\",\"reason\":\"NSF\"}"));
    }
  }

  @Test
  @Order(8)
  void inverseExchangeReturnsDeliveredCashWithoutFinancialMovement() {
    service("web1259-exchange-funding");
    long beforeQuantity = quantity(note);
    long id =
        ok(
                ACTOR,
                "POST",
                "/v2/base-teller/cash-exchanges",
                "{\"cashierId\":"
                    + cashier
                    + ",\"currencyCode\":\""
                    + currency
                    + "\",\"receivedDenominations\":[{\"denominationId\":\""
                    + small
                    + "\",\"quantity\":100}],\"deliveredDenominations\":[{\"denominationId\":\""
                    + note
                    + "\",\"quantity\":10}],\"idempotencyKey\":\"web1259-exchange\"}")
            .jsonPath()
            .getLong("id");
    String history = "CASH_EXCHANGE:" + id,
        code = authorization(history),
        request =
            "{\"historyId\":\""
                + history
                + "\",\"idempotencyKey\":\"exchange-undo\",\"authorizationNumber\":\""
                + code
                + "\",\"cashDenominations\":[{\"denominationId\":\""
                + small
                + "\",\"quantity\":100}],\"returnedCashDenominations\":[{\"denominationId\":\""
                + note
                + "\",\"quantity\":10}]}";
    String journals = querySingleValueInDefaultTenant("SELECT COUNT(*) FROM acc_gl_journal_entry");
    ok(ACTOR, "POST", ROOT, request);
    assertEquals(
        journals, querySingleValueInDefaultTenant("SELECT COUNT(*) FROM acc_gl_journal_entry"));
    assertEquals(
        "REVERSED",
        ok(ACTOR, "GET", "/v2/base-teller/cash-exchanges/" + id, null)
            .jsonPath()
            .getString("status"));
    assertEquals(beforeQuantity, quantity(note));
    assertEquals(0, quantity(small));
  }

  @Test
  @Order(9)
  void unsupportedSourceAndPermissionDeniedDoNotChangeStateAndClosingSeesCorrectCustody() {
    String history =
        querySingleValueInDefaultTenant(
            "SELECT CONCAT('CASHIER_TRANSACTION:',id) FROM m_cashier_transactions WHERE cashier_id="
                + cashier
                + " ORDER BY id LIMIT 1");
    // Linked cashier rows have no independent history identity; a fabricated ID is also rejected.
    String before = snapshot();
    rejected(call(ACTOR, "POST", ROOT, body(history, "unsupported", "invalid", 10)));
    assertEquals(before, snapshot());
    rejected(
        call(
            "mifos",
            "GET",
            ROOT + "/" + (privateHistory = "SERVICE_PAYMENT:" + service("web1259-private")),
            null));
    String request =
        "{\"cashierId\":"
            + cashier
            + ",\"businessDate\":\""
            + date
            + "\",\"idempotencyKey\":\"web1259-eod-preview\",\"currencies\":[{\"currencyCode\":\""
            + currency
            + "\",\"denominations\":[{\"denominationId\":\""
            + note
            + "\",\"quantity\":20}],\"vouchers\":0,\"checks\":[]}]}";
    String closingPath = "/v2/base-teller/end-of-day-closing";
    List<String> configuredCurrencies =
        ok(ACTOR, "GET", closingPath + "/context", null)
            .jsonPath()
            .getList("currencies.currencyCode");
    assertTrue(configuredCurrencies.contains(currency));
    String beforeClosing = snapshot();
    if (configuredCurrencies.size() > 1) {
      // A preceding cash-exchange suite can enable another tenant currency. Closing must still
      // reject incomplete declarations; the successful preview below must declare every currency.
      Response incomplete = call(ACTOR, "POST", closingPath + "/preview", request);
      assertEquals(403, incomplete.statusCode());
      assertEquals(
          "error.msg.base.teller.end.of.day.currency.incomplete",
          incomplete.jsonPath().getString("errors[0].userMessageGlobalisationCode"));
      assertEquals(beforeClosing, snapshot());
    }
    JsonObject completeRequest = JsonParser.parseString(request).getAsJsonObject();
    for (String code : configuredCurrencies) {
      if (code.equals(currency)) continue;
      JsonObject declaration = new JsonObject();
      declaration.addProperty("currencyCode", code);
      declaration.add("denominations", new JsonArray());
      declaration.addProperty("vouchers", 0);
      declaration.add("checks", new JsonArray());
      completeRequest.getAsJsonArray("currencies").add(declaration);
    }
    Response closing = ok(ACTOR, "POST", closingPath + "/preview", completeRequest.toString());
    List<Map<String, Object>> balances = closing.jsonPath().getList("balancesByCurrency");
    assertEquals(configuredCurrencies.size(), balances.size());
    assertEquals(
        Set.copyOf(configuredCurrencies),
        Set.copyOf(balances.stream().map(b -> (String) b.get("currencyCode")).toList()));
    for (var balance : balances) {
      BigDecimal expected =
          currency.equals(balance.get("currencyCode")) ? new BigDecimal("2000") : BigDecimal.ZERO;
      assertEquals(0, expected.compareTo(new BigDecimal(balance.get("expectedCash").toString())));
    }
    assertEquals(beforeClosing, snapshot());
  }

  @Test
  @Order(10)
  void readPermissionCannotCreateAuthorizeOrSeeAnotherOffice() {
    long role =
        resource(
            ok(
                "mifos",
                "POST",
                "/v1/roles",
                "{\"name\":\"WEB1259 Read Only\",\"description\":\"Restricted reversal test\"}"));
    ok(
        "mifos",
        "PUT",
        "/v1/roles/" + role + "/permissions",
        "{\"permissions\":{\"READ_BASE_TELLER_TRANSACTION_REVERSAL\":true}}");
    long office =
        resource(
            ok(
                "mifos",
                "POST",
                "/v1/offices",
                "{\"name\":\"WEB1259 Other Office\",\"parentId\":1,\"openingDate\":\""
                    + date
                    + "\",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}"));
    long staff =
        resource(
            ok(
                "mifos",
                "POST",
                "/v1/staff",
                "{\"officeId\":"
                    + office
                    + ",\"firstname\":\"Other\",\"lastname\":\"Cashier\",\"isLoanOfficer\":false}"));
    long teller =
        resource(
            ok(
                "mifos",
                "POST",
                "/v1/tellers",
                "{\"officeId\":"
                    + office
                    + ",\"name\":\"WEB1259 Other Window\",\"status\":300,\"startDate\":\""
                    + date
                    + "\",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}"));
    resource(
        ok(
            "mifos",
            "POST",
            "/v1/tellers/" + teller + "/cashiers",
            "{\"staffId\":"
                + staff
                + ",\"isFullDay\":true,\"startDate\":\""
                + date
                + "\",\"endDate\":\""
                + date
                + "\",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}"));
    String user = "web1259-other-office";
    resource(
        ok(
            "mifos",
            "POST",
            "/v1/users",
            "{\"username\":\""
                + user
                + "\",\"firstname\":\"Other\",\"lastname\":\"Cashier\",\"email\":\"other-web1259@example.test\",\"officeId\":"
                + office
                + ",\"staffId\":"
                + staff
                + ",\"roles\":["
                + role
                + "],\"password\":\"WEB1259-Test-A1!\",\"repeatPassword\":\"WEB1259-Test-A1!\",\"sendPasswordToEmail\":false}"));
    ok(user, "GET", ROOT + "/context", null);
    assertEquals(
        0,
        ok(user, "GET", ROOT + "/transactions?transactionDate=" + date, null)
            .jsonPath()
            .getLong("totalFilteredRecords"));
    rejected(call(user, "GET", ROOT + "/" + privateHistory, null));
    rejected(call(user, "POST", ROOT, body(privateHistory, "forbidden-create", "invalid", 10)));
    rejected(
        call(
            user,
            "POST",
            ROOT + "/authorizations",
            "{\"historyId\":\"" + privateHistory + "\",\"cashierUserId\":" + actorId + "}"));
  }

  @Test
  @Order(11)
  void completedClosingFreezesReversalWithoutReopeningCustody() {
    var activities =
        List.of(
            org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity
                .CASH_AT_MAINVAULT,
            org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity
                .CASH_AT_TELLER);
    for (var activity : activities) {
      String existing =
          querySingleValueInDefaultTenant(
              "SELECT id FROM acc_gl_financial_activity_account WHERE financial_activity_type="
                  + activity.getValue());
      if (existing.isBlank())
        ok(
            "mifos",
            "POST",
            "/v1/financialactivityaccounts",
            "{\"financialActivityId\":"
                + activity.getValue()
                + ",\"glAccountId\":"
                + gl("WEB1259-CLOSE-" + activity.name(), 1)
                + "}");
    }
    String number = authorization(privateHistory);
    ok(
        "mifos",
        "POST",
        "/v2/base-teller/closings",
        "{\"idempotencyKey\":\"web1259-close\",\"cashierId\":"
            + cashier
            + ",\"businessDate\":\""
            + date
            + "\",\"currencyCode\":\""
            + currency
            + "\",\"denominations\":[{\"denominationId\":\""
            + note
            + "\",\"value\":100,\"quantity\":20}],\"checkIds\":[]}");
    String before = snapshot();
    assertFalse(
        ok(ACTOR, "GET", ROOT + "/" + privateHistory, null)
            .jsonPath()
            .getBoolean("eligibility.reversible"));
    rejected(call(ACTOR, "POST", ROOT, body(privateHistory, "after-close", number, 10)));
    assertEquals(before, snapshot());
    assertEquals(0, cashBalance().compareTo(BigDecimal.ZERO));
  }
}
