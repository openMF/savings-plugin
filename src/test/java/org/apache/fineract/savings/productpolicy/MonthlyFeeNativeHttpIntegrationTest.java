/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.*;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.Test;

/**
 * Exact Maven Fineract runtime and PostgreSQL. Verifies blocked processing has no financial effect.
 */
class MonthlyFeeNativeHttpIntegrationTest extends ProductTransactionNativeTestSupport {
  static final String ROOT = "/v1/savings/monthly-fees";

  @Test
  void authenticatedDiagnosticsLeaveNativeChargesTransactionsBalancesAndJournalsUnchanged()
      throws Exception {
    // Reuse only the native fixture builders, not the BE-06 test scenario.
    var nativeApi = new ProductTransactionNativeHttpIntegrationTest();
    nativeApi.call("PUT", "/v1/currencies", Map.of("currencies", List.of("MXN", "USD")), 200);
    nativeApi.cash = nativeApi.gl("BE07 cash", 1);
    nativeApi.control = nativeApi.gl("BE07 control", 2);
    nativeApi.interest = nativeApi.gl("BE07 expense", 5);
    nativeApi.income = nativeApi.gl("BE07 fee income", 4);
    nativeApi.suspense = nativeApi.gl("BE07 suspense", 2);
    nativeApi.payment =
        nativeApi.post(
            "/v1/paymenttypes",
            Map.of("name", "BE07 synthetic cash", "isCashPayment", true, "position", 1));
    nativeApi.client = nativeApi.client();
    long product = nativeApi.product(0), account = nativeApi.active(product);
    nativeApi.transaction(account, "deposit", "100.00", 200);
    long charge =
        nativeApi.post(
            "/v1/charges",
            Map.ofEntries(
                Map.entry("name", "BE07 dated " + UUID.randomUUID()),
                Map.entry("currencyCode", "MXN"),
                Map.entry("amount", 10),
                Map.entry("chargeAppliesTo", 2),
                Map.entry("chargeTimeType", 2),
                Map.entry("chargeCalculationType", 1),
                Map.entry("penalty", false),
                Map.entry("active", true),
                Map.entry("locale", "en")));
    // Existing native specified-date obligation must survive all diagnostic runs unchanged.
    long nativeCharge =
        nativeApi.post(
            "/v1/savingsaccounts/" + account + "/charges",
            Map.of(
                "chargeId",
                charge,
                "amount",
                10,
                "dueDate",
                java.time.LocalDate.parse(ProductTransactionNativeHttpIntegrationTest.DATE)
                    .plusDays(10)
                    .toString(),
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    var nextDue =
        java.time.LocalDate.parse(ProductTransactionNativeHttpIntegrationTest.DATE).plusDays(20);
    long monthlyDefinition =
        nativeApi.post(
            "/v1/charges",
            Map.ofEntries(
                Map.entry("name", "BE07 existing monthly " + UUID.randomUUID()),
                Map.entry("currencyCode", "MXN"),
                Map.entry("amount", 12),
                Map.entry("chargeAppliesTo", 2),
                Map.entry("chargeTimeType", 7),
                Map.entry("chargeCalculationType", 1),
                Map.entry("feeInterval", 1),
                Map.entry(
                    "feeOnMonthDay",
                    nextDue.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))),
                Map.entry("monthDayFormat", "MM-dd"),
                Map.entry("penalty", false),
                Map.entry("active", true),
                Map.entry("locale", "en")));
    long nativeMonthlyCharge =
        nativeApi.post(
            "/v1/savingsaccounts/" + account + "/charges",
            Map.of(
                "chargeId",
                monthlyDefinition,
                "amount",
                12,
                "dueDate",
                nextDue.toString(),
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    var draft = JsonParser.parseString(ProductPolicyValidationTest.JSON).getAsJsonObject();
    draft.getAsJsonObject("policy").addProperty("feeApplicable", true);
    draft.getAsJsonObject("policy").addProperty("feeChargeId", charge);
    nativeApi.call("PUT", "/v1/savingsproducts/policy/" + product, draft.toString(), 200);
    String business =
        nativeApi
            .call("GET", "/v2/base-teller/cash-allocations/context?officeId=1", null, 200)
            .jsonPath()
            .getString("businessDate");
    String period = YearMonth.from(java.time.LocalDate.parse(business)).minusMonths(1).toString();
    var capabilities = nativeApi.call("GET", ROOT + "/template", null, 200);
    assertFalse(capabilities.jsonPath().getBoolean("postingSupported"));
    assertFalse(capabilities.jsonPath().getBoolean("collectionSupported"));
    assertFalse(capabilities.jsonPath().getBoolean("approvedAverageCalculationSupported"));
    assertTrue(capabilities.jsonPath().getBoolean("diagnosticBatchAdapterSupported"));
    String before = financial();
    String balanceBefore =
        querySingleValueInDefaultTenant(
            "SELECT account_balance_derived FROM m_savings_account WHERE id=" + account);
    assertEquals(
        0, new java.math.BigDecimal("100").compareTo(new java.math.BigDecimal(balanceBefore)));
    String run =
        nativeApi
            .call("POST", ROOT + "/runs?period=" + period, null, 200)
            .jsonPath()
            .getString("id");
    assertEquals(
        run,
        nativeApi
            .call("POST", ROOT + "/runs?period=" + period, null, 200)
            .jsonPath()
            .getString("id"));
    var assessed = nativeApi.call("POST", ROOT + "/runs/" + run + "/assess?limit=1", null, 200);
    assertEquals("DIAGNOSTIC_COMPLETE", assessed.jsonPath().getString("status"));
    assertEquals(1, assessed.jsonPath().getInt("blocked"));
    var rows = nativeApi.call("GET", ROOT + "/runs/" + run + "/assessments", null, 200);
    assertEquals("BLOCKED", rows.jsonPath().getString("items[0].outcome"));
    assertEquals("NOT_ATTEMPTED", rows.jsonPath().getString("items[0].financialOutcome"));
    assertEquals(
        run,
        nativeApi
            .call("GET", ROOT + "/runs?limit=1", null, 200)
            .jsonPath()
            .getString("items[0].id"));
    nativeApi.call("POST", ROOT + "/runs/" + run + "/assess", null, 200);
    var reconciliation =
        nativeApi.call("GET", ROOT + "/runs/" + run + "/reconciliation", null, 200);
    assertFalse(reconciliation.jsonPath().getBoolean("nativeAccountingVerified"));
    assertFalse(reconciliation.jsonPath().getBoolean("financialRetryAllowed"));
    nativeApi.call(
        "POST",
        ROOT + "/runs?period=" + YearMonth.from(java.time.LocalDate.parse(business)),
        null,
        400);
    nativeApi.call("POST", ROOT + "/runs/" + run + "/assess?limit=101", null, 400);
    given(SavingsTestUtils.requestSpec(getFineractPort()))
        .when()
        .get(SavingsTestUtils.CONTEXT_PATH + "/api" + ROOT + "/template")
        .then()
        .statusCode(401);
    long role =
        nativeApi.post(
            "/v1/roles",
            Map.of(
                "name",
                "BE07 no financial permissions " + UUID.randomUUID(),
                "description",
                "Synthetic restricted operator"));
    String restricted = "be07-" + UUID.randomUUID().toString().substring(0, 8);
    nativeApi.post(
        "/v1/users",
        Map.ofEntries(
            Map.entry("username", restricted),
            Map.entry("firstname", "BE07"),
            Map.entry("lastname", "Restricted"),
            Map.entry("email", restricted + "@example.invalid"),
            Map.entry("officeId", 1),
            Map.entry("roles", List.of(role)),
            Map.entry("sendPasswordToEmail", false),
            Map.entry("password", "Be06-review-26!"),
            Map.entry("repeatPassword", "Be06-review-26!"),
            Map.entry("passwordNeverExpires", true)));
    nativeApi.call(restricted, "POST", ROOT + "/runs/" + run + "/assess", null, 403);
    nativeApi.call(restricted, "GET", ROOT + "/runs/" + run, null, 403);
    String after = financial();
    assertEquals(before, after);
    String balanceAfter =
        querySingleValueInDefaultTenant(
            "SELECT account_balance_derived FROM m_savings_account WHERE id=" + account);
    assertEquals(balanceBefore, balanceAfter);
    assertEquals(
        "7",
        querySingleValueInDefaultTenant(
            "SELECT charge_time_enum FROM m_savings_account_charge WHERE id="
                + nativeMonthlyCharge));
    assertEquals(
        "f",
        querySingleValueInDefaultTenant(
            "SELECT is_paid_derived FROM m_savings_account_charge WHERE id=" + nativeCharge));
    Files.writeString(
        Path.of("target/monthly-fee-be07-native-evidence.json"),
        new com.google.gson.Gson()
            .toJson(
                Map.ofEntries(
                    Map.entry("mode", "DIAGNOSTIC_ASSESSMENT_ONLY"),
                    Map.entry("dependency", "0.0.1628-bc1c598"),
                    Map.entry("accountId", account),
                    Map.entry("period", period),
                    Map.entry("nativeExistingChargeId", nativeCharge),
                    Map.entry("nativeExistingMonthlyChargeId", nativeMonthlyCharge),
                    Map.entry("preOperationBalance", balanceBefore),
                    Map.entry("postOperationBalance", balanceAfter),
                    Map.entry("runId", run),
                    Map.entry("financialBefore", before),
                    Map.entry("financialAfter", after),
                    Map.entry("nativeFinancialStateUnchanged", true),
                    Map.entry("conditionalChargeCreated", false),
                    Map.entry("accountingReconciled", false))));
  }

  String financial() {
    return querySingleValueInDefaultTenant(
        "SELECT md5((SELECT COALESCE(string_agg(row_to_json(a)::text,',' ORDER BY id),'') FROM"
            + " m_savings_account a)||'|'||(SELECT COALESCE(string_agg(row_to_json(t)::text,','"
            + " ORDER BY id),'') FROM m_savings_account_transaction t)||'|'||(SELECT"
            + " COALESCE(string_agg(row_to_json(c)::text,',' ORDER BY id),'') FROM"
            + " m_savings_account_charge c)||'|'||(SELECT"
            + " COALESCE(string_agg(row_to_json(j)::text,',' ORDER BY id),'') FROM"
            + " acc_gl_journal_entry j))");
  }
}
