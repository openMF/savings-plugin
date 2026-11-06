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
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.apache.fineract.testing.support.SavingsIntegrationTestBase;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.*;

/** Real native postings/journals/transfer rollback; no direct financial-table seeding. */
class ProductTransactionNativeHttpIntegrationTest extends SavingsIntegrationTestBase {
  static final Gson JSON = new Gson();
  static final List<Map<String, Object>> evidence = Collections.synchronizedList(new ArrayList<>());
  static final String DATE = LocalDate.now(java.time.ZoneOffset.UTC).toString();
  String checker;
  long client, cash, control, interest, income, suspense, payment;

  Response call(String user, String method, String path, Object body, int expected) {
    var spec =
        given(
            SavingsTestUtils.requestSpecWithAuth(
                getFineractPort(), user, user.equals("mifos") ? "password" : "Be06-review-26!"));
    if (body != null) spec.body(body instanceof String ? (String) body : JSON.toJson(body));
    var response = spec.request(method, SavingsTestUtils.CONTEXT_PATH + "/api" + path);
    evidence.add(Map.of("method", method, "path", path, "status", response.statusCode()));
    if (expected > 0)
      assertEquals(
          expected, response.statusCode(), () -> method + " " + path + ": " + response.asString());
    return response;
  }

  Response call(String method, String path, Object body, int expected) {
    return call("mifos", method, path, body, expected);
  }

  long post(String path, Object body) {
    return call("POST", path, body, 200).jsonPath().getLong("resourceId");
  }

  long gl(String name, int type) {
    return post(
        "/v1/glaccounts",
        Map.of(
            "name",
            name,
            "glCode",
            UUID.randomUUID().toString().substring(0, 12),
            "type",
            type,
            "usage",
            1,
            "manualEntriesAllowed",
            true));
  }

