package org.apache.fineract.baseteller.transfer;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real native accounts and financial commands. Setup never inserts financial state through SQL. */
class AccountTransfersApiHttpIntegrationTest extends AccountTransferNativeTestSupport {
  private static final Gson GSON = new Gson();
  private static final String ROOT = "/v2/base-teller/account-transfers";
  private static final String API = SavingsTestUtils.CONTEXT_PATH + "/api";
  private static String date, currency, suffix;
  private static long clientA, clientB, product, payment;
  private long source, own, third;

  private static Response call(String method, String path, Object body) {
    var request =
        given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"));
    if (body != null) request.body(GSON.toJson(body));
    return request.request(method, API + path);
  }

  private static Response ok(String method, String path, Object body) {
    var result = call(method, path, body);
    assertEquals(200, result.statusCode(), () -> path + ": " + result.asString());
    return result;
  }

  private static long resource(String path, Object body) {
    return ok("POST", path, body).jsonPath().getLong("resourceId");
  }

  @BeforeAll
  static void setupClientsAndProduct() {
    var context = ok("GET", ROOT + "/context", null).jsonPath();
    date = context.getString("businessDate");
    currency = context.getString("currencies[0]");
    suffix = UUID.randomUUID().toString().substring(0, 8);
    clientA = client("Ordering");
    clientB = client("Beneficiary");
    payment =
        resource(
            "/v1/paymenttypes",
            Map.of("name", "WEB1261 funding " + suffix, "isCashPayment", false, "position", 1));
    product =
        resource(
            "/v1/savingsproducts",
            Map.ofEntries(
                Map.entry("name", "WEB1261 " + suffix),
                    Map.entry("shortName", suffix.substring(0, 4)),
                Map.entry("description", "Native transfer acceptance"),
                    Map.entry("currencyCode", currency),
                Map.entry("digitsAfterDecimal", 2), Map.entry("nominalAnnualInterestRate", 0),
                Map.entry("interestCompoundingPeriodType", 1),
                    Map.entry("interestPostingPeriodType", 4),
                Map.entry("interestCalculationType", 1),
                    Map.entry("interestCalculationDaysInYearType", 365),
                Map.entry("accountingRule", 1), Map.entry("locale", "en")));
  }

