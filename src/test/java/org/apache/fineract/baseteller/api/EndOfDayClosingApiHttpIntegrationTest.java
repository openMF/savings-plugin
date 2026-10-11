package org.apache.fineract.baseteller.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import io.restassured.specification.RequestSpecification;
import java.util.*;
import org.apache.fineract.testing.support.SavingsIntegrationTestBase;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs against plugin-loaded Fineract and real native allocations, settlement and journal entries.
 */
class EndOfDayClosingApiHttpIntegrationTest extends SavingsIntegrationTestBase {
  private static final String PATH =
      SavingsTestUtils.CONTEXT_PATH + "/api/v2/base-teller/end-of-day-closing";
  private static final String ALLOCATION =
      SavingsTestUtils.CONTEXT_PATH + "/api/v2/base-teller/cash-allocations";
  private static String date,
      office,
      head,
      balancedCashier,
      shortageCashier,
      overageCashier,
      cashOverageCashier;
  private static List<String> currencies;
  private static final Map<String, String> denominationByCurrency = new HashMap<>();
  private static String primaryUsername, primaryCredential;

  private static RequestSpecification auth() {
    return SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password");
  }

  @BeforeAll
  static void seed() {
    primaryCredential = UUID.randomUUID().toString() + "aA1!";
    primaryUsername = "web1258-primary-" + UUID.randomUUID().toString().substring(0, 8);
    executeSqlInDefaultTenant(
        """
        INSERT INTO m_organisation_currency(code,decimal_places,currency_multiplesof,name,display_symbol,internationalized_name_code)
        SELECT code,decimal_places,currency_multiplesof,name,display_symbol,internationalized_name_code
        FROM m_currency WHERE code IN ('EUR','USD') AND code NOT IN (SELECT code FROM m_organisation_currency);
        """);
    var context = given(auth()).get(PATH + "/context").then().statusCode(200).extract().jsonPath();
    date = context.getString("businessDate");
    currencies = context.getList("currencies.currencyCode");
    assertTrue(currencies.size() >= 2);
    executeSqlInDefaultTenant(
        """
        INSERT INTO m_office(parent_id,hierarchy,name,opening_date)
        VALUES(1,'.1.','WEB-1258 HTTP Office',DATE '2026-01-01');
        UPDATE m_office SET hierarchy='.1.'||id||'.' WHERE name='WEB-1258 HTTP Office';
        INSERT INTO m_staff(is_loan_officer,office_id,firstname,lastname,display_name,is_active)
        SELECT false,id,'WEB1258','Primary','WEB-1258 HTTP Primary',true FROM m_office WHERE name='WEB-1258 HTTP Office';
        INSERT INTO m_staff(is_loan_officer,office_id,firstname,lastname,display_name,is_active)
        SELECT false,id,'WEB1258','Balanced','WEB-1258 HTTP Balanced',true FROM m_office WHERE name='WEB-1258 HTTP Office';
        INSERT INTO m_staff(is_loan_officer,office_id,firstname,lastname,display_name,is_active)
        SELECT false,id,'WEB1258','Shortage','WEB-1258 HTTP Shortage',true FROM m_office WHERE name='WEB-1258 HTTP Office';
        INSERT INTO m_staff(is_loan_officer,office_id,firstname,lastname,display_name,is_active)
        SELECT false,id,'WEB1258','Overage','WEB-1258 HTTP Overage',true FROM m_office WHERE name='WEB-1258 HTTP Office';
        INSERT INTO m_staff(is_loan_officer,office_id,firstname,lastname,display_name,is_active)
        SELECT false,id,'WEB1258','CashOverage','WEB-1258 HTTP Cash Overage',true FROM m_office WHERE name='WEB-1258 HTTP Office';
        INSERT INTO m_tellers(office_id,name,description,state)
        SELECT id,'WEB-1258 HTTP Window','End of day acceptance',300 FROM m_office WHERE name='WEB-1258 HTTP Office';
        INSERT INTO m_cashiers(staff_id,teller_id,description,full_day)
        SELECT s.id,t.id,s.display_name,true FROM m_staff s JOIN m_tellers t ON t.office_id=s.office_id
        WHERE t.name='WEB-1258 HTTP Window';
        INSERT INTO m_role(name,description,is_disabled) VALUES('WEB-1258 HTTP Authorizer','Primary cashier authorization',false);
        INSERT INTO m_role_permission(role_id,permission_id)
        SELECT r.id,p.id FROM m_role r,m_permission p WHERE r.name='WEB-1258 HTTP Authorizer'
        AND p.code='AUTHORIZE_BASE_TELLER_END_OF_DAY_CLOSING';
        INSERT INTO m_appuser(office_id,staff_id,username,firstname,lastname,password,email,firsttime_login_remaining,nonexpired,nonlocked,nonexpired_credentials,enabled,last_time_password_updated,password_never_expires)
        SELECT s.office_id,s.id,%s,'WEB1258','Primary',%s,'web1258-primary@example.test',false,true,true,true,true,CURRENT_DATE,true
        FROM m_staff s,m_appuser u WHERE s.display_name='WEB-1258 HTTP Primary' AND u.username='mifos';
        INSERT INTO m_appuser_role(appuser_id,role_id)
        SELECT u.id,r.id FROM m_appuser u,m_role r WHERE u.username=%s AND r.name='WEB-1258 HTTP Authorizer';
        INSERT INTO acc_gl_account(name,gl_code,disabled,manual_journal_entries_allowed,account_usage,classification_enum)
        VALUES('WEB-1258 Vault','WEB1258-VAULT',false,true,2,1),('WEB-1258 Teller','WEB1258-TELLER',false,true,2,1),('WEB-1258 Contra','WEB1258-CONTRA',false,true,2,4);
        INSERT INTO acc_gl_financial_activity_account(gl_account_id,financial_activity_type)
        SELECT id,101 FROM acc_gl_account WHERE gl_code='WEB1258-VAULT' ON CONFLICT(financial_activity_type) DO NOTHING;
        INSERT INTO acc_gl_financial_activity_account(gl_account_id,financial_activity_type)
        SELECT id,102 FROM acc_gl_account WHERE gl_code='WEB1258-TELLER' ON CONFLICT(financial_activity_type) DO NOTHING;
        INSERT INTO acc_gl_financial_activity_account(gl_account_id,financial_activity_type)
        SELECT id,300 FROM acc_gl_account WHERE gl_code='WEB1258-CONTRA' ON CONFLICT(financial_activity_type) DO NOTHING;
        """,
        primaryUsername,
        org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder()
            .encode(primaryCredential),
        primaryUsername);
    office =
        querySingleValueInDefaultTenant(
            "SELECT id FROM m_office WHERE name='WEB-1258 HTTP Office'");
    head = cashier("WEB-1258 HTTP Primary");
    balancedCashier = cashier("WEB-1258 HTTP Balanced");
    shortageCashier = cashier("WEB-1258 HTTP Shortage");
    overageCashier = cashier("WEB-1258 HTTP Overage");
    cashOverageCashier = cashier("WEB-1258 HTTP Cash Overage");
    for (String currency : currencies) {
      executeSqlInDefaultTenant(
          "INSERT INTO"
              + " m_service_payment_denomination(identifier,currency_code,value,denomination_type,active)"
              + " VALUES(%s,%s,1,'COIN',true) ON CONFLICT(currency_code,value) DO UPDATE SET"
              + " active=true;",
          "web1258-unit-" + currency, currency);
      String identifier =
          querySingleValueInDefaultTenant(
              "SELECT identifier FROM m_service_payment_denomination WHERE currency_code="
                  + sqlLiteral(currency)
                  + " AND value=1");
      allocate("SAFE_VAULT_OPENING", currency, identifier, 5000, null, null);
      allocate("HEAD_CASHIER_ALLOCATION", currency, identifier, 5000, null, head);
      denominationByCurrency.put(currency, identifier);
      {
        allocate(
            "OPERATIONAL_TELLER_ALLOCATION", currency, identifier, 1000, head, balancedCashier);
        allocate(
            "OPERATIONAL_TELLER_ALLOCATION", currency, identifier, 1000, head, shortageCashier);
        allocate("OPERATIONAL_TELLER_ALLOCATION", currency, identifier, 1000, head, overageCashier);
        allocate(
            "OPERATIONAL_TELLER_ALLOCATION",
            currency,
            identifier,
            currency.equals("USD") ? 500 : 1000,
            head,
            cashOverageCashier);
      }
    }
  }

