/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Real bc1c598 runtime + native API fixtures + PostgreSQL. Never substitutes fake native tables.
 */
@EnabledIfSystemProperty(
    named = "anpa.it.baseUrl",
    matches = "http://(localhost|127\\.0\\.0\\.1):[0-9]+/fineract-provider")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnpaNativeIntegrationTest {
  static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  static String base, auth;
  static String fixtureTenant = "default";
  static JdbcTemplate secondary;
  static long secondaryLoan;
  static JdbcTemplate jdbc;
  static long loan, repayment;
  static String day = "2026-11-14";

  static JsonObject request(String method, String path, Object body, int expected)
      throws Exception {
    return requestAs(method, path, body, expected, auth, fixtureTenant);
  }

  static JsonObject requestAs(
      String method, String path, Object body, int expected, String credentials, String tenant)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(60))
            .header("Fineract-Platform-TenantId", tenant)
            .header("Content-Type", "application/json");
    if (credentials != null) builder.header("Authorization", credentials);
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(AnpaSupport.JSON.toJson(body)));
    JdbcTemplate source =
        tenant.equals("default") ? jdbc : tenant.equals("anpa-secondary") ? secondary : null;
    boolean diagnostic = source != null && path.startsWith("/api/v1/loans/anpa-writeoff/");
    var before = diagnostic ? financialFingerprint(source) : null;
    var result = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    if (diagnostic)
      assertEquals(
          before,
          financialFingerprint(source),
          method + " " + path + " changed native financial data");
    assertEquals(expected, result.statusCode(), path + " " + result.body());
    if (result.body().isBlank() || !result.body().startsWith("{")) return new JsonObject();
    return JsonParser.parseString(result.body()).getAsJsonObject();
  }

  static long create(String path, Object body, String id) throws Exception {
    return request("POST", "/api/v1" + path, body, 200).get(id).getAsLong();
  }

  static Map<String, Object> dated(String field, String value) {
    var r = new HashMap<String, Object>();
    r.put(field, value);
    r.put("dateFormat", "yyyy-MM-dd");
    r.put("locale", "en");
    return r;
  }

  @BeforeAll
  static void setup() throws Exception {
    base = System.getProperty("anpa.it.baseUrl");
    String url = System.getProperty("anpa.it.jdbcUrl", "");
    if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/web1205_[A-Za-z0-9_]+"))
      throw new IllegalArgumentException(
          "Disposable web1205_* native PostgreSQL database required.");
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                url,
                System.getProperty("anpa.it.dbUser", System.getProperty("user.name")),
                System.getProperty(
                    "anpa.it.dbPassword", System.getenv().getOrDefault("PGPASSWORD", ""))));
    auth =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (System.getProperty("anpa.it.user", "mifos")
                            + ":"
                            + System.getProperty("anpa.it.password", "password"))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var info = request("GET", "/actuator/info", null, 200);
    assertEquals(
        "bc1c5980996a878719fddce7c6d70be54fb3b3a0",
        info.getAsJsonObject("git")
            .getAsJsonObject("commit")
            .getAsJsonObject("id")
            .get("full")
            .getAsString());
    seedLoan(40);
    String secondaryUrl = System.getProperty("anpa.it.secondaryJdbcUrl");
    if (secondaryUrl != null) {
      if (!secondaryUrl.matches(
          "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/web1205_[A-Za-z0-9_]+"))
        throw new IllegalArgumentException("Disposable secondary database required.");
      secondary =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  secondaryUrl,
                  System.getProperty("anpa.it.dbUser", System.getProperty("user.name")),
                  System.getProperty(
                      "anpa.it.dbPassword", System.getenv().getOrDefault("PGPASSWORD", ""))));
      long primaryLoan = loan, primaryRepayment = repayment;
      fixtureTenant = "anpa-secondary";
      try {
        seedLoan(25);
        secondaryLoan = loan;
      } finally {
        fixtureTenant = "default";
        loan = primaryLoan;
        repayment = primaryRepayment;
      }
    }
  }

  static void seedLoan(int paymentAmount) throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    var accounts = new HashMap<String, Long>();
    for (String key :
        List.of(
            "fund",
            "principal",
            "interest",
            "fees",
            "penalty",
            "suspense",
            "income",
            "expense",
            "liability")) {
      int type =
          switch (key) {
            case "income" -> 4;
            case "expense" -> 5;
            case "liability" -> 2;
            default -> 1;
          };
      accounts.put(
          key,
          create(
              "/glaccounts",
              Map.of(
                  "name",
                  "ANPA " + key + suffix,
                  "glCode",
                  suffix + key,
                  "type",
                  type,
                  "usage",
                  1,
                  "manualEntriesAllowed",
                  true),
              "resourceId"));
    }
    var product = new HashMap<String, Object>();
    product.putAll(
        Map.of(
            "name",
            "ANPA integration " + suffix,
            "shortName",
            suffix.substring(0, 4),
            "currencyCode",
            "USD",
            "digitsAfterDecimal",
            2,
            "locale",
            "en",
            "principal",
            1000,
            "numberOfRepayments",
            12,
            "repaymentEvery",
            1,
            "repaymentFrequencyType",
            2));
    product.putAll(
        Map.of(
            "interestRatePerPeriod",
            1,
            "interestRateFrequencyType",
            2,
            "amortizationType",
            1,
            "interestType",
            1,
            "interestCalculationPeriodType",
            1,
            "transactionProcessingStrategyCode",
            "mifos-standard-strategy",
            "accountingRule",
            4,
            "daysInMonthType",
            1,
            "daysInYearType",
            1,
            "isInterestRecalculationEnabled",
            false));
    product.put("overdueDaysForNPA", 30);
    var assetKeys =
        Map.of(
            "fundSourceAccountId",
            "fund",
            "loanPortfolioAccountId",
            "principal",
            "transfersInSuspenseAccountId",
            "suspense",
            "receivableInterestAccountId",
            "interest",
            "receivableFeeAccountId",
            "fees",
            "receivablePenaltyAccountId",
            "penalty",
            "overpaymentLiabilityAccountId",
            "liability");
    assetKeys.forEach((key, value) -> product.put(key, accounts.get(value)));
    for (String key :
        List.of(
            "interestOnLoanAccountId",
            "incomeFromFeeAccountId",
            "incomeFromPenaltyAccountId",
            "incomeFromRecoveryAccountId",
            "incomeFromChargeOffInterestAccountId",
            "incomeFromChargeOffFeesAccountId",
            "incomeFromChargeOffPenaltyAccountId",
            "incomeFromGoodwillCreditInterestAccountId",
            "incomeFromGoodwillCreditFeesAccountId",
            "incomeFromGoodwillCreditPenaltyAccountId")) product.put(key, accounts.get("income"));
    for (String key :
        List.of(
            "writeOffAccountId",
            "goodwillCreditAccountId",
            "chargeOffExpenseAccountId",
            "chargeOffFraudExpenseAccountId")) product.put(key, accounts.get("expense"));
    long productId = create("/loanproducts", product, "resourceId");
    var client = dated("activationDate", "2026-01-01");
    client.putAll(
        Map.of(
            "officeId",
            1,
            "legalFormId",
            1,
            "firstname",
            "Synthetic",
            "lastname",
            "ANPA " + suffix,
            "active",
            true));
    long clientId = create("/clients", client, "resourceId");
    var application = dated("submittedOnDate", "2026-01-01");
    application.putAll(
        Map.of(
            "clientId",
            clientId,
            "productId",
            productId,
            "loanType",
            "individual",
            "principal",
            1000,
            "loanTermFrequency",
            12,
            "loanTermFrequencyType",
            2,
            "numberOfRepayments",
            12,
            "repaymentEvery",
            1,
            "repaymentFrequencyType",
            2));
    application.putAll(
        Map.of(
            "interestRatePerPeriod",
            1,
            "amortizationType",
            1,
            "interestType",
            1,
            "interestCalculationPeriodType",
            1,
            "transactionProcessingStrategyCode",
            "mifos-standard-strategy",
            "expectedDisbursementDate",
            "2026-01-01"));
    loan = create("/loans", application, "loanId");
    create("/loans/" + loan + "?command=approve", dated("approvedOnDate", "2026-01-01"), "loanId");
    create(
        "/loans/" + loan + "?command=disburse",
        dated("actualDisbursementDate", "2026-01-01"),
        "loanId");
    var payment = dated("transactionDate", "2026-03-01");
    payment.put("transactionAmount", paymentAmount);
    repayment = create("/loans/" + loan + "/transactions?command=repayment", payment, "resourceId");
  }

  static String endpoint(String suffix) {
    return "/api/v1/loans/anpa-writeoff/loans/" + loan + suffix;
  }

  static Map<String, String> financialFingerprint() {
    return financialFingerprint(jdbc);
  }

  static Map<String, String> financialFingerprint(JdbcTemplate source) {
    var result = new TreeMap<String, String>();
    for (String table :
        List.of(
            "m_loan",
            "m_loan_transaction",
            "m_loan_charge",
            "m_loan_charge_paid_by",
            "m_loan_charge_tax_details",
            "m_loan_transaction_relation",
            "m_loan_repayment_schedule",
            "acc_gl_journal_entry",
            "acc_product_mapping"))
      result.put(
          table,
          source.queryForObject(
              "SELECT md5(COALESCE(string_agg(row_json::text,'' ORDER BY row_json::text),'')) FROM (SELECT to_jsonb(t) row_json FROM "
                  + table
                  + " t) s",
              String.class));
    return result;
  }

  @Test
  @Order(1)
  void nativeSourceIdsAndEveryGetAreFinanciallyReadOnly() throws Exception {
    for (String path :
        List.of(
            "/eligibility",
            "/history",
            "/receivables",
            "/preview",
            "/preview?asOf=2026-02-01",
            "/assessments")) {
      var before = financialFingerprint();
      request("GET", endpoint(path), null, 200);
      assertEquals(before, financialFingerprint(), path);
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM m_anpa_assessment WHERE loan_id=?", Integer.class, loan));
    }
    var p =
        AnpaSupport.JSON.fromJson(request("GET", endpoint("/preview"), null, 200), Preview.class);
    assertFalse(p.financiallyExecutable());
    assertNull(p.proposedReceivableExpense());
    assertTrue(p.blockers().contains(Reason.NPA_RECEIVABLE_MAPPING_MISSING));
    assertTrue(p.blockers().contains(Reason.NPA_HISTORY_UNAVAILABLE));
    assertTrue(p.evidence().transactions().stream().anyMatch(t -> t.id() == repayment));
    assertTrue(
        p.evidence().transactions().stream()
            .allMatch(t -> t.createdAt() != null && t.modifiedAt() != null));
    var historical =
        AnpaSupport.JSON.fromJson(
            request("GET", endpoint("/preview?asOf=2026-02-01"), null, 200), Preview.class);
    assertTrue(historical.evidence().transactions().stream().noneMatch(t -> t.id() == repayment));
    assertTrue(
        historical.evidence().journals().stream()
            .allMatch(j -> !j.date().isAfter(historical.asOf())));
    assertTrue(historical.blockers().contains(Reason.HISTORICAL_SNAPSHOT_UNAVAILABLE));
    assertTrue(
        p.evidence().journals().stream()
            .anyMatch(j -> Objects.equals(j.loanTransactionId(), repayment)));
    assertTrue(
        p.receivables().stream()
            .anyMatch(b -> b.category() == Category.INTEREST && b.netGlReceivable() != null));
  }

  @Test
  @Order(2)
  void nativeReversalIsRetainedAndNotCountedTwice() throws Exception {
    var reversal = dated("transactionDate", "2026-03-01");
    reversal.put("transactionAmount", 0);
    create("/loans/" + loan + "/transactions/" + repayment, reversal, "loanId");
    var before = financialFingerprint();
    var p =
        AnpaSupport.JSON.fromJson(request("GET", endpoint("/preview"), null, 200), Preview.class);
    assertTrue(
        p.evidence().transactions().stream().anyMatch(t -> t.id() == repayment && t.reversed()));
    var interest =
        p.receivables().stream()
            .filter(
                b ->
                    b.category() == Category.INTEREST
                        && b.currentClassification() == Classification.NATIVE_UNSEGREGATED)
            .findFirst()
            .orElseThrow();
    assertEquals(0, p.loan().currentOutstanding().interest().compareTo(interest.netGlReceivable()));
    assertEquals(before, financialFingerprint());
  }

  @Test
  @Order(3)
  void nativeChargesAndTaxComponentsRemainEvidenceWithoutInventedItbmsTreatment() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    long liability =
        create(
            "/glaccounts",
            Map.of(
                "name",
                "ANPA tax " + suffix,
                "glCode",
                "tax" + suffix,
                "type",
                2,
                "usage",
                1,
                "manualEntriesAllowed",
                true),
            "resourceId");
    var component = dated("startDate", "2026-01-01");
    component.putAll(
        Map.of(
            "name",
            "ANPA synthetic tax " + suffix,
            "percentage",
            7,
            "creditAccountType",
            2,
            "creditAccountId",
            liability));
    long tax = create("/taxes/component", component, "resourceId");
    long group =
        create(
            "/taxes/group",
            Map.of(
                "name",
                "ANPA tax group " + suffix,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en",
                "taxComponents",
                List.of(Map.of("taxComponentId", tax, "startDate", "2026-01-01"))),
            "resourceId");
    for (boolean taxed : List.of(false, true)) {
      var definition = new HashMap<String, Object>();
      definition.putAll(
          Map.of(
              "name",
              "ANPA fee " + suffix + taxed,
              "currencyCode",
              "USD",
              "amount",
              20,
              "chargeAppliesTo",
              1,
              "chargeTimeType",
              2,
              "chargeCalculationType",
              1,
              "chargePaymentMode",
              0,
              "active",
              true,
              "penalty",
              false,
              "locale",
              "en"));
      if (taxed) definition.put("taxGroupId", group);
      long charge = create("/charges", definition, "resourceId");
      var assignment = dated("dueDate", "2026-04-01");
      assignment.putAll(Map.of("chargeId", charge, "amount", 20));
      create("/loans/" + loan + "/charges", assignment, "resourceId");
    }
    var before = financialFingerprint();
    var p =
        AnpaSupport.JSON.fromJson(request("GET", endpoint("/preview"), null, 200), Preview.class);
    assertEquals(2, p.evidence().charges().size());
    assertFalse(p.evidence().taxes().isEmpty());
    assertTrue(
        p.evidence().taxes().stream()
            .allMatch(t -> t.taxComponentId() == tax && t.creditAccountId() == liability));
    assertTrue(p.blockers().contains(Reason.TAX_TREATMENT_UNAPPROVED));
    assertTrue(
        p.receivables().stream()
            .filter(b -> b.category() == Category.ITBMS)
            .allMatch(b -> b.expenseEligible() == null));
    assertEquals(before, financialFingerprint());
  }

  @Test
  @Order(3)
  void assessmentReplaySupersessionAuditAndConflictsAffectOnlyEvidence() throws Exception {
    var before = financialFingerprint();
    String key = UUID.randomUUID().toString();
    var saved = request("POST", endpoint("/assessments"), Map.of("idempotencyKey", key), 200);
    assertEquals(
        saved, request("POST", endpoint("/assessments"), Map.of("idempotencyKey", key), 200));
    String id = saved.get("id").getAsString();
    request(
        "POST", endpoint("/assessments"), Map.of("idempotencyKey", key, "asOf", "2026-01-01"), 409);
    assertEquals(saved, request("GET", endpoint("/assessments/" + id), null, 200));
    var successor =
        request(
            "POST",
            endpoint("/assessments"),
            Map.of(
                "idempotencyKey",
                UUID.randomUUID().toString(),
                "supersedesAssessmentId",
                id,
                "correctionReason",
                "Synthetic review; retain prior evidence"),
            200);
    assertEquals(id, successor.get("supersedesAssessmentId").getAsString());
    assertEquals(saved, request("GET", endpoint("/assessments/" + id), null, 200));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_anpa_assessment WHERE loan_id=? AND (id=? OR supersedes_id=?)",
            Integer.class,
            loan,
            id,
            id));
    assertEquals(before, financialFingerprint());
  }

  @Test
  @Order(3)
  void concurrentIdempotencyStoresOneImmutableSnapshot() throws Exception {
    var before = financialFingerprint();
    String key = UUID.randomUUID().toString();
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Integer> attempt =
          () -> {
            start.await();
            var request =
                HttpRequest.newBuilder(URI.create(base + endpoint("/assessments")))
                    .header("Authorization", auth)
                    .header("Fineract-Platform-TenantId", "default")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            AnpaSupport.JSON.toJson(Map.of("idempotencyKey", key))))
                    .build();
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
          };
      var first = pool.submit(attempt);
      var second = pool.submit(attempt);
      start.countDown();
      var codes =
          List.of(
              first.get(40, java.util.concurrent.TimeUnit.SECONDS),
              second.get(40, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(codes.contains(200));
      assertTrue(codes.stream().allMatch(code -> code == 200 || code == 409));
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_anpa_assessment WHERE tenant_identifier='default' AND idempotency_key=?",
            Integer.class,
            key));
    var saved = request("POST", endpoint("/assessments"), Map.of("idempotencyKey", key), 200);
    assertEquals(
        saved,
        request("GET", endpoint("/assessments/" + saved.get("id").getAsString()), null, 200));
    assertEquals(before, financialFingerprint());
  }

  @Test
  @Order(3)
  void matchingNativeLoanIdsAndAssessmentKeysAreIsolatedAcrossTenants() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        secondary != null, "Use the native harness to provision the second tenant.");
    assertEquals(
        loan,
        secondaryLoan,
        "This fixture requires fresh databases with matching native loan IDs.");
    var primaryBefore = financialFingerprint();
    var secondaryBefore = financialFingerprint(secondary);
    var primary = request("GET", endpoint("/preview"), null, 200);
    var other = requestAs("GET", endpoint("/preview"), null, 200, auth, "anpa-secondary");
    assertEquals("default", primary.get("tenant").getAsString());
    assertEquals("anpa-secondary", other.get("tenant").getAsString());
    assertNotEquals(
        primary.getAsJsonObject("loan").getAsJsonObject("currentOutstanding").get("principal"),
        other.getAsJsonObject("loan").getAsJsonObject("currentOutstanding").get("principal"));
    String key = UUID.randomUUID().toString();
    var a = request("POST", endpoint("/assessments"), Map.of("idempotencyKey", key), 200);
    var b =
        requestAs(
            "POST",
            endpoint("/assessments"),
            Map.of("idempotencyKey", key),
            200,
            auth,
            "anpa-secondary");
    assertNotEquals(a.get("id"), b.get("id"));
    requestAs(
        "GET",
        endpoint("/assessments/" + a.get("id").getAsString()),
        null,
        404,
        auth,
        "anpa-secondary");
    request("GET", endpoint("/assessments/" + b.get("id").getAsString()), null, 404);
    assertEquals(primaryBefore, financialFingerprint());
    assertEquals(secondaryBefore, financialFingerprint(secondary));
  }

  @Test
  @Order(4)
  void validationAndAuthenticationFailClosed() throws Exception {
    requestAs("GET", endpoint("/preview"), null, 401, null, "default");
    requestAs("GET", endpoint("/preview"), null, 400, auth, "anpa-nonexistent");
    request("GET", endpoint("/preview?asOf=2026-02-30"), null, 400);
    request("GET", endpoint("/assessments?limit=100000"), null, 400);
    request("GET", endpoint("/assessments/invalid"), null, 400);
    request(
        "POST",
        endpoint("/assessments"),
        Map.of("idempotencyKey", UUID.randomUUID().toString(), "balance", 0),
        400);
    request("POST", endpoint("/writeoff"), Map.of(), 404);
  }

  @Test
  @Order(4)
  void separatePermissionsAndOfficeScopeAreEnforced() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    long role =
        create(
            "/roles",
            Map.of("name", "ANPA read " + suffix, "description", "Synthetic least privilege test"),
            "resourceId");
    request(
        "PUT",
        "/api/v1/roles/" + role + "/permissions",
        Map.of(
            "permissions",
            Map.of(
                "READ_ANPA_ACCOUNTING",
                true,
                "READ_LOAN",
                true,
                "READ_GLACCOUNT",
                true,
                "READ_JOURNALENTRY",
                true)),
        200);
    var user = new HashMap<String, Object>();
    user.putAll(
        Map.of(
            "username",
            "anpa" + suffix,
            "firstname",
            "Synthetic",
            "lastname",
            "ANPA",
            "email",
            "anpa" + suffix + "@example.test",
            "officeId",
            1,
            "roles",
            List.of(role),
            "password",
            "AnpaTest#123456",
            "repeatPassword",
            "AnpaTest#123456",
            "sendPasswordToEmail",
            false));
    create("/users", user, "resourceId");
    String readAuth =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    ("anpa" + suffix + ":AnpaTest#123456")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    requestAs("GET", endpoint("/eligibility"), null, 200, readAuth, "default");
    requestAs("GET", endpoint("/preview"), null, 403, readAuth, "default");
    requestAs("GET", endpoint("/assessments"), null, 403, readAuth, "default");
    requestAs(
        "POST",
        endpoint("/assessments"),
        Map.of("idempotencyKey", UUID.randomUUID().toString()),
        403,
        readAuth,
        "default");
    // Every permission still requires customer/loan office scope, including replay and audit.
    request(
        "PUT",
        "/api/v1/roles/" + role + "/permissions",
        Map.of(
            "permissions",
            Map.of(
                "PREVIEW_ANPA_WRITEOFF",
                true,
                "CREATE_ANPA_ASSESSMENT",
                true,
                "READ_ANPA_AUDIT",
                true)),
        200);
    var office = dated("openingDate", "2026-01-01");
    office.putAll(Map.of("name", "ANPA isolated office " + suffix, "parentId", 1));
    long officeId = create("/offices", office, "resourceId");
    user.put("username", "scope" + suffix);
    user.put("officeId", officeId);
    user.put("email", "scope" + suffix + "@example.test");
    create("/users", user, "resourceId");
    String scopedAuth =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    ("scope" + suffix + ":AnpaTest#123456")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    for (String path :
        List.of("/eligibility", "/history", "/receivables", "/preview", "/assessments"))
      requestAs("GET", endpoint(path), null, 404, scopedAuth, "default");
    requestAs(
        "POST",
        endpoint("/assessments"),
        Map.of("idempotencyKey", UUID.randomUUID().toString()),
        404,
        scopedAuth,
        "default");
  }

  @Test
  @Order(5)
  void nativeWriteoffRemainsNativeAndHistoricalReviewRemainsBlocked() throws Exception {
    create(
        "/loans/" + loan + "/transactions?command=writeoff",
        dated("transactionDate", day),
        "loanId");
    var before = financialFingerprint();
    var p =
        AnpaSupport.JSON.fromJson(request("GET", endpoint("/preview"), null, 200), Preview.class);
    assertEquals(601, p.loan().nativeStatus());
    assertTrue(p.evidence().transactions().stream().anyMatch(t -> t.type() == 6 && !t.reversed()));
    assertFalse(p.financiallyExecutable());
    assertEquals(before, financialFingerprint());
  }

  @Test
  @Order(6)
  void migrationRerunLeavesNativeFinancialDataAndSavedEvidenceUnchanged() throws Exception {
    var before = financialFingerprint();
    int assessments = jdbc.queryForObject("SELECT count(*) FROM m_anpa_assessment", Integer.class);
    for (int i = 0; i < 2; i++)
      try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
        var db =
            DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        try (var lb =
            new Liquibase(
                "db/changelog/tenant/module/savings/parts/091-anpa-writeoff-assessments.xml",
                new ClassLoaderResourceAccessor(),
                db)) {
          lb.update(new Contexts(), new LabelExpression());
        }
      }
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_permission WHERE code IN ('READ_ANPA_ACCOUNTING','PREVIEW_ANPA_WRITEOFF','CREATE_ANPA_ASSESSMENT','READ_ANPA_AUDIT')",
            Integer.class));
    assertEquals(
        assessments, jdbc.queryForObject("SELECT count(*) FROM m_anpa_assessment", Integer.class));
    assertEquals(before, financialFingerprint());
  }
}