  private static long client(String name) {
    return resource(
        "/v1/clients",
        Map.of(
            "officeId",
            1,
            "legalFormId",
            1,
            "firstname",
            "WEB1261" + suffix,
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

  private static long account(long owner) {
    long id =
        resource(
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
        "POST",
        "/v1/savingsaccounts/" + id + "?command=approve",
        Map.of("approvedOnDate", date, "dateFormat", "yyyy-MM-dd", "locale", "en"));
    ok(
        "POST",
        "/v1/savingsaccounts/" + id + "?command=activate",
        Map.of("activatedOnDate", date, "dateFormat", "yyyy-MM-dd", "locale", "en"));
    return id;
  }

  @BeforeEach
  void fundedAccounts() {
    source = account(clientA);
    own = account(clientA);
    third = account(clientB);
    ok(
        "POST",
        "/v1/savingsaccounts/" + source + "/transactions?command=deposit",
        Map.of(
            "transactionDate",
            date,
            "transactionAmount",
            1000,
            "paymentTypeId",
            payment,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"));
  }

  private TransferRequest request(
      TransferOperation operation, long destination, String amount, String key) {
    return new TransferRequest(
        operation,
        clientA,
        source,
        TransferAccountType.SAVINGS,
        destination,
        new BigDecimal(amount),
        key);
  }

  private BigDecimal balance(long id) {
    return new BigDecimal(
        ok("GET", "/v1/savingsaccounts/" + id, null)
            .jsonPath()
            .getString("summary.accountBalance"));
  }

  private List<Map<String, Object>> transactions(long id) {
    List<Map<String, Object>> items =
        ok("GET", "/v1/savingsaccounts/" + id + "?associations=transactions", null)
            .jsonPath()
            .getList("transactions");
    return items == null ? List.of() : items;
  }

  private static List<Map<String, Object>> inventory() {
    List<Map<String, Object>> rows =
        ok("GET", "/v2/base-teller/cash-inventory", null).jsonPath().getList("");
    return rows.stream()
        .map(
            row -> {
              Map<String, Object> amounts = new java.util.HashMap<>(row);
              amounts.remove(
                  "asOf"); // Observation time changes; all custody and monetary fields must match.
              return amounts;
            })
        .toList();
  }

  @Test
  void ownTransferPreviewIdempotencyReceiptHistoryAndPhysicalCustody() {
    var inventory = inventory();
    var closing =
        ok("GET", "/v2/base-teller/end-of-day-closing/context", null).jsonPath().getMap("");
    var r =
        request(TransferOperation.BETWEEN_MY_ACCOUNTS, own, "100", UUID.randomUUID().toString());
    var preview = ok("POST", ROOT + "/preview", r).jsonPath();
    assertTrue(preview.getBoolean("balanced"));
    assertEquals(preview.getMap("inflowsByCurrency"), preview.getMap("outflowsByCurrency"));
    assertEquals(0, balance(source).compareTo(new BigDecimal("1000")));
    int sourceCount = transactions(source).size(), targetCount = transactions(own).size();
    var posted = ok("POST", ROOT, r).jsonPath();
    long id = posted.getLong("transferId");
    assertEquals(id, ok("POST", ROOT, r).jsonPath().getLong("transferId"));
    assertEquals(0, balance(source).compareTo(new BigDecimal("900")));
    assertEquals(0, balance(own).compareTo(new BigDecimal("100")));
    assertEquals(sourceCount + 1, transactions(source).size());
    assertEquals(targetCount + 1, transactions(own).size());
    assertNotNull(posted.get("sourceTransactionId"));
    assertNotNull(posted.get("destinationTransactionId"));
    assertEquals("COMPLETED", ok("GET", ROOT + "/" + id, null).jsonPath().getString("status"));
    assertEquals(
        id, ok("GET", ROOT + "/" + id + "/receipt", null).jsonPath().getLong("transferId"));
    var history =
        ok(
                "GET",
                "/v2/base-teller/transaction-history?reference="
                    + id
                    + "&operation=ACCOUNT_TRANSFER",
                null)
            .jsonPath();
    assertEquals(1, history.getInt("totalFilteredRecords"));
    assertEquals(inventory, inventory());
    assertEquals(
        closing,
        ok("GET", "/v2/base-teller/end-of-day-closing/context", null).jsonPath().getMap(""));
    assertEquals(
        "1",
        querySingleValueInDefaultTenant(
            "SELECT count(*) FROM m_base_teller_account_transfer WHERE native_transfer_id=" + id));
  }

  @Test
  void threeBeneficiaryDiscoveryMethodsResolveOneAuthoritativeDestination() {
    var nativeClient = ok("GET", "/v1/clients/" + clientB, null).jsonPath();
    String number = nativeClient.getString("accountNo");
    String accountNumber =
        ok("GET", "/v1/savingsaccounts/" + third, null).jsonPath().getString("accountNo");
    assertEquals(
        clientB,
        ok("GET", ROOT + "/clients?clientNumber=" + number, null)
            .jsonPath()
            .getLong("pageItems[0].clientId"));
    var byName =
        ok("GET", ROOT + "/clients?name=WEB1261" + suffix + "&offset=0&limit=10", null)
            .jsonPath()
            .getList("pageItems.clientId", Long.class);
    assertTrue(byName.contains(clientB));
    String params =
        "?orderingClientId="
            + clientA
            + "&sourceAccountId="
            + source
            + "&operationType=TO_THIRD_PARTY";
    assertEquals(
        third,
        ok("GET", ROOT + "/accounts/lookup" + params + "&accountNumber=" + accountNumber, null)
            .jsonPath()
            .getLong("accountId"));
    var targets =
        ok(
                "GET",
                ROOT
                    + "/clients/"
                    + clientA
                    + "/destination-accounts?sourceAccountId="
                    + source
                    + "&operationType=TO_THIRD_PARTY&beneficiaryClientId="
                    + clientB,
                null)
            .jsonPath()
            .getList("accountId", Long.class);
    assertTrue(targets.contains(third));
    ok(
        "POST",
        ROOT,
        request(TransferOperation.TO_THIRD_PARTY, third, "75", UUID.randomUUID().toString()));
    assertEquals(0, balance(third).compareTo(new BigDecimal("75")));
    assertEquals(0, balance(source).compareTo(new BigDecimal("925")));
  }

  @Test
  void invalidTargetsOwnershipAndInsufficientFundsNeverMoveMoney() {
    int before = transactions(source).size();
    for (var r :
        List.of(
            request(TransferOperation.BETWEEN_MY_ACCOUNTS, third, "1", "invalid-owner"),
            request(TransferOperation.TO_THIRD_PARTY, own, "1", "invalid-third-party"),
            request(TransferOperation.BETWEEN_MY_ACCOUNTS, source, "1", "self"),
            request(TransferOperation.BETWEEN_MY_ACCOUNTS, Long.MAX_VALUE, "1", "missing"),
            request(TransferOperation.BETWEEN_MY_ACCOUNTS, own, "1001", "insufficient"))) {
      int status = call("POST", ROOT, r).statusCode();
      assertTrue(status >= 400 && status < 500, "Unexpected status " + status);
    }
    assertEquals(before, transactions(source).size());
    assertEquals(0, balance(source).compareTo(new BigDecimal("1000")));
    assertEquals(0, balance(own).signum());
    assertEquals(0, balance(third).signum());
  }

  @Test
  void simultaneousSpendCannotOverdrawOrPartiallyCredit() throws Exception {
    List<Callable<Integer>> requests = new ArrayList<>();
    requests.add(
        () ->
            call(
                    "POST",
                    ROOT,
                    request(
                        TransferOperation.BETWEEN_MY_ACCOUNTS,
                        own,
                        "800",
                        UUID.randomUUID().toString()))
                .statusCode());
    requests.add(
        () ->
            call(
                    "POST",
                    ROOT,
                    request(
                        TransferOperation.TO_THIRD_PARTY,
                        third,
                        "800",
                        UUID.randomUUID().toString()))
                .statusCode());
    try (var executor = Executors.newFixedThreadPool(2)) {
      var results = executor.invokeAll(requests);
      List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
      assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
      assertTrue(
          statuses.stream().filter(s -> s != 200).allMatch(s -> s >= 400 && s < 500),
          statuses.toString());
    }
    assertEquals(0, balance(source).compareTo(new BigDecimal("200")));
    assertEquals(0, balance(own).add(balance(third)).compareTo(new BigDecimal("800")));
  }

  @Test
  void sameKeyWithDifferentPayloadIsRejected() {
    String key = UUID.randomUUID().toString();
    ok("POST", ROOT, request(TransferOperation.BETWEEN_MY_ACCOUNTS, own, "10", key));
    int status =
        call("POST", ROOT, request(TransferOperation.BETWEEN_MY_ACCOUNTS, own, "11", key))
            .statusCode();
    assertTrue(status >= 400 && status < 500);
    assertEquals(0, balance(source).compareTo(new BigDecimal("990")));
    assertEquals(0, balance(own).compareTo(new BigDecimal("10")));
  }

  @Test
  void failedReceiptPersistenceRollsBackBothNativeLegsAndCommand() {
    String beforeTransfers =
        querySingleValueInDefaultTenant("SELECT count(*) FROM m_account_transfer_transaction");
    int beforeSource = transactions(source).size(), beforeDestination = transactions(own).size();
    String key = "injected-" + UUID.randomUUID();
    executeSqlInDefaultTenant(
        "CREATE FUNCTION web1261_fail_receipt() RETURNS trigger LANGUAGE plpgsql AS $body$ "
            + "BEGIN IF NEW.idempotency_key="
            + sqlLiteral(key)
            + " THEN RAISE EXCEPTION 'WEB1261 injected receipt failure'; END IF; RETURN NEW; END"
            + " $body$;");
    executeSqlInDefaultTenant(
        "CREATE TRIGGER web1261_receipt_failure BEFORE INSERT ON m_base_teller_account_transfer FOR"
            + " EACH ROW EXECUTE FUNCTION web1261_fail_receipt();");
    try {
      assertEquals(
          500,
          call("POST", ROOT, request(TransferOperation.BETWEEN_MY_ACCOUNTS, own, "10", key))
              .statusCode());
    } finally {
      executeSqlInDefaultTenant(
          "DROP TRIGGER web1261_receipt_failure ON m_base_teller_account_transfer;");
      executeSqlInDefaultTenant("DROP FUNCTION web1261_fail_receipt();");
    }
    assertEquals(0, balance(source).compareTo(new BigDecimal("1000")));
    assertEquals(0, balance(own).signum());
    assertEquals(beforeSource, transactions(source).size());
    assertEquals(beforeDestination, transactions(own).size());
    assertEquals(
        beforeTransfers,
        querySingleValueInDefaultTenant("SELECT count(*) FROM m_account_transfer_transaction"));
  }

  @Test
  void savingsToLoanCreatesNativeRepaymentAndBothLinks() {
    String loanSuffix = UUID.randomUUID().toString().substring(0, 4);
    Map<String, Object> terms = new java.util.HashMap<>();
    terms.put("currencyCode", currency);
    terms.put("digitsAfterDecimal", 2);
    terms.put("inMultiplesOf", 0);
    terms.put("principal", 1000);
    terms.put("numberOfRepayments", 10);
    terms.put("repaymentEvery", 1);
    terms.put("repaymentFrequencyType", 2);
    terms.put("interestRatePerPeriod", 0);
    terms.put("interestRateFrequencyType", 3);
    terms.put("amortizationType", 1);
    terms.put("interestType", 0);
    terms.put("interestCalculationPeriodType", 1);
    terms.put("transactionProcessingStrategyCode", "mifos-standard-strategy");
    terms.put("locale", "en");
    Map<String, Object> loanProduct = new java.util.HashMap<>(terms);
    loanProduct.put("name", "WEB1261 Loan " + loanSuffix);
    loanProduct.put("shortName", loanSuffix);
    loanProduct.put("daysInYearType", 365);
    loanProduct.put("daysInMonthType", 30);
    loanProduct.put("isInterestRecalculationEnabled", false);
    loanProduct.put("accountingRule", 1);
    long loanProductId = resource("/v1/loanproducts", loanProduct);
    Map<String, Object> application = new java.util.HashMap<>(terms);
    application.remove("currencyCode");
    application.remove("digitsAfterDecimal");
    application.remove("inMultiplesOf");
    application.put("clientId", clientB);
    application.put("productId", loanProductId);
    application.put("loanType", "individual");
    application.put("loanTermFrequency", 10);
    application.put("loanTermFrequencyType", 2);
    application.put("submittedOnDate", date);
    application.put("expectedDisbursementDate", date);
    application.put("dateFormat", "yyyy-MM-dd");
    long loan = resource("/v1/loans", application);
    ok(
        "POST",
        "/v1/loans/" + loan + "?command=approve",
        Map.of(
            "approvedLoanAmount",
            1000,
            "approvedOnDate",
            date,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"));
    ok(
        "POST",
        "/v1/loans/" + loan + "?command=disburse",
        Map.of(
            "actualDisbursementDate",
            date,
            "transactionAmount",
            1000,
            "paymentTypeId",
            payment,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"));
    var r =
        new TransferRequest(
            TransferOperation.TO_THIRD_PARTY,
            clientA,
            source,
            TransferAccountType.LOAN,
            loan,
            new BigDecimal("100"),
            UUID.randomUUID().toString());
    ok("POST", ROOT + "/preview", r);
    var receipt = ok("POST", ROOT, r).jsonPath();
    assertEquals("LOAN", receipt.getString("destination.accountType"));
    assertEquals(0, balance(source).compareTo(new BigDecimal("900")));
    var account = ok("GET", "/v1/loans/" + loan + "?associations=transactions", null).jsonPath();
    assertEquals(
        0,
        new BigDecimal(account.getString("summary.principalOutstanding"))
            .compareTo(new BigDecimal("900")));
    long leg = receipt.getLong("destinationTransactionId");
    assertEquals(
        "2",
        querySingleValueInDefaultTenant(
            "SELECT transaction_type_enum FROM m_loan_transaction WHERE id=" + leg));
    assertEquals(
        String.valueOf(leg),
        querySingleValueInDefaultTenant(
            "SELECT to_loan_transaction_id FROM m_account_transfer_transaction WHERE id="
                + receipt.getLong("transferId")));
  }

  @Test
  void closedDestinationIsFilteredAndCannotBePosted() {
    ok(
        "POST",
        "/v1/savingsaccounts/" + own + "?command=close",
        Map.of("closedOnDate", date, "dateFormat", "yyyy-MM-dd", "locale", "en"));
    var ids =
        ok(
                "GET",
                ROOT
                    + "/clients/"
                    + clientA
                    + "/destination-accounts?sourceAccountId="
                    + source
                    + "&operationType=BETWEEN_MY_ACCOUNTS",
                null)
            .jsonPath()
            .getList("accountId", Long.class);
    assertFalse(ids.contains(own));
    int status =
        call(
                "POST",
                ROOT,
                request(
                    TransferOperation.BETWEEN_MY_ACCOUNTS, own, "10", UUID.randomUUID().toString()))
            .statusCode();
    assertTrue(status >= 400 && status < 500);
    assertEquals(0, balance(source).compareTo(new BigDecimal("1000")));
  }

  @Test
  void crossCurrencyDestinationCannotBeDiscoveredOrPosted() {
    var config = ok("GET", "/v1/currencies", null).jsonPath();
    List<String> selected =
        new ArrayList<>(config.getList("selectedCurrencyOptions.code", String.class));
    String other = selected.stream().filter(c -> !c.equals(currency)).findFirst().orElse(null);
    if (other == null) {
      List<Map<String, Object>> options = config.getList("currencyOptions");
      other =
          options.stream()
              .filter(c -> ((Number) c.get("decimalPlaces")).intValue() == 2)
              .map(c -> (String) c.get("code"))
              .filter(c -> !c.equals(currency))
              .max(String::compareTo)
              .orElseThrow();
      selected.add(other);
      ok("PUT", "/v1/currencies", Map.of("currencies", selected));
    }
    long otherProduct =
        resource(
            "/v1/savingsproducts",
            Map.ofEntries(
                Map.entry("name", "WEB1261 FX " + UUID.randomUUID()),
                    Map.entry("shortName", UUID.randomUUID().toString().substring(0, 4)),
                Map.entry("description", "Native cross currency rejection"),
                    Map.entry("currencyCode", other),
                Map.entry("digitsAfterDecimal", 2), Map.entry("nominalAnnualInterestRate", 0),
                Map.entry("interestCompoundingPeriodType", 1),
                    Map.entry("interestPostingPeriodType", 4),
                Map.entry("interestCalculationType", 1),
                    Map.entry("interestCalculationDaysInYearType", 365),
                Map.entry("accountingRule", 1), Map.entry("locale", "en")));
    long target =
        resource(
            "/v1/savingsaccounts",
            Map.of(
                "clientId",
                clientA,
                "productId",
                otherProduct,
                "submittedOnDate",
                date,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    ok(
        "POST",
        "/v1/savingsaccounts/" + target + "?command=approve",
        Map.of("approvedOnDate", date, "dateFormat", "yyyy-MM-dd", "locale", "en"));
    ok(
        "POST",
        "/v1/savingsaccounts/" + target + "?command=activate",
        Map.of("activatedOnDate", date, "dateFormat", "yyyy-MM-dd", "locale", "en"));
    var targets =
        ok(
                "GET",
                ROOT
                    + "/clients/"
                    + clientA
                    + "/destination-accounts?sourceAccountId="
                    + source
                    + "&operationType=BETWEEN_MY_ACCOUNTS",
                null)
            .jsonPath()
            .getList("accountId", Long.class);
    assertFalse(targets.contains(target));
    var response =
        call(
            "POST",
            ROOT,
            request(
                TransferOperation.BETWEEN_MY_ACCOUNTS, target, "10", UUID.randomUUID().toString()));
    assertTrue(response.statusCode() >= 400 && response.statusCode() < 500, response.asString());
    assertEquals(0, balance(source).compareTo(new BigDecimal("1000")));
    assertEquals(0, balance(target).signum());
  }

  @Test
  void simultaneousIdenticalKeysProduceExactlyOneNativeTransfer() throws Exception {
    var r =
        request(TransferOperation.BETWEEN_MY_ACCOUNTS, own, "100", UUID.randomUUID().toString());
    try (var executor = Executors.newFixedThreadPool(2)) {
      var results =
          executor.invokeAll(
              List.<Callable<Long>>of(
                  () -> ok("POST", ROOT, r).jsonPath().getLong("transferId"),
                  () -> ok("POST", ROOT, r).jsonPath().getLong("transferId")));
      assertEquals(results.get(0).get(), results.get(1).get());
    }
    assertEquals(0, balance(source).compareTo(new BigDecimal("900")));
    assertEquals(0, balance(own).compareTo(new BigDecimal("100")));
    assertEquals(1, transactions(own).size());
  }

  @Test
  void officeScopeCannotBeBypassedWithCraftedIdsOrExactAccountNumber() {
    String scopeSuffix = UUID.randomUUID().toString().substring(0, 8);
    long office =
        resource(
            "/v1/offices",
            Map.of(
                "name",
                "WEB1261 scope " + scopeSuffix,
                "parentId",
                1,
                "openingDate",
                date,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    long role =
        Long.parseLong(
            querySingleValueInDefaultTenant("SELECT id FROM m_role WHERE name='Super user'"));
    String username = "web1261-scope-" + scopeSuffix,
        password = UUID.randomUUID().toString().replaceAll("(.)\\1+", "$1") + "Aa1!";
    resource(
        "/v1/users",
        Map.ofEntries(
            Map.entry("username", username),
            Map.entry("firstname", "Transfer"),
            Map.entry("lastname", "Scope"),
            Map.entry("officeId", office),
            Map.entry("roles", List.of(role)),
            Map.entry("password", password),
            Map.entry("repeatPassword", password),
            Map.entry("sendPasswordToEmail", false),
            Map.entry("email", username + "@example.invalid")));
    var restricted = SavingsTestUtils.requestSpecWithAuth(getFineractPort(), username, password);
    given(restricted)
        .get(API + ROOT + "/clients?name=WEB1261" + suffix)
        .then()
        .statusCode(200)
        .body("totalFilteredRecords", org.hamcrest.Matchers.equalTo(0));
    int sourceStatus =
        given(restricted).get(API + ROOT + "/clients/" + clientA + "/source-accounts").statusCode();
    assertTrue(sourceStatus >= 400 && sourceStatus < 500);
    var r = request(TransferOperation.BETWEEN_MY_ACCOUNTS, own, "10", UUID.randomUUID().toString());
    int postStatus = given(restricted).body(GSON.toJson(r)).post(API + ROOT).statusCode();
    assertTrue(postStatus >= 400 && postStatus < 500);
    assertEquals(0, balance(source).compareTo(new BigDecimal("1000")));
    var number = ok("GET", "/v1/savingsaccounts/" + own, null).jsonPath().getString("accountNo");
    int lookupStatus =
        given(restricted)
            .queryParam("accountNumber", number)
            .queryParam("orderingClientId", clientA)
            .queryParam("sourceAccountId", source)
            .queryParam("operationType", "BETWEEN_MY_ACCOUNTS")
            .get(API + ROOT + "/accounts/lookup")
            .statusCode();
    assertTrue(lookupStatus >= 400 && lookupStatus < 500);
  }
}