  private static String cashier(String name) {
    return querySingleValueInDefaultTenant(
        "SELECT c.id FROM m_cashiers c JOIN m_staff s ON s.id=c.staff_id WHERE s.display_name="
            + sqlLiteral(name));
  }

  private static void allocate(
      String type,
      String currency,
      String identifier,
      int amount,
      String source,
      String destination) {
    var body = new JsonObject();
    body.addProperty("idempotencyKey", UUID.randomUUID().toString());
    body.addProperty("operationType", type);
    body.addProperty("officeId", Long.parseLong(office));
    body.addProperty("businessDate", date);
    body.addProperty("currencyCode", currency);
    body.addProperty("amount", amount);
    if (source != null) body.addProperty("sourceCashierId", Long.parseLong(source));
    if (destination != null) body.addProperty("destinationCashierId", Long.parseLong(destination));
    var line = new JsonObject();
    line.addProperty("denominationId", identifier);
    line.addProperty("quantity", amount);
    var lines = new JsonArray();
    lines.add(line);
    body.add("denominations", lines);
    var result = given(auth()).body(body.toString()).post(ALLOCATION);
    assertEquals(200, result.statusCode(), result.asString());
  }

  private static JsonObject declaration(String cashier, int quantity, String key) {
    var body = new JsonObject();
    body.addProperty("cashierId", Long.parseLong(cashier));
    body.addProperty("businessDate", date);
    body.addProperty("idempotencyKey", key);
    var declarations = new JsonArray();
    for (String currency : currencies) {
      var c = new JsonObject();
      c.addProperty("currencyCode", currency);
      c.addProperty("vouchers", 0);
      c.add("checks", new JsonArray());
      var ds = new JsonArray();
      {
        var d = new JsonObject();
        d.addProperty("denominationId", denominationByCurrency.get(currency));
        d.addProperty("quantity", quantity);
        ds.add(d);
      }
      c.add("denominations", ds);
      declarations.add(c);
    }
    body.add("currencies", declarations);
    return body;
  }