  long client() {
    return post(
        "/v1/clients",
        Map.of(
            "officeId",
            1,
            "legalFormId",
            1,
            "firstname",
            "SyntheticBE06",
            "lastname",
            UUID.randomUUID().toString(),
            "active",
            true,
            "activationDate",
            DATE,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"));
  }

  long product(int rate) {
    var body = new LinkedHashMap<String, Object>();
    String suffix = UUID.randomUUID().toString();
    body.putAll(
        Map.of(
            "name",
            "BE06 " + suffix,
            "shortName",
            suffix.substring(0, 4),
            "description",
            "Disposable BE06 verification",
            "currencyCode",
            "MXN",
            "digitsAfterDecimal",
            2,
            "nominalAnnualInterestRate",
            rate,
            "interestCompoundingPeriodType",
            1,
            "interestPostingPeriodType",
            4,
            "interestCalculationType",
            1,
            "interestCalculationDaysInYearType",
            365));
    body.putAll(
        Map.of(
            "accountingRule",
            2,
            "savingsReferenceAccountId",
            cash,
            "savingsControlAccountId",
            control,
            "interestOnSavingsAccountId",
            interest,
            "incomeFromFeeAccountId",
            income,
            "incomeFromPenaltyAccountId",
            income,
            "transfersInSuspenseAccountId",
            suspense,
            "overdraftPortfolioControlId",
            cash,
            "incomeFromInterestId",
            income,
            "writeOffAccountId",
            interest));
    body.put("locale", "en");
    return post("/v1/savingsproducts", body);
  }

  long apply(long product, long client) {
    return call(
            "POST",
            "/v1/savingsaccounts",
            Map.of(
                "clientId",
                client,
                "productId",
                product,
                "submittedOnDate",
                DATE,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"),
            200)
        .jsonPath()
        .getLong("savingsId");
  }

  void approve(long account) {
    call(
        "POST",
        "/v1/savingsaccounts/" + account + "?command=approve",
        Map.of("approvedOnDate", DATE, "dateFormat", "yyyy-MM-dd", "locale", "en"),
        200);
  }

  void activate(long account, int expected) {
    call(
        "POST",
        "/v1/savingsaccounts/" + account + "?command=activate",
        Map.of("activatedOnDate", DATE, "dateFormat", "yyyy-MM-dd", "locale", "en"),
        expected);
  }

  long active(long product) {
    long a = apply(product, client);
    approve(a);
    activate(a, 200);
    return a;
  }

  Response transaction(long account, String op, String amount, int expected) {
    return call(
        "POST",
        "/v1/savingsaccounts/" + account + "/transactions?command=" + op,
        Map.of(
            "transactionDate",
            DATE,
            "transactionAmount",
            new java.math.BigDecimal(amount),
            "paymentTypeId",
            payment,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"),
        expected);
  }

  void policy(long product, boolean withdrawals, boolean deposits, boolean fixed) {
    String path = "/v1/savingsproducts/policy/" + product;
    var json =
        com.google.gson.JsonParser.parseString(ProductPolicyValidationTest.JSON).getAsJsonObject();
    var p = json.getAsJsonObject("policy");
    p.addProperty("withdrawalsAllowed", withdrawals);
    p.addProperty("depositsAllowed", deposits);
    if (fixed) {
      p.addProperty("classification", "INTEREST_BEARING_SIGHT");
      p.addProperty("interestMode", "FIXED");
    }
    call("PUT", path, json.toString(), 200);
    call("POST", path + "/submit", Map.of("expectedRevision", 1, "effectiveDate", DATE), 200);
    call(
        checker,
        "POST",
        path + "/versions/1/approve",
        Map.of("reason", "Independent synthetic test review"),
        200);
  }

  void historicalBinding(long account, long product) {
    // Simulates BE-02 historical binding after posting through native APIs. Only plugin metadata.
    executeSqlInDefaultTenant(
        "INSERT INTO"
            + " m_savings_account_policy(savings_account_id,savings_product_id,version,purpose,bound_at)"
            + " VALUES(%s,%s,1,'MAIN',CURRENT_TIMESTAMP);",
        account, product);
  }

  String financial() {
    return querySingleValueInDefaultTenant(
        "SELECT md5((SELECT COALESCE(string_agg(row_to_json(a)::text,',' ORDER BY id),'') FROM"
            + " m_savings_account a)||'|'||(SELECT COALESCE(string_agg(row_to_json(t)::text,','"
            + " ORDER BY id),'') FROM m_savings_account_transaction t)||'|'||(SELECT"
            + " COALESCE(string_agg(row_to_json(j)::text,',' ORDER BY id),'') FROM"
            + " acc_gl_journal_entry j)||'|'||(SELECT COALESCE(string_agg(row_to_json(c)::text,','"
            + " ORDER BY id),'') FROM m_cashier_transactions c)||'|'||(SELECT"
            + " COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY id),'') FROM"
            + " m_account_transfer_transaction t))");
  }

  void deniedTransaction(long account, String operation, String code) {
    String before = financial();
    var response = transaction(account, operation, "1.00", 400);
    assertTrue(response.asString().contains(code), response.asString());
    String after = financial();
    assertEquals(before, after);
    evidence.add(
        Map.of(
            "rejectedOperation",
            operation,
            "reason",
            code,
            "financialBefore",
            before,
            "financialAfter",
            after,
            "balanceAfter",
            querySingleValueInDefaultTenant(
                "SELECT account_balance_derived FROM m_savings_account WHERE id=" + account)));
  }

  Map<String, Object> transfer(long source, long target) {
    return Map.ofEntries(
        Map.entry("fromOfficeId", 1),
        Map.entry("fromClientId", client),
        Map.entry("fromAccountType", 2),
        Map.entry("fromAccountId", source),
        Map.entry("toOfficeId", 1),
        Map.entry("toClientId", client),
        Map.entry("toAccountType", 2),
        Map.entry("toAccountId", target),
        Map.entry("transferDate", DATE),
        Map.entry("transferAmount", 1),
        Map.entry("transferDescription", "Synthetic BE06 transfer"),
        Map.entry("dateFormat", "yyyy-MM-dd"),
        Map.entry("locale", "en"));
  }

  @Test
  void nativeDecisionsRollbackJournalsAndBothTransferLegsAndExposeAdjustmentGap() throws Exception {
    // Exact Maven module runtime is required for this evidence, not a moving develop image.
    var cp = fineract.execInContainer("cat", "/app/jib-classpath-file");
    assertEquals(
        "/be06libs/*",
        cp.getStdout().strip(),
        "Build the pinned runtime described in the BE06 guide.");
    String pluginHash =
        HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(
                        Files.readAllBytes(Path.of("target/savings-plugin-1.16.0-SNAPSHOT.jar"))));
    var deployed = fineract.execInContainer("sha256sum", "/app/plugins/savings-plugin.jar");
    assertEquals(0, deployed.getExitCode());
    assertTrue(deployed.getStdout().startsWith(pluginHash), deployed.getStdout());
    Path provider =
        Path.of(
            org.apache.fineract.portfolio.savings.service
                .SavingsAccountWritePlatformServiceJpaRepositoryImpl.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    assertTrue(provider.getFileName().toString().contains("0.0.1628-bc1c598"));
    String providerHash =
        HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(provider)));
    var deployedProvider =
        fineract.execInContainer("sha256sum", "/be06libs/" + provider.getFileName());
    assertEquals(0, deployedProvider.getExitCode());
    assertTrue(deployedProvider.getStdout().startsWith(providerHash));
    evidence.add(
        Map.of(
            "runtimeClasspath",
            cp.getStdout().strip(),
            "dependency",
            "0.0.1628-bc1c598",
            "providerSha256",
            providerHash,
            "pluginSha256",
            pluginHash,
            "deployedHashesMatch",
            true));
    call("PUT", "/v1/currencies", Map.of("currencies", List.of("MXN", "USD")), 200);
    var context = call("GET", "/v2/base-teller/cash-allocations/context?officeId=1", null, 200);
    assertEquals(DATE, context.jsonPath().getString("businessDate"));
    evidence.add(
        Map.of(
            "tenantBusinessDate",
            context.jsonPath().getString("businessDate"),
            "storedBusinessDates",
            call("GET", "/v1/businessdate", null, 200).asString()));
    cash = gl("BE06 cash", 1);
    control = gl("BE06 control", 2);
    interest = gl("BE06 interest", 5);
    income = gl("BE06 income", 4);
    suspense = gl("BE06 suspense", 2);
    post(
        "/v1/financialactivityaccounts",
        Map.of("financialActivityId", 200, "glAccountId", suspense));
    checker = "be06-checker-" + UUID.randomUUID().toString().substring(0, 6);
    post(
        "/v1/users",
        Map.ofEntries(
            Map.entry("username", checker),
            Map.entry("firstname", "BE06"),
            Map.entry("lastname", "Checker"),
            Map.entry("email", checker + "@example.invalid"),
            Map.entry("officeId", 1),
            Map.entry("roles", List.of(1)),
            Map.entry("sendPasswordToEmail", false),
            Map.entry("password", "Be06-review-26!"),
            Map.entry("repeatPassword", "Be06-review-26!"),
            Map.entry("passwordNeverExpires", true)));
    payment =
        post("/v1/paymenttypes", Map.of("name", "BE06 cash", "isCashPayment", true, "position", 1));
    client = client();
    long debitProduct = product(0),
        creditProduct = product(0),
        eligibleProduct = product(0),
        legacyProduct = product(6);
    long debit = active(debitProduct),
        credit = active(creditProduct),
        eligible = active(eligibleProduct),
        legacy = active(legacyProduct);
    long creditTransaction = 0;
    for (long a : List.of(debit, credit, eligible, legacy)) {
      long id = transaction(a, "deposit", "100.00", 200).jsonPath().getLong("resourceId");
      if (a == credit) creditTransaction = id;
    }
    assertTrue(
        Long.parseLong(querySingleValueInDefaultTenant("SELECT count(*) FROM acc_gl_journal_entry"))
            > 0);
    policy(debitProduct, false, true, false);
    historicalBinding(debit, debitProduct);
    policy(creditProduct, true, false, false);
    historicalBinding(credit, creditProduct);
    policy(eligibleProduct, true, true, false);
    historicalBinding(eligible, eligibleProduct);
    String spoofBefore = financial();
    call(
        "POST",
        "/v1/savingsaccounts/" + debit + "/transactions?command=withdrawal",
        Map.of(
            "transactionDate",
            DATE,
            "transactionAmount",
            1,
            "paymentTypeId",
            payment,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en",
            "override",
            true,
            "approvedBy",
            1),
        400);
    assertEquals(spoofBefore, financial());
    deniedTransaction(debit, "withdrawal", "withdrawals.disabled");
    deniedTransaction(debit, "force-withdrawal", "withdrawals.disabled");
    deniedTransaction(credit, "deposit", "deposits.disabled");
    deniedTransaction(eligible, "deposit", "maximum.balance.enforcement.unsupported");
    for (var pair : List.of(List.of(debit, legacy), List.of(legacy, credit))) {
      String before = financial();
      var rejection = call("POST", "/v1/accounttransfers", transfer(pair.get(0), pair.get(1)), 400);
      assertTrue(
          rejection
              .asString()
              .contains(pair.get(0) == debit ? "withdrawals.disabled" : "deposits.disabled"),
          rejection.asString());
      assertEquals(before, financial());
    }
    String before = financial();
    try (var workers = Executors.newFixedThreadPool(2)) {
      var a = workers.submit(() -> transaction(eligible, "deposit", "700.00", -1).statusCode());
      var b = workers.submit(() -> transaction(eligible, "deposit", "700.00", -1).statusCode());
      for (int status : List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
        assertTrue(List.of(400, 409).contains(status));
    }
    assertEquals(before, financial());
    long allowedId =
        transaction(eligible, "withdrawal", "1.01", 200).jsonPath().getLong("resourceId");
    assertEquals(
        "1",
        querySingleValueInDefaultTenant(
            "SELECT count(*) FROM m_savings_transaction_policy_audit a JOIN"
                + " m_savings_account_transaction t ON t.id=a.native_transaction_id WHERE"
                + " a.outcome='EVALUATED_ALLOW' AND t.id="
                + allowedId));
    String allowedBalance =
        querySingleValueInDefaultTenant(
            "SELECT account_balance_derived FROM m_savings_account WHERE id=" + eligible);
    assertEquals(
        0, new java.math.BigDecimal("98.99").compareTo(new java.math.BigDecimal(allowedBalance)));
    evidence.add(
        Map.of(
            "allowedNativeWithdrawalId",
            allowedId,
            "balanceAfter",
            allowedBalance,
            "journalCount",
            querySingleValueInDefaultTenant("SELECT count(*) FROM acc_gl_journal_entry")));
    transaction(legacy, "withdrawal", "1.00", 200); // Existing fixed-rate native behavior.
    assertEquals(
        "0",
        querySingleValueInDefaultTenant(
            "SELECT count(*) FROM m_savings_transaction_policy_audit WHERE"
                + " outcome<>'EVALUATED_ALLOW' AND native_transaction_id IS NOT NULL"));
    assertTrue(
        Long.parseLong(
                querySingleValueInDefaultTenant(
                    "SELECT count(*) FROM m_savings_transaction_policy_audit WHERE"
                        + " outcome='POLICY_DENIED'"))
            >= 4);

    long inactiveProduct = product(0);
    policy(inactiveProduct, true, true, false);
    long pending = apply(inactiveProduct, client());
    approve(pending);
    before = financial();
    activate(pending, 400);
    assertEquals(before, financial());
    assertEquals(
        "200",
        querySingleValueInDefaultTenant(
            "SELECT status_enum FROM m_savings_account WHERE id=" + pending));
    assertFalse(
        call("GET", "/v1/savingsproducts/policy/template", null, 200)
            .jsonPath()
            .getBoolean("financialEnforcement"));

    // An actual native bypass is a BLOCKER, not a passing financial-enforcement claim.
    String auditBefore =
        querySingleValueInDefaultTenant("SELECT count(*) FROM m_savings_transaction_policy_audit");
    before = financial();
    call(
        "POST",
        "/v1/savingsaccounts/" + credit + "/transactions/" + creditTransaction + "?command=modify",
        Map.of(
            "transactionDate",
            DATE,
            "transactionAmount",
            101,
            "paymentTypeId",
            payment,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"),
        200);
    assertNotEquals(before, financial());
    assertEquals(
        auditBefore,
        querySingleValueInDefaultTenant("SELECT count(*) FROM m_savings_transaction_policy_audit"));
    evidence.add(
        Map.of(
            "nativeAdjustmentBypass",
            "CONFIRMED_UNSUPPORTED",
            "configuredActivation",
            "BLOCKED",
            "deniedFinancialStateUnchanged",
            true));
  }

  @AfterAll
  static void evidence() throws Exception {
    Files.writeString(
        Path.of("target/product-transaction-be06-http-evidence.json"), JSON.toJson(evidence));
  }
}
