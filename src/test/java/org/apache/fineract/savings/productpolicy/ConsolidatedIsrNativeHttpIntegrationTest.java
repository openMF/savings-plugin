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
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Optional genuine native HTTP/accounting regression against an explicitly supplied disposable
 * pinned runtime. All financial fixtures are created through native APIs, never SQL.
 */
@EnabledIfSystemProperty(named = "isr.http.baseUrl", matches = ".+")
class ConsolidatedIsrNativeHttpIntegrationTest {
  static final String ROOT = "/v1/savings/consolidated-isr";
  static final String PASSWORD = "Isr-synthetic-26!";
  final String today = LocalDate.now(java.time.ZoneOffset.UTC).toString();
  final String start = LocalDate.parse(today).minusDays(45).toString();
  final String suffix = UUID.randomUUID().toString().substring(0, 8);
  JdbcTemplate jdbc;

  Response call(String user, String method, String path, Object body, int status) {
    var spec =
        given()
            .relaxedHTTPSValidation()
            .baseUri(System.getProperty("isr.http.baseUrl"))
            .header("Fineract-Platform-TenantId", "default")
            .contentType("application/json");
    if (user != null)
      spec.auth().preemptive().basic(user, user.equals("mifos") ? "password" : PASSWORD);
    if (body != null)
      spec.body(body instanceof String ? body : RatePropagationJson.JSON.toJson(body));
    var response = spec.request(method, path);
    assertEquals(
        status, response.statusCode(), () -> method + " " + path + ": " + response.asString());
    return response;
  }

  Response call(String method, String path, Object body, int status) {
    return call("mifos", method, path, body, status);
  }

  long post(String path, Object body) {
    return call("POST", path, body, 200).jsonPath().getLong("resourceId");
  }

  Map<String, Object> date(String name, String value) {
    return Map.of(name, value, "dateFormat", "yyyy-MM-dd", "locale", "en");
  }

  long gl(String name, int type) {
    return post(
        "/v1/glaccounts",
        Map.of(
            "name",
            name + suffix,
            "glCode",
            UUID.randomUUID().toString().substring(0, 12),
            "type",
            type,
            "usage",
            1,
            "manualEntriesAllowed",
            true));
  }

