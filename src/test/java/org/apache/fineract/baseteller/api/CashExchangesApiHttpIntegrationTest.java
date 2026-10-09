package org.apache.fineract.baseteller.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.restassured.config.JsonConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.config.JsonPathConfig.NumberReturnType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.fineract.testing.support.SavingsIntegrationTestBase;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CashExchangesApiHttpIntegrationTest extends SavingsIntegrationTestBase {
  private static final String PATH =
      SavingsTestUtils.CONTEXT_PATH + "/api/v2/base-teller/cash-exchanges";
  private static String currency, date, cashier, large, small;

  @BeforeAll
  static void seed() {
    currency =
        querySingleValueInDefaultTenant(
            "SELECT code FROM m_organisation_currency WHERE decimal_places<=6 ORDER BY code LIMIT"
                + " 1");
    date =
        given(auth())
            .queryParam("officeId", 1)
            .get(SavingsTestUtils.CONTEXT_PATH + "/api/v2/base-teller/cash-allocations/context")
            .then()
            .statusCode(200)
            .extract()
            .path("businessDate");
    executeSqlInDefaultTenant(
        """
        INSERT INTO m_service_payment_denomination(identifier,currency_code,value,denomination_type,active)
        VALUES ('web1257-large',%s,500,'BANKNOTE',true),('web1257-small',%s,100,'BANKNOTE',true)
        ON CONFLICT(currency_code,value) DO UPDATE SET active=true;
        INSERT INTO m_staff(is_loan_officer,office_id,firstname,lastname,display_name,is_active)
        VALUES(false,1,'WEB1257','Cashier','WEB-1257 Cashier',true);
        INSERT INTO m_tellers(office_id,name,description,state) VALUES(1,'WEB-1257 Exchange Window','Exchange integration fixture',300);
        INSERT INTO m_cashiers(staff_id,teller_id,description,full_day)
        SELECT s.id,t.id,'WEB-1257 exchange fixture',true FROM m_staff s,m_tellers t WHERE s.display_name='WEB-1257 Cashier' AND t.name='WEB-1257 Exchange Window';
        """,
        currency, currency);
    executeSqlInDefaultTenant(
        """
        INSERT INTO m_office(id,parent_id,hierarchy,name,opening_date) VALUES(91257,1,'.1.91257.','WEB-1257 Restricted Branch',DATE '2026-01-01');
        INSERT INTO m_role(name,description,is_disabled) VALUES('WEB-1257 exchange role','Cash exchange only',false);
        INSERT INTO m_appuser(office_id,username,firstname,lastname,password,email,firsttime_login_remaining,nonexpired,nonlocked,nonexpired_credentials,enabled,last_time_password_updated,password_never_expires)
        SELECT 91257,'web1257-restricted','WEB1257','Restricted',password,'web1257@example.test',false,true,true,true,true,CURRENT_DATE,true FROM m_appuser WHERE username='mifos';
        INSERT INTO m_role_permission(role_id,permission_id) SELECT r.id,p.id FROM m_role r,m_permission p WHERE r.name='WEB-1257 exchange role' AND p.code IN ('READ_BASE_TELLER_CASH_EXCHANGE','CREATE_BASE_TELLER_CASH_EXCHANGE','REPRINT_BASE_TELLER_CASH_EXCHANGE');
        INSERT INTO m_appuser_role(appuser_id,role_id) SELECT u.id,r.id FROM m_appuser u,m_role r WHERE u.username='web1257-restricted' AND r.name='WEB-1257 exchange role';
        """);
    cashier =
        querySingleValueInDefaultTenant(
            "SELECT c.id FROM m_cashiers c JOIN m_tellers t ON t.id=c.teller_id WHERE"
                + " t.name='WEB-1257 Exchange Window'");
    large =
        querySingleValueInDefaultTenant(
            "SELECT identifier FROM m_service_payment_denomination WHERE currency_code="
                + sqlLiteral(currency)
                + " AND value=500");
    small =
        querySingleValueInDefaultTenant(
            "SELECT identifier FROM m_service_payment_denomination WHERE currency_code="
                + sqlLiteral(currency)
                + " AND value=100");
  }

  @BeforeEach
  void reset() {
    executeSqlInDefaultTenant(
        """
        DELETE FROM m_cash_exchange_detail WHERE exchange_id IN(SELECT id FROM m_cash_exchange WHERE cashier_id=%s);
        DELETE FROM m_cash_exchange WHERE cashier_id=%s;
        DELETE FROM m_cash_allocation_denomination WHERE allocation_id IN(SELECT id FROM m_cash_allocation WHERE idempotency_key='web1257-opening');
        DELETE FROM m_cash_allocation WHERE idempotency_key='web1257-opening';
        DELETE FROM m_cashier_transactions WHERE cashier_id=%s;
        INSERT INTO m_cashier_transactions(cashier_id,txn_type,txn_date,txn_amount,created_date,currency_code,txn_note)
        VALUES(%s,101,%s,2500,CURRENT_TIMESTAMP,%s,'WEB-1257 recorded opening');
        INSERT INTO m_cash_allocation(idempotency_key,request_fingerprint,receipt_number,operation_type,status,business_date,office_id,office_name,initiated_by,initiated_by_username,source_name,destination_cashier_id,destination_name,destination_key,currency_code,amount,source_balance_before,source_balance_after,destination_balance_before,destination_balance_after,destination_cashier_transaction_id)
        SELECT 'web1257-opening',repeat('a',64),'WEB1257-OPEN','HEAD_CASHIER_ALLOCATION','COMPLETED',%s,1,'Head Office',u.id,u.username,'Recorded vault',%s,'WEB-1257 Cashier','CASHIER:'||%s,%s,2500,2500,0,0,2500,ct.id
        FROM m_appuser u,m_cashier_transactions ct WHERE u.username='mifos' AND ct.cashier_id=%s;
        INSERT INTO m_cash_allocation_denomination(allocation_id,denomination_identifier,denomination_type,denomination_value,quantity,line_total)
        SELECT id,%s,'BANKNOTE',500,1,500 FROM m_cash_allocation WHERE idempotency_key='web1257-opening';
        INSERT INTO m_cash_allocation_denomination(allocation_id,denomination_identifier,denomination_type,denomination_value,quantity,line_total)
        SELECT id,%s,'BANKNOTE',100,20,2000 FROM m_cash_allocation WHERE idempotency_key='web1257-opening';
        """,
        cashier, cashier, cashier, cashier, date, currency, date, cashier, cashier, currency,
        cashier, large, small);
  }

  private String body(String key, long received, long delivered) {
    return """
    {"cashierId":%s,"currencyCode":"%s","receivedDenominations":[{"denominationId":"%s","quantity":%s}],"deliveredDenominations":[{"denominationId":"%s","quantity":%s}],"idempotencyKey":"%s"}
    """
        .formatted(cashier, currency, large, received, small, delivered, key);
  }

  private static RequestSpecification auth() {
    return SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password")
        .config(
            RestAssuredConfig.config()
                .jsonConfig(JsonConfig.jsonConfig().numberReturnType(NumberReturnType.BIG_DECIMAL)))
        .relaxedHTTPSValidation();
  }

  private Response post(String suffix, String body) {
    return given(auth()).contentType(ContentType.JSON).body(body).post(PATH + suffix);
  }

  private Response get(String suffix) {
    return given(auth()).get(PATH + suffix);
  }

  private String snapshot() {
    return querySingleValueInDefaultTenant(
        "SELECT CONCAT((SELECT COUNT(*) FROM m_cash_exchange WHERE cashier_id="
            + cashier
            + "),':',(SELECT COALESCE(SUM(quantity),0) FROM m_cash_exchange_detail),':',(SELECT"
            + " SUM(txn_amount) FROM m_cashier_transactions WHERE cashier_id="
            + cashier
            + "),':',(SELECT COUNT(*) FROM acc_gl_journal_entry))");
  }

  private long quantity(String identifier) {
    return given(auth())
        .queryParam("cashierId", cashier)
        .queryParam("currencyCode", currency)
        .get(PATH + "/denominations")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getLong(
            "denominations.find { it.denominationId == '" + identifier + "' }.availableQuantity");
  }

  @Test
  void completeExchangeChangesOnlyCompositionAndAppearsOnceInHistory() {
    get("/context")
        .then()
        .statusCode(200)
        .body("tellers.id", hasItem(Integer.valueOf(cashier)))
        .body("currencies.code", hasItem(currency));
    String before = snapshot();
    assertEquals(1, quantity(large));
    assertEquals(20, quantity(small));
    post("/preview", body("success", 2, 10))
        .then()
        .statusCode(200)
        .body("balanced", equalTo(true))
        .body("receivedAmount", comparesEqualTo(new BigDecimal("1000")))
        .body("deliveredAmount", comparesEqualTo(new BigDecimal("1000")))
        .body("netMonetaryEffect", equalTo(0));
    assertEquals(before, snapshot());
    Response created = post("", body("success", 2, 10));
    created.then().statusCode(200).body("status", equalTo("COMPLETED"));
    long id = created.jsonPath().getLong("id");
    String receipt = created.jsonPath().getString("receiptNumber");
    assertEquals(3, quantity(large));
    assertEquals(10, quantity(small));
    assertEquals(
        new BigDecimal("2500"),
        new BigDecimal("500")
            .multiply(BigDecimal.valueOf(quantity(large)))
            .add(new BigDecimal("100").multiply(BigDecimal.valueOf(quantity(small)))));
    get("/" + id)
        .then()
        .statusCode(200)
        .body("receiptNumber", equalTo(receipt))
        .body("receivedDenominations[0].quantity", equalTo(2))
        .body("deliveredDenominations[0].quantity", equalTo(10));
    get("/" + id + "/receipt")
        .then()
        .statusCode(200)
        .body("id", equalTo((int) id))
        .body("receiptNumber", equalTo(receipt));
    String after = snapshot();
    post("", body("success", 2, 10)).then().statusCode(200).body("id", equalTo((int) id));
    assertEquals(after, snapshot());
    assertEquals(
        before.substring(before.indexOf(':', before.indexOf(':') + 1)),
        after.substring(after.indexOf(':', after.indexOf(':') + 1)));
    given(auth())
        .queryParam("tellerId", cashier)
        .queryParam("reference", receipt)
        .get(SavingsTestUtils.CONTEXT_PATH + "/api/v2/base-teller/transaction-history")
        .then()
        .statusCode(200)
        .body("totalFilteredRecords", equalTo(1))
        .body("items[0].historyId", equalTo("CASH_EXCHANGE:" + id))
        .body("items[0].inflow", comparesEqualTo(BigDecimal.ZERO))
        .body("items[0].outflow", comparesEqualTo(BigDecimal.ZERO));
    given(auth())
        .get(
            SavingsTestUtils.CONTEXT_PATH
                + "/api/v2/base-teller/transaction-history/CASH_EXCHANGE:"
                + id
                + "/receipt")
        .then()
        .statusCode(200);
    given(auth())
        .queryParam("custodianKey", "TELLER:" + cashier)
        .queryParam("transactionType", "CASH")
        .queryParam("currencyCode", currency)
        .get(SavingsTestUtils.CONTEXT_PATH + "/api/v2/base-teller/cash-inventory")
        .then()
        .statusCode(200)
        .body("[0].balance", comparesEqualTo(new BigDecimal("2500")));
  }

  @Test
  void unbalancedAndUnavailableCashLeaveDatabaseUnchanged() {
    String before = snapshot();
    assertTrue(post("/preview", body("mismatch", 2, 9)).statusCode() >= 400);
    assertTrue(post("", body("mismatch", 2, 9)).statusCode() >= 400);
    assertTrue(post("", body("unavailable", 5, 25)).statusCode() >= 400);
    assertEquals(before, snapshot());
  }

  @Test
  void invalidConfigurationAndNoopsAreRejected() {
    String before = snapshot();
    for (String b :
        new String[] {
          body("bad", 2, 10).replace(currency, "ZZZ"),
          body("bad", 2, 10).replace(large, "unknown-denomination"),
          body("bad", 2, 10)
              .replace("\"cashierId\":" + cashier, "\"cashierId\":9223372036854775807"),
          body("zero", 0, 0),
          body("negative", -2, -10),
          body("bad", 2, 10).replace("\"quantity\":2", "\"quantity\":2.5")
        }) assertTrue(post("/preview", b).statusCode() >= 400);
    assertEquals(before, snapshot());
  }

  @Test
  void sameDenominationBothSidesUsesNetDelta() {
    String b =
        body("overlap", 1, 10)
            .replace(
                "\"receivedDenominations\":[",
                "\"receivedDenominations\":[{\"denominationId\":\""
                    + small
                    + "\",\"quantity\":5},");
    post("", b).then().statusCode(200);
    assertEquals(2, quantity(large));
    assertEquals(15, quantity(small));
  }

  @Test
  void idempotencyConflictIsRejected() {
    post("", body("conflict", 2, 10)).then().statusCode(200);
    String before = snapshot();
    assertTrue(post("", body("conflict", 1, 5)).statusCode() >= 400);
    assertEquals(before, snapshot());
  }

  @Test
  void unknownNativeMovementDoesNotInventAvailability() {
    executeSqlInDefaultTenant(
        "INSERT INTO"
            + " m_cashier_transactions(cashier_id,txn_type,txn_date,txn_amount,created_date,currency_code)"
            + " VALUES(%s,104,%s,100,CURRENT_TIMESTAMP,%s);",
        cashier, date, currency);
    String before = snapshot();
    assertTrue(post("", body("unknown", 2, 10)).statusCode() >= 400);
    assertEquals(before, snapshot());
  }

  @Test
  void concurrentExchangesCannotOverspendAndDuplicateKeysMutateOnce() throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<Integer> a = pool.submit(() -> post("", body("concurrent-a", 3, 15)).statusCode());
      Future<Integer> b = pool.submit(() -> post("", body("concurrent-b", 3, 15)).statusCode());
      assertEquals(1, (a.get() == 200 ? 1 : 0) + (b.get() == 200 ? 1 : 0));
      assertEquals(5, quantity(small));
    }
    reset();
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<Response> a = pool.submit(() -> post("", body("same-concurrent", 2, 10)));
      Future<Response> b = pool.submit(() -> post("", body("same-concurrent", 2, 10)));
      assertEquals(
          a.get().then().statusCode(200).extract().jsonPath().getLong("id"),
          b.get().then().statusCode(200).extract().jsonPath().getLong("id"));
      assertEquals(10, quantity(small));
    }
  }

  @Test
  void missingAndUnauthorizedResourcesAreDenied() {
    get("/9223372036854775807").then().statusCode(404);
    given(SavingsTestUtils.requestSpec(getFineractPort()))
        .get(PATH + "/context")
        .then()
        .statusCode(401);
  }

  @Test
  void branchScopeIsEnforcedForEveryEndpoint() {
    var restricted =
        SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "web1257-restricted", "password");
    given(restricted).get(PATH + "/context").then().statusCode(200).body("tellers", hasSize(0));
    long id =
        post("", body("restricted-source", 2, 10))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getLong("id");
    given(restricted).get(PATH + "/" + id).then().statusCode(404);
    given(restricted).get(PATH + "/" + id + "/receipt").then().statusCode(404);
    assertTrue(
        given(restricted)
                .contentType(ContentType.JSON)
                .body(body("forbidden", 1, 5))
                .post(PATH)
                .statusCode()
            >= 400);
    assertTrue(
        given(restricted)
                .contentType(ContentType.JSON)
                .body(body("forbidden", 1, 5))
                .post(PATH + "/preview")
                .statusCode()
            >= 400);
    assertTrue(
        given(restricted)
                .queryParam("cashierId", cashier)
                .queryParam("currencyCode", currency)
                .get(PATH + "/denominations")
                .statusCode()
            >= 400);
  }

  @Test
  void historyDenominationsKeepReceivedAndDeliveredRoles() {
    long id =
        post("", body("history-roles", 2, 10))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getLong("id");
    given(auth())
        .get(
            SavingsTestUtils.CONTEXT_PATH
                + "/api/v2/base-teller/transaction-history/CASH_EXCHANGE:"
                + id
                + "/denominations")
        .then()
        .statusCode(200)
        .body("receivedDenominations[0].quantity", equalTo(2))
        .body("deliveredDenominations[0].quantity", equalTo(10))
        .body("changeDenominations", hasSize(0));
  }

  @Test
  void disabledDenominationCannotBeExchanged() {
    executeSqlInDefaultTenant(
        "UPDATE m_service_payment_denomination SET active=false WHERE currency_code=%s AND"
            + " identifier=%s;",
        currency, large);
    try {
      assertTrue(post("", body("disabled", 2, 10)).statusCode() >= 400);
    } finally {
      executeSqlInDefaultTenant(
          "UPDATE m_service_payment_denomination SET active=true WHERE currency_code=%s AND"
              + " identifier=%s;",
          currency, large);
    }
  }

  @Test
  void foreignDenominationsAreRejectedAndCurrenciesStayIsolated() {
    String other =
        querySingleValueInDefaultTenant(
            "SELECT code FROM m_currency WHERE code<>"
                + sqlLiteral(currency)
                + " AND decimal_places<=6 ORDER BY code LIMIT 1");
    executeSqlInDefaultTenant(
        """
        INSERT INTO m_organisation_currency(code,decimal_places,currency_multiplesof,name,internationalized_name_code,display_symbol)
        SELECT code,decimal_places,currency_multiplesof,name,internationalized_name_code,display_symbol FROM m_currency
        WHERE code=%s AND NOT EXISTS(SELECT 1 FROM m_organisation_currency WHERE code=%s);
        INSERT INTO m_service_payment_denomination(identifier,currency_code,value,denomination_type,active)
        SELECT 'web1257-foreign',%s,500,'NOTE',true WHERE NOT EXISTS(SELECT 1 FROM m_service_payment_denomination WHERE currency_code=%s AND value=500);
        """,
        other, other, other, other);
    String foreign =
        querySingleValueInDefaultTenant(
            "SELECT identifier FROM m_service_payment_denomination WHERE currency_code="
                + sqlLiteral(other)
                + " AND value=500");
    String before = snapshot();
    assertTrue(post("", body("foreign", 2, 10).replace(large, foreign)).statusCode() >= 400);
    assertEquals(before, snapshot());
    given(auth())
        .queryParam("cashierId", cashier)
        .queryParam("currencyCode", other)
        .get(PATH + "/denominations")
        .then()
        .statusCode(200)
        .body("denominations[0].availableQuantity", equalTo(0));
    post("", body("isolated", 2, 10)).then().statusCode(200);
    given(auth())
        .queryParam("cashierId", cashier)
        .queryParam("currencyCode", other)
        .get(PATH + "/denominations")
        .then()
        .statusCode(200)
        .body("denominations[0].availableQuantity", equalTo(0));
    assertEquals(3, quantity(large));
    assertEquals(10, quantity(small));
  }
}
