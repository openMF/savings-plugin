/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Opt-in deployed pinned Fineract verification against disposable loopback web1349_* databases. */
class InvestmentSimulationNativeHttpIntegrationTest {
  static final String ROOT = "/investment-products/simulations";
  String base, checker, secret, today;
  JdbcTemplate jdbc;

  @Test
  void nativeSecurityMigrationsGrossProjectionsAuditAndNonExecution() throws Exception {
    base = System.getProperty("investment.native.url");
    Assumptions.assumeTrue(
        base != null,
        "Set investment.native.url and investment.native.jdbcUrl for an isolated native runtime.");
    String url = System.getProperty("investment.native.jdbcUrl", "");
    if (!base.matches("https://(localhost|127\\.0\\.0\\.1):[0-9]+/fineract-provider/api/v1")
        || !url.matches(
            "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/web1349_[a-zA-Z0-9_]+"))
      throw new IllegalArgumentException(
          "An explicit loopback runtime and disposable web1349_* database are required.");
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                url,
                System.getProperty("investment.native.dbUser", System.getProperty("user.name")),
                System.getProperty("investment.native.dbPassword", "")));
    for (int migration : List.of(94, 95, 96))
      assertTrue(
          jdbc.queryForObject(
                  "SELECT count(*) FROM databasechangelog WHERE id LIKE ?",
                  Integer.class,
                  "ss-0" + migration + "-%")
              > 0);
    var finances = financialSnapshot();
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    createChecker(suffix);
    call(null, null, "GET", ROOT + "/template", null, 401);
    call(checker, secret, "POST", ROOT, InvestmentSimulationFixtures.request(1, null), 403);
    long note = product(suffix, false);
    configure(note, false);
    today =
        call(
                "mifos",
                "password",
                "GET",
                "/investment-products/" + note + "/rates/template",
                null,
                200)
            .jsonPath()
            .getString("businessDate");
    var missing = call("mifos", "password", "POST", ROOT + "/validate", request(note, false), 200);
    assertEquals("NO_APPLICABLE_RATE", missing.jsonPath().getString("status"));
    pricing(note, false);
    var nativeBefore = jdbc.queryForMap("SELECT * FROM m_savings_product WHERE id=?", note);
    long count = jdbc.queryForObject("SELECT count(*) FROM m_investment_simulation", Long.class);
    var validation =
        call("mifos", "password", "POST", ROOT + "/validate", request(note, false), 200);
    assertEquals("COMPLETE_GROSS_PROJECTION", validation.jsonPath().getString("status"));
    assertEquals(
        count, jdbc.queryForObject("SELECT count(*) FROM m_investment_simulation", Long.class));
    var created = call("mifos", "password", "POST", ROOT, request(note, false), 200);
    String id = created.jsonPath().getString("simulationId");
    assertEquals("COMPLETE_GROSS_PROJECTION", created.jsonPath().getString("status"));
    assertNotNull(created.jsonPath().get("grossInterest"));
    assertFalse(created.jsonPath().getBoolean("fundingAssessment.balanceVerified"));
    assertEquals(
        created.asString(),
        call("mifos", "password", "GET", ROOT + "/" + id, null, 200).asString());
    assertEquals(
        id,
        call("mifos", "password", "POST", ROOT, request(note, false), 200)
            .jsonPath()
            .getString("simulationId"));
    var different = JsonParser.parseString(request(note, false)).getAsJsonObject();
    different.addProperty("termDays", 91);
    call("mifos", "password", "POST", ROOT, different.toString(), 409);
    assertEquals(
        1,
        call("mifos", "password", "GET", ROOT + "/history?productId=" + note, null, 200)
            .jsonPath()
            .getList("$")
            .size());
    assertEquals(
        count + 1, jdbc.queryForObject("SELECT count(*) FROM m_investment_simulation", Long.class));
    assertEquals(
        nativeBefore, jdbc.queryForMap("SELECT * FROM m_savings_product WHERE id=?", note));
    call("mifos", "password", "POST", ROOT, "{}", 400);
    long cede = product("c" + suffix.substring(1), true);
    configure(cede, true);
    pricing(cede, true);
    var monthly = call("mifos", "password", "POST", ROOT, request(cede, true), 200);
    assertEquals("COMPLETE_GROSS_PROJECTION", monthly.jsonPath().getString("status"));
    assertEquals("CEDE", monthly.jsonPath().getString("investmentType"));
    assertTrue(monthly.jsonPath().getList("cycles[0].paymentSchedule").size() > 1);
    assertEquals(finances, financialSnapshot());
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("target/be03-native-example.json"), created.asString());
  }

  long product(String suffix, boolean monthly) {
    String json =
        """
        {"name":"WEB1349 BE03 SYNTHETIC %s","shortName":"%s","description":"Disposable simulation test",
        "currencyCode":"USD","digitsAfterDecimal":2,"inMultiplesOf":1,"nominalAnnualInterestRate":8,
        "interestCompoundingPeriodType":%d,"interestPostingPeriodType":%d,"interestCalculationType":1,
        "interestCalculationDaysInYearType":365,"accountingRule":1,"minDepositTerm":1,"maxDepositTerm":1825,
        "minDepositTermTypeId":0,"maxDepositTermTypeId":0,"depositAmount":1000,"minDepositAmount":100,
        "maxDepositAmount":100000,"preClosurePenalApplicable":false,"locale":"en",
        "charts":[{"name":"Synthetic native baseline","fromDate":"2020-01-01","dateFormat":"yyyy-MM-dd","locale":"en",
        "isPrimaryGroupingByAmount":false,"chartSlabs":[{"periodType":0,"fromPeriod":1,"annualInterestRate":8,"locale":"en"}]}]}
        """
            .formatted(suffix, suffix.substring(0, 4), monthly ? 4 : 7, monthly ? 4 : 7);
    return call("mifos", "password", "POST", "/fixeddepositproducts", json, 200)
        .jsonPath()
        .getLong("resourceId");
  }

  void configure(long product, boolean cede) {
    String policy =
        cede
            ? InvestmentProductFixtures.request(
                0,
                Map.of(
                    "investmentType",
                    "CEDE",
                    "minimumTermDays",
                    60,
                    "maximumTermDays",
                    180,
                    "termMultipleDays",
                    30,
                    "interestPayoutMode",
                    "MONTHLY"))
            : InvestmentProductFixtures.JSON;
    call(
        "mifos",
        "password",
        "PUT",
        "/investment-products/configuration/" + product,
        policy.replace("MXN", "USD"),
        200);
  }

  void pricing(long product, boolean cede) {
    var root =
        JsonParser.parseString(
                InvestmentRateFixtures.request(0, null, "8.123456").replace("MXN", "USD"))
            .getAsJsonObject();
    if (cede)
      root.getAsJsonObject("matrix")
          .add(
              "termBands",
              InvestmentProductJson.JSON.toJsonTree(
                  List.of(
                      new InvestmentRateData.TermBand(60, 90),
                      new InvestmentRateData.TermBand(120, 180))));
    String path = "/investment-products/" + product + "/rates";
    call("mifos", "password", "PUT", path + "/draft", root.toString(), 200);
    call(checker, secret, "POST", path + "/publish", "{\"expectedRevision\":1}", 200);
  }

  String request(long product, boolean cede) {
    return InvestmentSimulationFixtures.edit(
        product,
        "native-" + product,
        r -> {
          r.addProperty("startDate", today);
          if (cede) r.addProperty("interestPayoutMode", "MONTHLY");
        });
  }

  void createChecker(String suffix) {
    long role =
        call(
                "mifos",
                "password",
                "POST",
                "/roles",
                InvestmentProductJson.JSON.toJson(
                    Map.of(
                        "name",
                        "BE03 checker " + suffix,
                        "description",
                        "Synthetic independent pricing reviewer")),
                200)
            .jsonPath()
            .getLong("resourceId");
    call(
        "mifos",
        "password",
        "PUT",
        "/roles/" + role + "/permissions",
        "{\"permissions\":{\"READ_INVESTMENT_RATE\":true,\"APPROVE_INVESTMENT_RATE\":true}}",
        200);
    checker = "be03checker" + suffix;
    secret = "Synthetic!Ab9Cd7Ef2";
    call(
        "mifos",
        "password",
        "POST",
        "/users",
        InvestmentProductJson.JSON.toJson(
            Map.of(
                "username",
                checker,
                "firstname",
                "Synthetic",
                "lastname",
                "Reviewer",
                "email",
                checker + "@example.invalid",
                "officeId",
                1,
                "roles",
                List.of(role),
                "password",
                secret,
                "repeatPassword",
                secret,
                "sendPasswordToEmail",
                false)),
        200);
  }

  Map<String, String> financialSnapshot() {
    var result = new LinkedHashMap<String, String>();
    for (String table :
        List.of(
            "m_savings_account",
            "m_savings_account_transaction",
            "acc_gl_journal_entry",
            "m_account_transfer_details",
            "m_account_transfer_transaction",
            "m_savings_account_on_hold_transaction",
            "m_savings_account_transaction_tax_details",
            "m_deposit_account_term_and_preclosure")) {
      boolean exists =
          jdbc.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=current_schema() AND table_name=?)",
              Boolean.class,
              table);
      if (exists)
        result.put(
            table,
            jdbc.queryForObject(
                "SELECT COALESCE(md5(jsonb_agg(to_jsonb(t) ORDER BY id)::text),'empty') FROM "
                    + table
                    + " t",
                String.class));
    }
    assertTrue(result.containsKey("m_savings_account"));
    assertTrue(result.containsKey("acc_gl_journal_entry"));
    return result;
  }

  Response call(
      String user, String password, String method, String path, String json, int expected) {
    var request =
        given()
            .relaxedHTTPSValidation()
            .baseUri(base)
            .header("Fineract-Platform-TenantId", "default")
            .contentType("application/json");
    if (user != null) request.auth().preemptive().basic(user, password);
    if (json != null) request.body(json);
    var response = request.request(method, path);
    assertEquals(expected, response.statusCode(), response::asString);
    return response;
  }
}