  @Test
  void diagnosticSnapshotsPreserveNativeWithholdingInterestAndJournalAccounting() throws Exception {
    String url = System.getProperty("isr.http.jdbcUrl", "");
    if (!url.matches(
        "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/web1350_be11_[a-zA-Z0-9_]+"))
      throw new IllegalArgumentException("Explicit disposable BE-11 database required.");
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                url,
                System.getProperty("policy.it.dbUser", System.getProperty("user.name")),
                System.getProperty("policy.it.dbPassword", "")));
    call(null, "GET", ROOT + "/template", null, 401);
    call("PUT", "/v1/currencies", Map.of("currencies", List.of("MXN")), 200);
    long payment =
        post(
            "/v1/paymenttypes",
            Map.of("name", "ISR cash " + suffix, "isCashPayment", true, "position", 1));
    long cash = gl("ISR cash ", 1),
        control = gl("ISR control ", 2),
        expense = gl("ISR interest ", 5),
        income = gl("ISR income ", 4),
        suspense = gl("ISR suspense ", 2),
        liability = gl("ISR native tax ", 2);
    // Synthetic native regression percentage. It is never a consolidated ISR policy fixture.
    long component =
        post(
            "/v1/taxes/component",
            Map.of(
                "name",
                "ISR synthetic " + suffix,
                "percentage",
                new BigDecimal("10"),
                "startDate",
                start,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en",
                "creditAccountType",
                2,
                "creditAccountId",
                liability));
    long group =
        post(
            "/v1/taxes/group",
            Map.of(
                "name",
                "ISR synthetic " + suffix,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en",
                "taxComponents",
                List.of(Map.of("taxComponentId", component, "startDate", start))));
    var product = new LinkedHashMap<String, Object>();
    product.putAll(
        Map.of(
            "name",
            "ISR synthetic " + suffix,
            "shortName",
            suffix.substring(0, 4),
            "description",
            "Native regression only",
            "currencyCode",
            "MXN",
            "digitsAfterDecimal",
            2,
            "nominalAnnualInterestRate",
            12,
            "interestCompoundingPeriodType",
            1,
            "interestPostingPeriodType",
            4,
            "interestCalculationType",
            1,
            "interestCalculationDaysInYearType",
            365));
    product.putAll(
        Map.of(
            "accountingRule",
            2,
            "savingsReferenceAccountId",
            cash,
            "savingsControlAccountId",
            control,
            "interestOnSavingsAccountId",
            expense,
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
            expense));
    product.putAll(Map.of("withHoldTax", true, "taxGroupId", group, "locale", "en"));
    long productId = post("/v1/savingsproducts", product);
    long client =
        post(
            "/v1/clients",
            Map.of(
                "officeId",
                1,
                "legalFormId",
                1,
                "firstname",
                "ISR",
                "lastname",
                suffix,
                "active",
                true,
                "activationDate",
                start,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    long account =
        post(
            "/v1/savingsaccounts",
            Map.of(
                "clientId",
                client,
                "productId",
                productId,
                "submittedOnDate",
                start,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    call(
        "POST",
        "/v1/savingsaccounts/" + account + "?command=approve",
        date("approvedOnDate", start),
        200);
    call(
        "POST",
        "/v1/savingsaccounts/" + account + "?command=activate",
        date("activatedOnDate", start),
        200);
    long deposit =
        post(
            "/v1/savingsaccounts/" + account + "/transactions?command=deposit",
            Map.of(
                "transactionDate",
                start,
                "transactionAmount",
                new BigDecimal("1000"),
                "paymentTypeId",
                payment,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"));
    call("POST", "/v1/savingsaccounts/" + account + "?command=postInterest", Map.of(), 200);
    assertTrue(
        jdbc.queryForObject(
                "SELECT COUNT(*) FROM m_savings_account_transaction WHERE savings_account_id=? AND transaction_type_enum=18 AND is_reversed=false",
                Integer.class,
                account)
            > 0);
    assertTrue(
        jdbc.queryForObject(
                "SELECT COUNT(*) FROM acc_gl_journal_entry WHERE savings_transaction_id IN (SELECT id FROM m_savings_account_transaction WHERE savings_account_id=?)",
                Integer.class,
                account)
            > 0);
    assertEquals(
        0,
        jdbc.queryForObject(
                "SELECT SUM(CASE WHEN type_enum=1 THEN amount ELSE -amount END) FROM acc_gl_journal_entry WHERE savings_transaction_id IN (SELECT id FROM m_savings_account_transaction WHERE savings_account_id=?)",
                BigDecimal.class,
                account)
            .signum());
    String path = ROOT + "/clients/" + client;
    String query = "?from=" + start + "&through=" + today;
    String before = financial();
    var template = call("GET", ROOT + "/template", null, 200);
    assertFalse(template.jsonPath().getBoolean("withholdingExecution"));
    assertFalse(template.jsonPath().getBoolean("accountingIntegrationVerified"));
    String ruleId = UUID.randomUUID().toString();
    var proposal =
        Map.of(
            "idempotencyKey",
            UUID.randomUUID().toString(),
            "expectedVersion",
            0,
            "reason",
            "Synthetic unapproved metadata",
            "definition",
            Map.of(
                "jurisdiction",
                "UNCONFIRMED",
                "taxTreatment",
                "UNCONFIRMED",
                "currency",
                "MXN",
                "effectiveFrom",
                today,
                "effectiveThrough",
                today));
    var draft = call("POST", ROOT + "/rules/" + ruleId + "/versions", proposal, 200);
    assertEquals("DRAFT", draft.jsonPath().getString("status"));
    assertNull(draft.jsonPath().get("approverId"));
    assertEquals(
        draft.asString(),
        call("POST", ROOT + "/rules/" + ruleId + "/versions", proposal, 200).asString());
    call("GET", ROOT + "/rules/" + ruleId + "/versions", null, 200);
    call("GET", path + "/eligibility" + query, null, 200);
    call("GET", path + "/accounts" + query, null, 200);
    var preview = call("GET", path + "/preview" + query, null, 200);
    assertEquals("BLOCKED_PENDING_POLICY", preview.jsonPath().getString("state"));
    assertNull(preview.jsonPath().get("calculatedIsr"));
    assertNull(preview.jsonPath().get("actualWithheldAmount"));
    BigDecimal nativeTax =
        jdbc.queryForObject(
            "SELECT SUM(amount) FROM m_savings_account_transaction WHERE savings_account_id=? AND transaction_type_enum=18 AND is_reversed=false AND is_reversal=false",
            BigDecimal.class,
            account);
    assertEquals(
        0,
        nativeTax.compareTo(
            new BigDecimal(
                preview.jsonPath().getString("sources.observations[0].nativeWithheldTax"))));
    String key = UUID.randomUUID().toString();
    var body = Map.of("idempotencyKey", key, "period", Map.of("from", start, "through", today));
    var saved = call("POST", path + "/calculations", body, 200);
    assertEquals(saved.asString(), call("POST", path + "/calculations", body, 200).asString());
    String assessment = path + "/calculations/" + key;
    call("GET", assessment, null, 200);
    call("GET", assessment + "/audit", null, 200);
    assertEquals(
        "UNCHANGED_BLOCKED",
        call("GET", assessment + "/reconciliation", null, 200).jsonPath().getString("status"));
    assertEquals(before, financial());
    call(
        "POST",
        path + "/calculations",
        Map.of(
            "idempotencyKey",
            key,
            "period",
            Map.of("from", start, "through", today),
            "reason",
            "changed"),
        409);
    call(
        "POST",
        path + "/calculations",
        Map.of("idempotencyKey", UUID.randomUUID().toString(), "taxableBase", 1000),
        400);
    // Native backdated correction reverses/replaces interest and withholding through native code.
    call(
        "POST",
        "/v1/savingsaccounts/" + account + "/transactions/" + deposit + "?command=modify",
        Map.of(
            "transactionDate",
            start,
            "transactionAmount",
            new BigDecimal("900"),
            "paymentTypeId",
            payment,
            "dateFormat",
            "yyyy-MM-dd",
            "locale",
            "en"),
        200);
    call("POST", "/v1/savingsaccounts/" + account + "?command=postInterest", Map.of(), 200);
    assertTrue(
        jdbc.queryForObject(
                "SELECT COUNT(*) FROM m_savings_account_transaction WHERE savings_account_id=? AND transaction_type_enum=18 AND is_reversed=true",
                Integer.class,
                account)
            > 0);
    before = financial();
    assertEquals(
        "RECONCILIATION_REQUIRED",
        call("GET", assessment + "/reconciliation", null, 200).jsonPath().getString("status"));
    assertEquals(saved.asString(), call("GET", assessment, null, 200).asString());
    assertEquals(before, financial());
    assertEquals(
        0,
        jdbc.queryForObject(
                "SELECT SUM(CASE WHEN type_enum=1 THEN amount ELSE -amount END) FROM acc_gl_journal_entry WHERE savings_transaction_id IN (SELECT id FROM m_savings_account_transaction WHERE savings_account_id=?)",
                BigDecimal.class,
                account)
            .signum());
    long role =
        post(
            "/v1/roles",
            Map.of("name", "ISR reader " + suffix, "description", "Synthetic read-only role"));
    call(
        "PUT",
        "/v1/roles/" + role + "/permissions",
        Map.of("permissions", Map.of(ConsolidatedIsrAccess.READ, true)),
        200);
    String reader = "isr-reader-" + suffix;
    post(
        "/v1/users",
        Map.of(
            "username",
            reader,
            "firstname",
            "ISR",
            "lastname",
            "Reader",
            "email",
            reader + "@example.invalid",
            "officeId",
            1,
            "roles",
            List.of(role),
            "sendPasswordToEmail",
            false,
            "password",
            PASSWORD,
            "repeatPassword",
            PASSWORD,
            "passwordNeverExpires",
            true));
    call(reader, "GET", assessment, null, 200);
    call(reader, "GET", path + "/preview" + query, null, 403);
    call(reader, "GET", assessment + "/audit", null, 403);
    call(reader, "POST", path + "/calculations", body, 403);
    call(reader, "POST", ROOT + "/rules/" + UUID.randomUUID() + "/versions", proposal, 403);
    call("POST", path + "/withhold", Map.of(), 404);
    var evidence =
        Map.of(
            "calculationId",
            key,
            "nativeAccountId",
            account,
            "nativeWithheldTaxObserved",
            nativeTax,
            "nativeJournalBalanced",
            true,
            "savedAssessment",
            JsonParser.parseString(saved.asString()),
            "previewFinancialStateUnchanged",
            true,
            "nativeCorrectionDetected",
            true,
            "withholdingExecution",
            false);
    Files.writeString(
        Path.of("target/be11-native-evidence.json"), RatePropagationJson.JSON.toJson(evidence));
  }

  String financial() {
    return List.of(
            "m_savings_account",
            "m_savings_account_transaction",
            "m_savings_account_transaction_tax_details",
            "acc_gl_journal_entry",
            "m_savings_product")
        .stream()
        .map(t -> jdbc.queryForList("SELECT row_to_json(t)::text FROM " + t + " t ORDER BY id"))
        .toList()
        .toString();
  }
}