  private static void authorization(JsonObject body, String username, String credential) {
    var auth = new JsonObject();
    auth.addProperty("username", username);
    auth.addProperty("credential", credential);
    body.add("authorization", auth);
  }

  @Test
  void balancedAndShortageCloseWithRealPrimaryPasswordAndNativeSettlement() {
    for (String cashier : List.of(balancedCashier, shortageCashier, overageCashier)) {
      int quantity = cashier.equals(shortageCashier) ? 950 : 1000;
      var body = declaration(cashier, quantity, UUID.randomUUID().toString());
      if (cashier.equals(overageCashier))
        body.getAsJsonArray("currencies").get(0).getAsJsonObject().addProperty("vouchers", 50);
      given(auth()).queryParam("cashierId", cashier).get(PATH + "/checks").then().statusCode(200);
      var preview = given(auth()).body(body.toString()).post(PATH + "/preview");
      assertEquals(200, preview.statusCode(), preview.asString());
      assertEquals(
          cashier.equals(shortageCashier)
              ? "SHORTAGE"
              : cashier.equals(overageCashier) ? "OVERAGE" : "BALANCED",
          preview.jsonPath().getString("balancesByCurrency[0].reconciliationStatus"));
      assertEquals(
          "0",
          querySingleValueInDefaultTenant(
              "SELECT COUNT(*) FROM m_end_of_day_closing WHERE cashier_id=" + cashier));
      authorization(body, primaryUsername, "wrong-test-credential");
      var denied = given(auth()).body(body.toString()).post(PATH);
      assertEquals(403, denied.statusCode(), denied.asString());
      assertEquals(
          "0",
          querySingleValueInDefaultTenant(
              "SELECT COUNT(*) FROM m_end_of_day_closing WHERE cashier_id=" + cashier));
      authorization(body, primaryUsername, primaryCredential);
      var completed = given(auth()).body(body.toString()).post(PATH);
      assertEquals(200, completed.statusCode(), completed.asString());
      var id = completed.jsonPath().getLong("id");
      assertNotNull(completed.jsonPath().getLong("authorizedBy"));
      assertFalse(completed.asString().contains("credential"));
      var retry = given(auth()).body(body.toString()).post(PATH);
      assertEquals(200, retry.statusCode(), retry.asString());
      assertEquals(id, retry.jsonPath().getLong("id"));
      var retrieved = given(auth()).get(PATH + "/" + id);
      assertEquals(200, retrieved.statusCode());
      assertEquals(completed.jsonPath().getMap(""), retrieved.jsonPath().getMap(""));
      var report = given(auth()).get(PATH + "/" + id + "/report");
      assertEquals(200, report.statusCode());
      assertEquals(completed.jsonPath().getMap(""), report.jsonPath().getMap(""));
      assertEquals(
          String.valueOf(currencies.size()),
          querySingleValueInDefaultTenant(
              "SELECT COUNT(*) FROM m_cashier_transactions WHERE cashier_id="
                  + cashier
                  + " AND txn_type=102"));
      assertEquals(
          String.valueOf(currencies.size()),
          querySingleValueInDefaultTenant(
              "SELECT COUNT(*) FROM m_cashier_reconciliation WHERE end_of_day_closing_id=" + id));
      assertEquals(
          String.valueOf(currencies.size() * 2),
          querySingleValueInDefaultTenant(
              "SELECT COUNT(*) FROM acc_gl_journal_entry j JOIN m_cashier_reconciliation r ON"
                  + " r.accounting_transaction_id=j.transaction_id WHERE r.end_of_day_closing_id="
                  + id));
      assertInventoryAndHistory(cashier, id);
    }
  }

