/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.http.ContentType;
import org.apache.fineract.testing.support.SavingsIntegrationTestBase;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CashAllocationsApiHttpIntegrationTest extends SavingsIntegrationTestBase {

  private static final String PATH =
      SavingsTestUtils.CONTEXT_PATH + "/api/v2/base-teller/cash-allocations";
  private static final String AUTHORIZED_USERNAME = "web1221-read-allocation";
  private static final String RESTRICTED_USERNAME = "web1221-no-allocation";
  private static String currency;

  @BeforeAll
  static void seedDenominationAndRestrictedUser() {
    currency =
        querySingleValueInDefaultTenant(
            "SELECT code FROM m_organisation_currency ORDER BY code LIMIT 1");
    executeSqlInDefaultTenant(
        """
        INSERT INTO m_service_payment_denomination
          (identifier,currency_code,value,denomination_type,active)
        VALUES ('web1221-unit',%s,1,'COIN',true)
        ON CONFLICT (currency_code,identifier) DO UPDATE SET active=true;

        INSERT INTO m_role (name,description,is_disabled)
        VALUES ('WEB-1221 allocation reader','Cash allocation read role',false),
               ('WEB-1221 no allocation','Cash allocation permission denial role',false)
        ON CONFLICT (name) DO NOTHING;

        INSERT INTO m_appuser (
          office_id,username,firstname,lastname,password,email,firsttime_login_remaining,
          nonexpired,nonlocked,nonexpired_credentials,enabled,last_time_password_updated,
          password_never_expires
        ) SELECT 1,%s,'WEB','Reader',password,'web1221-reader@example.test',false,
                 true,true,true,true,CURRENT_DATE,true
          FROM m_appuser WHERE username='mifos'
        ON CONFLICT (username) DO UPDATE SET office_id=EXCLUDED.office_id;

        INSERT INTO m_appuser (
          office_id,username,firstname,lastname,password,email,firsttime_login_remaining,
          nonexpired,nonlocked,nonexpired_credentials,enabled,last_time_password_updated,
          password_never_expires
        ) SELECT 1,%s,'WEB','Restricted',password,'web1221-restricted@example.test',false,
                 true,true,true,true,CURRENT_DATE,true
          FROM m_appuser WHERE username='mifos'
        ON CONFLICT (username) DO UPDATE SET office_id=EXCLUDED.office_id;

        INSERT INTO m_role_permission (role_id,permission_id)
        SELECT r.id,p.id FROM m_role r,m_permission p
        WHERE r.name='WEB-1221 allocation reader'
          AND p.code IN ('READ_BASE_TELLER_CASH_ALLOCATION',
                         'CREATE_BASE_TELLER_CASH_ALLOCATION',
                         'REPRINT_BASE_TELLER_CASH_ALLOCATION',
                         'CREATE_JOURNALENTRY',
                         'ALLOCATECASHTOCASHIER_TELLER',
                         'SETTLECASHFROMCASHIER_TELLER')
        ON CONFLICT DO NOTHING;

        INSERT INTO m_appuser_role (appuser_id,role_id)
        SELECT u.id,r.id FROM m_appuser u,m_role r
        WHERE u.username=%s AND r.name='WEB-1221 allocation reader'
        ON CONFLICT DO NOTHING;

        INSERT INTO m_appuser_role (appuser_id,role_id)
        SELECT u.id,r.id FROM m_appuser u,m_role r
        WHERE u.username=%s AND r.name='WEB-1221 no allocation'
        ON CONFLICT DO NOTHING;

        INSERT INTO acc_gl_account
          (name,gl_code,disabled,manual_journal_entries_allowed,account_usage,classification_enum)
        VALUES ('WEB-1221 Main Vault','WEB1221-VAULT',false,true,2,1),
               ('WEB-1221 Teller Cash','WEB1221-TELLER',false,true,2,1),
               ('WEB-1221 Opening Contra','WEB1221-CONTRA',false,true,2,4)
        ON CONFLICT (gl_code) DO NOTHING;

        INSERT INTO acc_gl_financial_activity_account (gl_account_id,financial_activity_type)
        SELECT id,101 FROM acc_gl_account WHERE gl_code='WEB1221-VAULT'
        ON CONFLICT (financial_activity_type) DO UPDATE SET gl_account_id=EXCLUDED.gl_account_id;

        INSERT INTO acc_gl_financial_activity_account (gl_account_id,financial_activity_type)
        SELECT id,102 FROM acc_gl_account WHERE gl_code='WEB1221-TELLER'
        ON CONFLICT (financial_activity_type) DO UPDATE SET gl_account_id=EXCLUDED.gl_account_id;

        INSERT INTO acc_gl_financial_activity_account (gl_account_id,financial_activity_type)
        SELECT id,300 FROM acc_gl_account WHERE gl_code='WEB1221-CONTRA'
        ON CONFLICT (financial_activity_type) DO UPDATE SET gl_account_id=EXCLUDED.gl_account_id;

        INSERT INTO m_staff (is_loan_officer,office_id,firstname,lastname,display_name,is_active)
        VALUES (false,1,'WEB1221','Head','WEB-1221 Head Cashier',true),
               (false,1,'WEB1221','Teller','WEB-1221 Operational Teller',true);

        INSERT INTO m_tellers (office_id,name,description,state)
        VALUES (1,'WEB-1221 Head Window','Head cashier window',300),
               (1,'WEB-1221 Teller Window','Operational teller window',300);

        INSERT INTO m_cashiers (staff_id,teller_id,description,full_day)
        SELECT s.id,t.id,'WEB-1221 assignment',true
        FROM m_staff s,m_tellers t
        WHERE ((s.display_name='WEB-1221 Head Cashier' AND t.name='WEB-1221 Head Window')
           OR (s.display_name='WEB-1221 Operational Teller' AND t.name='WEB-1221 Teller Window'))
        AND NOT EXISTS (
          SELECT 1 FROM m_cashiers c WHERE c.staff_id=s.id AND c.teller_id=t.id
        );
        """,
        currency,
        AUTHORIZED_USERNAME,
        RESTRICTED_USERNAME,
        AUTHORIZED_USERNAME,
        RESTRICTED_USERNAME);
    assertEquals(
        "1",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_appuser u"
                + " JOIN m_appuser_role ur ON ur.appuser_id=u.id"
                + " JOIN m_role_permission rp ON rp.role_id=ur.role_id"
                + " JOIN m_permission p ON p.id=rp.permission_id"
                + " WHERE u.username='web1221-read-allocation'"
                + " AND p.code='READ_BASE_TELLER_CASH_ALLOCATION'"));
  }

  @Test
  void pluginLoadsMigrationAndExposesAuthoritativeContextOverHttp() {
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), AUTHORIZED_USERNAME, "password"))
        .queryParam("officeId", 1)
        .when()
        .get(PATH + "/context")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("businessDate", notNullValue())
        .body("officeId", equalTo(1))
        .body("currencies[0].code", equalTo(currency))
        .body("currencies[0].denominations.identifier", hasItem("web1221-unit"));
  }

  @Test
  void userWithoutCashAllocationReadPermissionIsForbidden() {
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), RESTRICTED_USERNAME, "password"))
        .queryParam("officeId", 1)
        .queryParam("currencyCode", currency)
        .when()
        .get(PATH + "/context")
        .then()
        .statusCode(403);
  }

  @Test
  void safeOpeningPostsAccountingOnceAndReturnsImmutableReceipt() {
    final String businessDate =
        given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), AUTHORIZED_USERNAME, "password"))
            .queryParam("officeId", 1)
            .queryParam("currencyCode", currency)
            .when()
            .get(PATH + "/context")
            .then()
            .statusCode(200)
            .extract()
            .path("businessDate");
    final String body =
        """
        {
          "idempotencyKey":"web1221-safe-opening",
          "operationType":"SAFE_VAULT_OPENING",
          "officeId":1,
          "businessDate":"%s",
          "currencyCode":"%s",
          "amount":20,
          "denominations":[{"denominationId":"web1221-unit","quantity":20}]
        }
        """
            .formatted(businessDate, currency);

    final Number allocationId =
        given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), AUTHORIZED_USERNAME, "password"))
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post(PATH)
            .then()
            .statusCode(200)
            .body("operationType", equalTo("SAFE_VAULT_OPENING"))
            .body("status", equalTo("COMPLETED"))
            .body("totalAmount", equalTo(20.0F))
            .body("accountingTransactionId", notNullValue())
            .body("denominations[0].subtotal", equalTo(20.0F))
            .extract()
            .path("id");

    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), AUTHORIZED_USERNAME, "password"))
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post(PATH)
        .then()
        .statusCode(200)
        .body("id", equalTo(allocationId.intValue()));

    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), AUTHORIZED_USERNAME, "password"))
        .when()
        .get(PATH + "/" + allocationId + "/receipt")
        .then()
        .statusCode(200)
        .body("id", equalTo(allocationId.intValue()))
        .body("completedOn", notNullValue());

    assertEquals(
        "1",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(DISTINCT accounting_transaction_id) FROM m_cash_allocation"
                + " WHERE idempotency_key='web1221-safe-opening'"));
    assertEquals(
        "2",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM acc_gl_journal_entry j"
                + " JOIN m_cash_allocation a ON a.accounting_transaction_id=j.transaction_id"
                + " WHERE a.idempotency_key='web1221-safe-opening'"));

    final String headCashierId =
        querySingleValueInDefaultTenant(
            "SELECT c.id FROM m_cashiers c JOIN m_staff s ON s.id=c.staff_id"
                + " WHERE s.display_name='WEB-1221 Head Cashier'");
    final String operationalCashierId =
        querySingleValueInDefaultTenant(
            "SELECT c.id FROM m_cashiers c JOIN m_staff s ON s.id=c.staff_id"
                + " WHERE s.display_name='WEB-1221 Operational Teller'");
    final String headBody =
        allocationBody(
            "web1221-head-opening",
            "HEAD_CASHIER_ALLOCATION",
            null,
            headCashierId,
            businessDate,
            "12",
            12);
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), AUTHORIZED_USERNAME, "password"))
        .contentType(ContentType.JSON)
        .body(headBody)
        .when()
        .post(PATH)
        .then()
        .statusCode(200)
        .body("sourceBalanceBefore", equalTo(20.0F))
        .body("sourceBalanceAfter", equalTo(8.0F))
        .body("destinationBalanceAfter", equalTo(12.0F))
        .body("destinationCashierTransactionId", notNullValue());

    final String tellerBody =
        allocationBody(
            "web1221-teller-opening",
            "OPERATIONAL_TELLER_ALLOCATION",
            headCashierId,
            operationalCashierId,
            businessDate,
            "5",
            5);
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), AUTHORIZED_USERNAME, "password"))
        .contentType(ContentType.JSON)
        .body(tellerBody)
        .when()
        .post(PATH)
        .then()
        .statusCode(200)
        .body("sourceBalanceBefore", equalTo(12.0F))
        .body("sourceBalanceAfter", equalTo(7.0F))
        .body("destinationBalanceAfter", equalTo(5.0F))
        .body("sourceCashierTransactionId", notNullValue())
        .body("destinationCashierTransactionId", notNullValue());

    assertEquals(
        "7.000000",
        querySingleValueInDefaultTenant(
            "SELECT (SUM(CASE WHEN txn_type IN (101,103) THEN txn_amount"
                + " WHEN txn_type IN (102,104) THEN -txn_amount ELSE 0 END))::decimal(19,6)"
                + " FROM m_cashier_transactions WHERE cashier_id="
                + headCashierId));
    assertEquals(
        "5.000000",
        querySingleValueInDefaultTenant(
            "SELECT (SUM(CASE WHEN txn_type IN (101,103) THEN txn_amount"
                + " WHEN txn_type IN (102,104) THEN -txn_amount ELSE 0 END))::decimal(19,6)"
                + " FROM m_cashier_transactions WHERE cashier_id="
                + operationalCashierId));
  }

  private static String allocationBody(
      final String key,
      final String operation,
      final String sourceCashierId,
      final String destinationCashierId,
      final String businessDate,
      final String amount,
      final long quantity) {
    final String source =
        sourceCashierId == null ? "" : ",\"sourceCashierId\":" + sourceCashierId;
    return "{\"idempotencyKey\":\""
        + key
        + "\",\"operationType\":\""
        + operation
        + "\",\"officeId\":1"
        + source
        + ",\"destinationCashierId\":"
        + destinationCashierId
        + ",\"businessDate\":\""
        + businessDate
        + "\",\"currencyCode\":\""
        + currency
        + "\",\"amount\":"
        + amount
        + ",\"denominations\":[{\"denominationId\":\"web1221-unit\",\"quantity\":"
        + quantity
        + "}]}";
  }
}
