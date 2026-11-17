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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Opt-in real Fineract HTTP/security/tenant migration verification. Only a separately provisioned,
 * disposable web1349_* database on loopback may be used; never opens accounts or posts
 * transactions.
 */
class InvestmentRateNativeHttpIntegrationTest {
  String base, checker, password;
  JdbcTemplate jdbc;

  @Test
  void actualFineractAuthenticatesPublishesSchedulesAndPreservesFinancialTables() {
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
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM databasechangelog WHERE id LIKE 'ss-095-%'", Integer.class));
    var finances = financialCounts();
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String create =
        """
        {"name":"WEB1349 SYNTHETIC %s","shortName":"%s","description":"Disposable BE02 HTTP test",
        "currencyCode":"USD","digitsAfterDecimal":2,"inMultiplesOf":1,"nominalAnnualInterestRate":8,
        "interestCompoundingPeriodType":1,"interestPostingPeriodType":4,"interestCalculationType":1,
        "interestCalculationDaysInYearType":365,"accountingRule":1,"minDepositTerm":1,"maxDepositTerm":1825,
        "minDepositTermTypeId":0,"maxDepositTermTypeId":0,"depositAmount":1000,"minDepositAmount":100,
        "maxDepositAmount":100000,"preClosurePenalApplicable":false,"locale":"en",
        "charts":[{"name":"Synthetic native baseline","fromDate":"2020-01-01","dateFormat":"yyyy-MM-dd",
        "locale":"en","isPrimaryGroupingByAmount":false,
        "chartSlabs":[{"periodType":0,"fromPeriod":1,"annualInterestRate":8,"locale":"en"}]}]}
        """
            .formatted(suffix, suffix.substring(0, 4));
    long product =
        call("mifos", "password", "POST", "/fixeddepositproducts", create, 200)
            .jsonPath()
            .getLong("resourceId");
    var nativeBefore =
        JsonParser.parseString(
            call("mifos", "password", "GET", "/fixeddepositproducts/" + product, null, 200)
                .asString());
    String root = "/investment-products/" + product + "/rates";
    call(
        "mifos",
        "password",
        "PUT",
        "/investment-products/configuration/" + product,
        InvestmentProductFixtures.JSON.replace("MXN", "USD"),
        200);
    String businessDate =
        call("mifos", "password", "GET", root + "/template", null, 200)
            .jsonPath()
            .getString("businessDate");
    call(null, null, "GET", root + "/template", null, 401);
    String draft = InvestmentRateFixtures.request(0, null, "8.123456").replace("MXN", "USD");
    call("mifos", "password", "POST", root + "/validate", draft, 200);
    call("mifos", "password", "PUT", root + "/draft", draft, 200);
    call("mifos", "password", "POST", root + "/publish", "{\"expectedRevision\":1}", 403);

    long role =
        call(
                "mifos",
                "password",
                "POST",
                "/roles",
                InvestmentProductJson.JSON.toJson(
                    Map.of(
                        "name",
                        "BE02 reviewer " + suffix,
                        "description",
                        "Synthetic rate reviewer")),
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
    checker = "be02checker" + suffix;
    password = "Synthetic!" + suffix + "A9";
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
                password,
                "repeatPassword",
                password,
                "sendPasswordToEmail",
                false)),
        200);
    call(checker, password, "PUT", root + "/draft", draft, 403);
    call(checker, password, "POST", root + "/publish", "{\"expectedRevision\":1}", 200);
    assertTrue(
        call(checker, password, "GET", root + "/selection?amount=1000.00&termDays=90", null, 200)
            .asString()
            .contains("8.123456"));

    String tomorrow = LocalDate.parse(businessDate).plusDays(1).toString();
    call(
        "mifos",
        "password",
        "PUT",
        root + "/draft",
        InvestmentRateFixtures.request(1, tomorrow, "9.123456").replace("MXN", "USD"),
        200);
    call(checker, password, "POST", root + "/publish", "{\"expectedRevision\":2}", 200);
    assertTrue(
        call(
                checker,
                password,
                "GET",
                root + "/selection?amount=1000&termDays=90&asOfInstant=" + tomorrow + "T00:00:00Z",
                null,
                200)
            .asString()
            .contains("9.123456"));
    call(checker, password, "GET", root + "/schedule", null, 200);
    call(checker, password, "GET", root + "/versions/1", null, 200);
    assertEquals(
        4,
        call(checker, password, "GET", root + "/history", null, 200)
            .jsonPath()
            .getList("$")
            .size());
    assertEquals(
        nativeBefore,
        JsonParser.parseString(
            call("mifos", "password", "GET", "/fixeddepositproducts/" + product, null, 200)
                .asString()));
    assertEquals(finances, financialCounts());
  }

  Map<String, Integer> financialCounts() {
    return Map.of(
        "accounts",
        jdbc.queryForObject("SELECT COUNT(*) FROM m_savings_account", Integer.class),
        "transactions",
        jdbc.queryForObject("SELECT COUNT(*) FROM m_savings_account_transaction", Integer.class),
        "journals",
        jdbc.queryForObject("SELECT COUNT(*) FROM acc_gl_journal_entry", Integer.class));
  }

  Response call(String user, String secret, String method, String path, String json, int expected) {
    var request =
        given()
            .relaxedHTTPSValidation()
            .baseUri(base)
            .header("Fineract-Platform-TenantId", "default")
            .contentType("application/json");
    if (user != null) request.auth().preemptive().basic(user, secret);
    if (json != null) request.body(json);
    var response = request.request(method, path);
    assertEquals(expected, response.statusCode(), response::asString);
    return response;
  }
}
