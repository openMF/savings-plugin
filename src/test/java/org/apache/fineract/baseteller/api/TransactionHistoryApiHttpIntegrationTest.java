/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.math.BigDecimal;
import org.apache.fineract.testing.support.SavingsIntegrationTestBase;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TransactionHistoryApiHttpIntegrationTest extends SavingsIntegrationTestBase {

  private static final String PATH =
      SavingsTestUtils.CONTEXT_PATH + "/api/v2/base-teller/transaction-history";
  private static final long RESTRICTED_OFFICE_ID = 91256L;
  private static final String RESTRICTED_USERNAME = "web1256-restricted";
  private static String currency;
  private static String businessDate;
  private static String cashierId;
  private static String servicePaymentId;
  private static String allocationId;
  private static String nativeTransactionId;

  @BeforeAll
  static void seedHistory() {
    currency =
        querySingleValueInDefaultTenant(
            "SELECT code FROM m_organisation_currency ORDER BY code LIMIT 1");
    businessDate =
        given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .queryParam("officeId", 1)
            .queryParam("currencyCode", currency)
            .when()
            .get(SavingsTestUtils.CONTEXT_PATH + "/api/v2/base-teller/cash-allocations/context")
            .then()
            .statusCode(200)
            .extract()
            .path("businessDate");
    executeSqlInDefaultTenant(
        """
        INSERT INTO m_office (id,parent_id,hierarchy,name,opening_date)
        VALUES (%s,1,%s,'WEB-1256 Restricted Office',DATE '2026-01-01')
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO m_role (name,description,is_disabled)
        VALUES ('WEB-1256 history restricted','Transaction history restricted-office role',false)
        ON CONFLICT (name) DO NOTHING;

        INSERT INTO m_appuser (
          office_id,username,firstname,lastname,password,email,firsttime_login_remaining,
          nonexpired,nonlocked,nonexpired_credentials,enabled,last_time_password_updated,
          password_never_expires
        ) SELECT %s,%s,'WEB1256','Restricted',password,'web1256-restricted@example.test',false,
                 true,true,true,true,CURRENT_DATE,true
          FROM m_appuser WHERE username='mifos'
        ON CONFLICT (username) DO UPDATE SET office_id=EXCLUDED.office_id;

        INSERT INTO m_role_permission (role_id,permission_id)
        SELECT r.id,p.id FROM m_role r,m_permission p
        WHERE r.name='WEB-1256 history restricted'
          AND p.code='READ_BASE_TELLER_TRANSACTION_HISTORY'
        ON CONFLICT DO NOTHING;

        INSERT INTO m_appuser_role (appuser_id,role_id)
        SELECT u.id,r.id FROM m_appuser u,m_role r
        WHERE u.username=%s AND r.name='WEB-1256 history restricted'
        ON CONFLICT DO NOTHING;

        INSERT INTO m_staff (is_loan_officer,office_id,firstname,lastname,display_name,is_active)
        SELECT false,1,'WEB1256','Teller','WEB-1256 History Teller',true
        WHERE NOT EXISTS (SELECT 1 FROM m_staff WHERE display_name='WEB-1256 History Teller');

        INSERT INTO m_tellers (office_id,name,description,state)
        SELECT 1,'WEB-1256 History Window','Transaction history integration window',300
        WHERE NOT EXISTS (SELECT 1 FROM m_tellers WHERE name='WEB-1256 History Window');

        INSERT INTO m_cashiers (staff_id,teller_id,description,full_day)
        SELECT s.id,t.id,'WEB-1256 history assignment',true FROM m_staff s,m_tellers t
        WHERE s.display_name='WEB-1256 History Teller' AND t.name='WEB-1256 History Window'
          AND NOT EXISTS (SELECT 1 FROM m_cashiers c WHERE c.staff_id=s.id AND c.teller_id=t.id);

        INSERT INTO m_service_payment_service
          (code,name,active,currency_code,commission_type,commission_value,commission_vat_rate)
        SELECT 'WEB1256-POWER','WEB-1256 Power',true,%s,'FIXED',0,0
        WHERE NOT EXISTS (SELECT 1 FROM m_service_payment_service WHERE code='WEB1256-POWER');

        INSERT INTO m_service_payment
          (idempotency_key,request_fingerprint,receipt_number,business_date,service_id,
          service_code,service_name,service_reference,payer_type,payer_name,currency_code,
           base_amount,commission_amount,commission_vat_amount,total_to_pay,amount_received,
           change_amount,payment_type_id,office_id,teller_id,cashier_id,operator_id,status,
           completed_on_utc)
        SELECT 'web1256-service','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
          'WEB1256-SP-001',%s,sp.id,sp.code,sp.name,'UTILITY-7788','NON_CLIENT','History Payer',%s,
          90,0,0,90,100,10,(SELECT id FROM m_payment_type ORDER BY id LIMIT 1),1,t.id,c.id,
          (SELECT id FROM m_appuser WHERE username='mifos'),'COMPLETED',CURRENT_TIMESTAMP
        FROM m_service_payment_service sp,m_tellers t,m_cashiers c,m_staff s
        WHERE sp.code='WEB1256-POWER' AND t.name='WEB-1256 History Window'
          AND c.teller_id=t.id AND s.id=c.staff_id AND s.display_name='WEB-1256 History Teller'
          AND NOT EXISTS (SELECT 1 FROM m_service_payment WHERE idempotency_key='web1256-service');

        INSERT INTO m_service_payment_cash_detail
          (service_payment_id,denomination_identifier,denomination_value,quantity,line_total)
        SELECT p.id,'web1256-note-50',50,2,100 FROM m_service_payment p
        WHERE p.idempotency_key='web1256-service'
          AND NOT EXISTS (SELECT 1 FROM m_service_payment_cash_detail d WHERE d.service_payment_id=p.id);

        INSERT INTO m_cashier_transactions
          (cashier_id,txn_type,txn_date,txn_amount,created_date,txn_note,entity_type,entity_id,currency_code)
        SELECT p.cashier_id,103,p.business_date,p.total_to_pay,CURRENT_TIMESTAMP,
          'WEB-1256 linked service payment','BASE_TELLER_SERVICE_PAYMENT',p.id,p.currency_code
        FROM m_service_payment p WHERE p.idempotency_key='web1256-service'
          AND p.cashier_transaction_id IS NULL;

        UPDATE m_service_payment p SET cashier_transaction_id=ct.id
        FROM m_cashier_transactions ct
        WHERE p.idempotency_key='web1256-service'
          AND ct.entity_type='BASE_TELLER_SERVICE_PAYMENT' AND ct.entity_id=p.id;

        INSERT INTO m_cashier_transactions
          (cashier_id,txn_type,txn_date,txn_amount,created_date,txn_note,currency_code)
        SELECT c.id,103,%s,30,CURRENT_TIMESTAMP,'WEB-1256 independent cash in',%s
        FROM m_cashiers c JOIN m_staff s ON s.id=c.staff_id
        WHERE s.display_name='WEB-1256 History Teller'
          AND NOT EXISTS (SELECT 1 FROM m_cashier_transactions
                          WHERE txn_note='WEB-1256 independent cash in');

        INSERT INTO m_cash_allocation
          (idempotency_key,request_fingerprint,receipt_number,operation_type,status,business_date,
           office_id,office_name,initiated_by,initiated_by_username,source_name,
           destination_cashier_id,destination_name,destination_key,currency_code,amount,
           destination_balance_before,destination_balance_after,completed_on_utc)
        SELECT 'web1256-allocation','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
          'WEB1256-CA-001','SAFE_VAULT_OPENING','COMPLETED',%s,1,'Head Office',u.id,u.username,
          'Safe/Vault',c.id,s.display_name,CONCAT('CASHIER:',c.id),%s,200,0,200,CURRENT_TIMESTAMP
        FROM m_appuser u,m_cashiers c,m_staff s,m_tellers t
        WHERE u.username='mifos' AND c.staff_id=s.id AND c.teller_id=t.id
          AND s.display_name='WEB-1256 History Teller' AND t.name='WEB-1256 History Window'
          AND NOT EXISTS (SELECT 1 FROM m_cash_allocation WHERE idempotency_key='web1256-allocation');

        INSERT INTO m_cash_allocation_denomination
          (allocation_id,denomination_identifier,denomination_type,denomination_value,quantity,line_total)
        SELECT a.id,'web1256-note-100','BANKNOTE',100,2,200 FROM m_cash_allocation a
        WHERE a.idempotency_key='web1256-allocation'
          AND NOT EXISTS (SELECT 1 FROM m_cash_allocation_denomination d WHERE d.allocation_id=a.id);
        """,
        RESTRICTED_OFFICE_ID,
        ".1." + RESTRICTED_OFFICE_ID + ".",
        RESTRICTED_OFFICE_ID,
        RESTRICTED_USERNAME,
        RESTRICTED_USERNAME,
        currency,
        businessDate,
        currency,
        businessDate,
        currency,
        businessDate,
        currency);
    cashierId =
        querySingleValueInDefaultTenant(
            "SELECT c.id FROM m_cashiers c JOIN m_staff s ON s.id=c.staff_id"
                + " WHERE s.display_name='WEB-1256 History Teller'");
    servicePaymentId =
        querySingleValueInDefaultTenant(
            "SELECT id FROM m_service_payment WHERE idempotency_key='web1256-service'");
    allocationId =
        querySingleValueInDefaultTenant(
            "SELECT id FROM m_cash_allocation WHERE idempotency_key='web1256-allocation'");
    nativeTransactionId =
        querySingleValueInDefaultTenant(
            "SELECT id FROM m_cashier_transactions"
                + " WHERE txn_note='WEB-1256 independent cash in'");
  }

  @AfterAll
  static void removeHistoryFixture() {
    executeSqlInDefaultTenant(
        """
        DELETE FROM m_cash_allocation_denomination d USING m_cash_allocation a
        WHERE d.allocation_id=a.id AND a.idempotency_key='web1256-allocation';
        DELETE FROM m_cash_allocation WHERE idempotency_key='web1256-allocation';
        DELETE FROM m_cashier_transactions WHERE txn_note='WEB-1256 independent cash in';
        UPDATE m_service_payment SET cashier_transaction_id=NULL
        WHERE idempotency_key='web1256-service';
        DELETE FROM m_cashier_transactions ct USING m_service_payment p
        WHERE p.idempotency_key='web1256-service'
          AND ct.entity_type='BASE_TELLER_SERVICE_PAYMENT' AND ct.entity_id=p.id;
        DELETE FROM m_service_payment_cash_detail d USING m_service_payment p
        WHERE d.service_payment_id=p.id AND p.idempotency_key='web1256-service';
        DELETE FROM m_service_payment WHERE idempotency_key='web1256-service';
        DELETE FROM m_service_payment_service WHERE code='WEB1256-POWER';
        DELETE FROM m_cashiers c USING m_staff s
        WHERE c.staff_id=s.id AND s.display_name='WEB-1256 History Teller';
        DELETE FROM m_tellers WHERE name='WEB-1256 History Window';
        DELETE FROM m_staff WHERE display_name='WEB-1256 History Teller';
        DELETE FROM m_appuser_role ur USING m_appuser u,m_role r
        WHERE ur.appuser_id=u.id AND ur.role_id=r.id
          AND u.username='web1256-restricted' AND r.name='WEB-1256 history restricted';
        DELETE FROM m_appuser WHERE username='web1256-restricted';
        DELETE FROM m_role_permission rp USING m_role r
        WHERE rp.role_id=r.id AND r.name='WEB-1256 history restricted';
        DELETE FROM m_role WHERE name='WEB-1256 history restricted';
        DELETE FROM m_office WHERE id=91256;
        """);
  }

  @Test
  void contextSearchPaginationTotalsAndFiltersUseAuthoritativeRows() {
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .when()
        .get(PATH + "/context")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("tellers.id", hasItem(Integer.valueOf(cashierId)))
        .body("currencies.code", hasItem(currency))
        .body("operations.code", hasItem("PAY_SERVICE"))
        .body("concepts.code", hasItem("WEB-1256 Power"));

    final Response response =
        given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .queryParam("tellerId", cashierId)
            .queryParam("currencyCode", currency)
            .queryParam("fromDate", businessDate)
            .queryParam("toDate", businessDate)
            .queryParam("limit", 1)
            .when()
            .get(PATH)
            .then()
            .statusCode(200)
            .body("items", hasSize(1))
            .body("totalFilteredRecords", equalTo(3))
            .body("totalsByCurrency", hasSize(1))
            .extract()
            .response();
    assertAmount("320", response.path("totalsByCurrency[0].totalInflows"));
    assertAmount("0", response.path("totalsByCurrency[0].totalOutflows"));
    assertAmount("320", response.path("totalsByCurrency[0].total"));

    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .queryParam("reference", "UTILITY-7788")
        .queryParam("operation", "PAY_SERVICE")
        .queryParam("type", "CASH")
        .queryParam("concept", "WEB-1256 Power")
        .queryParam("status", "COMPLETED")
        .when()
        .get(PATH)
        .then()
        .statusCode(200)
        .body("totalFilteredRecords", equalTo(1))
        .body("items[0].historyId", equalTo("SERVICE_PAYMENT:" + servicePaymentId))
        .body("items[0].reference", equalTo("WEB1256-SP-001"));
  }

  @Test
  void linkedNativeCashierRowIsNotDoubleCounted() {
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .queryParam("reference", "WEB-1256 linked service payment")
        .when()
        .get(PATH)
        .then()
        .statusCode(200)
        .body("totalFilteredRecords", equalTo(0));
  }

  @Test
  void unrelatedOfficeCannotSeeRowsCountsTotalsOrTellers() {
    given(
            SavingsTestUtils.requestSpecWithAuth(
                getFineractPort(), RESTRICTED_USERNAME, "password"))
        .when()
        .get(PATH)
        .then()
        .statusCode(200)
        .body("items", hasSize(0))
        .body("totalFilteredRecords", equalTo(0))
        .body("totalsByCurrency", hasSize(0));

    given(
            SavingsTestUtils.requestSpecWithAuth(
                getFineractPort(), RESTRICTED_USERNAME, "password"))
        .when()
        .get(PATH + "/context")
        .then()
        .statusCode(200)
        .body("tellers", hasSize(0))
        .body("statuses", hasSize(0))
        .body("operations", hasSize(0));
  }

  @Test
  void detailDenominationsReceiptAndReportResolveCanonicalSource() {
    final String serviceHistoryId = "SERVICE_PAYMENT:" + servicePaymentId;
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .when()
        .get(PATH + "/" + serviceHistoryId)
        .then()
        .statusCode(200)
        .body("historyId", equalTo(serviceHistoryId))
        .body("cashReceived", equalTo(100.0F))
        .body("change", equalTo(10.0F))
        .body("total", equalTo(90.0F))
        .body("receiptSupported", equalTo(true));

    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .when()
        .get(PATH + "/" + serviceHistoryId + "/denominations")
        .then()
        .statusCode(200)
        .body("operationDenominationsSupported", equalTo(true))
        .body("operationDenominations", hasSize(1))
        .body("operationDenominations[0].quantity", equalTo(2))
        .body("changeDenominationsSupported", equalTo(false))
        .body("changeDenominations", hasSize(0));

    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .when()
        .get(PATH + "/" + serviceHistoryId + "/receipt")
        .then()
        .statusCode(200)
        .body("historyId", equalTo(serviceHistoryId))
        .body("receipt.receiptNumber", equalTo("WEB1256-SP-001"));

    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .queryParam("tellerId", cashierId)
        .queryParam("currencyCode", currency)
        .queryParam("fromDate", businessDate)
        .queryParam("toDate", businessDate)
        .when()
        .get(PATH + "/report")
        .then()
        .statusCode(200)
        .body("items", hasSize(3))
        .body("totalFilteredRecords", equalTo(3))
        .body("totalsByCurrency[0].total", notNullValue());

    final String allocationHistoryId = "CASH_ALLOCATION:" + allocationId;
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .when()
        .get(PATH + "/" + allocationHistoryId + "/denominations")
        .then()
        .statusCode(200)
        .body("operationDenominations[0].denominationType", equalTo("BANKNOTE"));

    final String nativeHistoryId = "CASHIER_TRANSACTION:" + nativeTransactionId;
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .when()
        .get(PATH + "/" + nativeHistoryId)
        .then()
        .statusCode(200)
        .body("historyId", equalTo(nativeHistoryId))
        .body("client", equalTo(null))
        .body("total", equalTo(30.0F))
        .body("receiptSupported", equalTo(false));

    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .when()
        .get(PATH + "/" + nativeHistoryId + "/denominations")
        .then()
        .statusCode(200)
        .body("operationDenominationsSupported", equalTo(false))
        .body("operationDenominations", hasSize(0));
  }

  private static void assertAmount(final String expected, final Object actual) {
    assertEquals(0, new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected)));
  }
}
