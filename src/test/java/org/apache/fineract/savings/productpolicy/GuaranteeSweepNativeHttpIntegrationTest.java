/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;

import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Exact pinned Fineract runtime: rejected requests preserve every native financial table. */
class GuaranteeSweepNativeHttpIntegrationTest extends ProductTransactionNativeTestSupport {
  static final String ROOT = "/v1/savings/guarantee-sweeps";

  @Test
  void nativeBlockedManualReleaseAndConcurrentDiagnosticsPreserveAccountingAndIdempotency()
      throws Exception {
    var api = new ProductTransactionNativeHttpIntegrationTest();
    api.call("PUT", "/v1/currencies", Map.of("currencies", List.of("MXN", "USD")), 200);
    api.cash = api.gl("BE09 cash", 1);
    api.control = api.gl("BE09 control", 2);
    api.interest = api.gl("BE09 expense", 5);
    api.income = api.gl("BE09 income", 4);
    api.suspense = api.gl("BE09 suspense", 2);
    api.checker = "be09-checker-" + UUID.randomUUID().toString().substring(0, 6);
    user(api, api.checker, 1);
    api.client = api.client();
    long other = api.client();
    long product = api.product(0);
    api.policy(product, true, true, false);
    // A native BE-08 pending Main Account, created through the approved opening path.
    long source = api.apply(product, api.client);
    long loan = loan(api);
    String path = ROOT + "/clients/" + api.client + "/loans/" + loan;
    String before = financial(api);
    assertEquals(
        source,
        api.call("GET", ROOT + "/clients/" + api.client + "/sources", null, 200)
            .jsonPath()
            .getLong("[0].savingsAccountId"));
    assertEquals(
        loan,
        api.call("GET", ROOT + "/clients/" + api.client + "/loans", null, 200)
            .jsonPath()
            .getLong("[0].loanId"));
    var preview =
        api.call("GET", path + "/preview?savingsAccountId=" + source + "&amount=10", null, 200);
    assertEquals("BLOCKED_PENDING_POLICY", preview.jsonPath().getString("state"));
    assertNull(preview.jsonPath().get("availableGuaranteeFunds"));
    assertTrue(
        preview.jsonPath().getList("reasons").contains("SOURCE_LIFECYCLE_OR_BLOCK_UNSUPPORTED"));
    var request =
        Map.of(
            "idempotencyKey",
            "native-be09-manual",
            "savingsAccountId",
            source,
            "amount",
            10,
            "reason",
            "Synthetic Collections diagnostic");
    var first = api.call("POST", path + "/applications", request, 422);
    String id = first.jsonPath().getString("receipt.id");
    assertEquals(
        id, api.call("GET", path + "/executions/" + id, null, 200).jsonPath().getString("id"));
    assertEquals(
        0, api.call("GET", path + "/allocations", null, 200).jsonPath().getList("$").size());
    assertEquals(
        "BLOCKED_PENDING_POLICY",
        api.call("GET", path + "/release-status", null, 200).jsonPath().getString("state"));
    assertEquals(2, api.call("GET", path + "/audit", null, 200).jsonPath().getList("$").size());
    assertNotNull(id);
    assertNull(first.jsonPath().get("receipt.appliedAmount"));
    assertNull(first.jsonPath().get("receipt.nativeLoanTransactionId"));
    assertEquals(
        id,
        api.call("POST", path + "/applications", request, 409).jsonPath().getString("receipt.id"));
    api.call(
        "POST",
        path + "/applications",
        Map.of(
            "idempotencyKey",
            "native-be09-manual",
            "savingsAccountId",
            source,
            "amount",
            11,
            "reason",
            "Synthetic Collections diagnostic"),
        409);
    api.call(
        "POST",
        path + "/applications",
        Map.of(
            "idempotencyKey",
            "native-be09-spoof",
            "savingsAccountId",
            source,
            "amount",
            10,
            "reason",
            "Synthetic",
            "override",
            true),
        400);
    api.call(
        "GET",
        ROOT
            + "/clients/"
            + other
            + "/loans/"
            + loan
            + "/preview?savingsAccountId="
            + source
            + "&amount=10",
        null,
        400);
    var release =
        Map.of(
            "idempotencyKey",
            "native-be09-release",
            "savingsAccountId",
            source,
            "reason",
            "Synthetic settlement diagnostic");
    api.call("POST", path + "/releases", release, 422);
    api.call("POST", path + "/releases", release, 409);
    var recon = api.call("GET", path + "/executions/" + id + "/reconciliation", null, 200);
    assertEquals("NOT_ATTEMPTED", recon.jsonPath().getString("status"));
    assertFalse(recon.jsonPath().getBoolean("nativeAccountingVerified"));
    assertFalse(recon.jsonPath().getBoolean("financialRetryAllowed"));
    String period =
        YearMonth.parse(ProductTransactionNativeHttpIntegrationTest.DATE.substring(0, 7))
            .toString();
    String run =
        api.call("POST", ROOT + "/diagnostic-runs?period=" + period, null, 200)
            .jsonPath()
            .getString("id");
    assertEquals(
        run,
        api.call("POST", ROOT + "/diagnostic-runs?period=" + period, null, 200)
            .jsonPath()
            .getString("id"));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<String> worker =
          () -> {
            start.await();
            return api.call("POST", ROOT + "/diagnostic-runs/" + run + "/assess?limit=1", null, 200)
                .jsonPath()
                .getString("state");
          };
      var a = pool.submit(worker);
      var b = pool.submit(worker);
      start.countDown();
      assertEquals("BLOCKED_PENDING_POLICY", a.get(30, TimeUnit.SECONDS));
      assertEquals("BLOCKED_PENDING_POLICY", b.get(30, TimeUnit.SECONDS));
    }
    assertEquals(
        1,
        api.call("GET", ROOT + "/diagnostic-runs/" + run + "/outcomes", null, 200)
            .jsonPath()
            .getList("$")
            .size());
    assertEquals(
        "0",
        querySingleValueInDefaultTenant("SELECT count(*) FROM m_savings_guarantee_allocation"));
    assertEquals(
        "3", querySingleValueInDefaultTenant("SELECT count(*) FROM m_savings_guarantee_execution"));
    assertEquals(before, financial(api));
    var template = api.call("GET", ROOT + "/template", null, 200);
    for (String c :
        List.of(
            "manualSweep",
            "guaranteeAllocation",
            "guaranteeRelease",
            "automaticSweep",
            "financialEnforcement")) assertFalse(template.jsonPath().getBoolean(c));
    long role =
        api.post(
            "/v1/roles",
            Map.of(
                "name",
                "BE09 denied " + UUID.randomUUID(),
                "description",
                "Synthetic permission denial"));
    String denied = "be09-denied-" + UUID.randomUUID().toString().substring(0, 6);
    user(api, denied, role);
    api.call(denied, "GET", ROOT + "/template", null, 403);
    api.call(denied, "GET", path + "/preview?savingsAccountId=" + source + "&amount=10", null, 403);
    api.call(denied, "POST", path + "/applications", request, 403);
    api.call(denied, "POST", path + "/releases", release, 403);
    api.call(denied, "GET", path + "/executions/" + id + "/reconciliation", null, 403);
    api.call(denied, "POST", ROOT + "/diagnostic-runs?period=" + period, null, 403);
    assertEquals(before, financial(api));
  }

  private String financial(ProductTransactionNativeHttpIntegrationTest api) {
    return api.financial()
        + querySingleValueInDefaultTenant(
            "SELECT md5((SELECT COALESCE(string_agg(row_to_json(l)::text,',' ORDER BY id),'') FROM"
                + " m_loan l)||'|'||(SELECT COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY"
                + " id),'') FROM m_loan_transaction t))");
  }

  private void user(ProductTransactionNativeHttpIntegrationTest api, String name, long role) {
    api.post(
        "/v1/users",
        Map.ofEntries(
            Map.entry("username", name),
            Map.entry("firstname", "Synthetic"),
            Map.entry("lastname", "BE09"),
            Map.entry("email", name + "@example.invalid"),
            Map.entry("officeId", 1),
            Map.entry("roles", List.of(role)),
            Map.entry("sendPasswordToEmail", false),
            Map.entry("password", "Be06-review-26!"),
            Map.entry("repeatPassword", "Be06-review-26!"),
            Map.entry("passwordNeverExpires", true)));
  }

  private long loan(ProductTransactionNativeHttpIntegrationTest api) {
    Map<String, Object> terms = new HashMap<>();
    terms.put("currencyCode", "MXN");
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
    var product = new HashMap<>(terms);
    String suffix = UUID.randomUUID().toString();
    product.put("name", "BE09 Loan " + suffix);
    product.put("shortName", suffix.substring(0, 4));
    product.put("daysInYearType", 365);
    product.put("daysInMonthType", 30);
    product.put("isInterestRecalculationEnabled", false);
    product.put("accountingRule", 1);
    long productId = api.post("/v1/loanproducts", product);
    var application = new HashMap<>(terms);
    application.remove("currencyCode");
    application.remove("digitsAfterDecimal");
    application.remove("inMultiplesOf");
    application.put("clientId", api.client);
    application.put("productId", productId);
    application.put("loanType", "individual");
    application.put("loanTermFrequency", 10);
    application.put("loanTermFrequencyType", 2);
    application.put("submittedOnDate", ProductTransactionNativeHttpIntegrationTest.DATE);
    application.put("expectedDisbursementDate", ProductTransactionNativeHttpIntegrationTest.DATE);
    application.put("dateFormat", "yyyy-MM-dd");
    long loan = api.post("/v1/loans", application);
    api.call(
        "POST",
        "/v1/loans/" + loan + "?command=approve",
        Map.of(
            "approvedLoanAmount",
            1000,
            "approvedOnDate",
            ProductTransactionNativeHttpIntegrationTest.DATE,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"),
        200);
    api.call(
        "POST",
        "/v1/loans/" + loan + "?command=disburse",
        Map.of(
            "actualDisbursementDate",
            ProductTransactionNativeHttpIntegrationTest.DATE,
            "transactionAmount",
            1000,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"),
        200);
    return loan;
  }
}