  @org.junit.jupiter.api.AfterAll
  static void credentialIsAbsentFromServerLogs() {
    assertFalse(fineract.getLogs().contains(primaryCredential));
    assertFalse(fineract.getLogs().contains("wrong-test-credential"));
  }

  @Test
  void physicalOverageAndShortageCompleteWithoutAccountingAdjustments() {
    var body = declaration(cashOverageCashier, 1000, UUID.randomUUID().toString());
    for (var c : body.getAsJsonArray("currencies")) {
      var declaration = c.getAsJsonObject();
      var code = declaration.get("currencyCode").getAsString();
      declaration
          .getAsJsonArray("denominations")
          .get(0)
          .getAsJsonObject()
          .addProperty("quantity", code.equals("EUR") ? 1050 : code.equals("USD") ? 475 : 1000);
    }
    var preview = given(auth()).body(body.toString()).post(PATH + "/preview");
    assertEquals(200, preview.statusCode(), preview.asString());
    authorization(body, primaryUsername, "wrong-test-credential");
    var denied = given(auth()).body(body.toString()).post(PATH);
    assertEquals(403, denied.statusCode());
    assertEquals(
        "0",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_cashier_reconciliation WHERE cashier_id="
                + cashOverageCashier));
    assertEquals(
        "0",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_cashier_transactions WHERE txn_type=102 AND cashier_id="
                + cashOverageCashier));
    authorization(body, primaryUsername, primaryCredential);
    var completed = given(auth()).body(body.toString()).post(PATH);
    assertEquals(200, completed.statusCode(), completed.asString());
    long id = completed.jsonPath().getLong("id");
    var balances =
        JsonParser.parseString(completed.asString())
            .getAsJsonObject()
            .getAsJsonArray("balancesByCurrency");
    for (var value : balances) {
      var b = value.getAsJsonObject();
      var code = b.get("currencyCode").getAsString();
      assertEquals(
          code.equals("EUR") ? "OVERAGE" : code.equals("USD") ? "SHORTAGE" : "BALANCED",
          b.get("reconciliationStatus").getAsString());
      assertEquals(code.equals("EUR") ? 50 : 0, b.get("overage").getAsInt());
      assertEquals(code.equals("USD") ? 25 : 0, b.get("shortage").getAsInt());
      assertEquals(code.equals("USD") ? 500 : 1000, b.get("settledCash").getAsInt());
      assertEquals(
          0,
          b.get("expectedCash")
              .getAsBigDecimal()
              .compareTo(b.get("settledCash").getAsBigDecimal()));
      assertEquals(
          "0",
          querySingleValueInDefaultTenant(
              "SELECT COUNT(*) FROM m_cashier_transactions WHERE cashier_id="
                  + cashOverageCashier
                  + " AND currency_code="
                  + sqlLiteral(code)
                  + " AND txn_type IN (103,104)"));
    }
    assertEquals(
        String.valueOf(currencies.size()),
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_cashier_transactions WHERE cashier_id="
                + cashOverageCashier
                + " AND txn_type=102"));
    assertEquals(
        String.valueOf(currencies.size() * 2),
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM acc_gl_journal_entry j JOIN m_cashier_reconciliation r ON"
                + " r.accounting_transaction_id=j.transaction_id WHERE r.end_of_day_closing_id="
                + id));
    assertEquals(
        completed.jsonPath().getMap(""),
        given(auth()).body(body.toString()).post(PATH).jsonPath().getMap(""));
    assertEquals(
        completed.jsonPath().getMap(""),
        given(auth()).get(PATH + "/" + id + "/report").jsonPath().getMap(""));
    assertInventoryAndHistory(cashOverageCashier, id);
  }

  private static void assertInventoryAndHistory(String cashier, long id) {
    var rows =
        given(auth())
            .queryParam("custodianKey", "TELLER:" + cashier)
            .get(SavingsTestUtils.CONTEXT_PATH + "/api/v2/base-teller/cash-inventory");
    assertEquals(200, rows.statusCode());
    for (var value : JsonParser.parseString(rows.asString()).getAsJsonArray()) {
      var row = value.getAsJsonObject();
      if (row.get("inventoryType").getAsString().equals("CASH")) {
        assertEquals(0, row.get("balance").getAsBigDecimal().signum());
        assertEquals(0, row.get("accumulatedInflows").getAsBigDecimal().signum());
        assertEquals(0, row.get("accumulatedOutflows").getAsBigDecimal().signum());
      }
    }
    var history =
        given(auth())
            .queryParam("tellerId", cashier)
            .queryParam("operation", "CLOSE_CASHIER")
            .get(SavingsTestUtils.CONTEXT_PATH + "/api/v2/base-teller/transaction-history");
    assertEquals(200, history.statusCode(), history.asString());
    assertEquals(currencies.size(), history.jsonPath().getInt("totalFilteredRecords"));
    for (var row :
        JsonParser.parseString(history.asString()).getAsJsonObject().getAsJsonArray("items")) {
      var item = row.getAsJsonObject();
      assertTrue(item.get("historyId").getAsString().startsWith("CASHIER_CLOSING:"));
      var settled =
          querySingleValueInDefaultTenant(
              "SELECT settled_cash_total FROM m_cashier_reconciliation WHERE end_of_day_closing_id="
                  + id
                  + " AND currency_code="
                  + sqlLiteral(item.get("currencyCode").getAsString()));
      assertEquals(
          0, new java.math.BigDecimal(settled).compareTo(item.get("outflow").getAsBigDecimal()));
    }
  }

  @Test
  void arbitraryValidApplicationUserCannotAuthorizeAndOverageCanBePreviewed() {
    var body = declaration(head, 1100, UUID.randomUUID().toString());
    var preview = given(auth()).body(body.toString()).post(PATH + "/preview");
    assertEquals(200, preview.statusCode(), preview.asString());
    authorization(body, "mifos", "password");
    var denied = given(auth()).body(body.toString()).post(PATH);
    assertEquals(403, denied.statusCode(), denied.asString());
    assertEquals(
        "0",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_end_of_day_closing WHERE cashier_id=" + head));
  }
}
