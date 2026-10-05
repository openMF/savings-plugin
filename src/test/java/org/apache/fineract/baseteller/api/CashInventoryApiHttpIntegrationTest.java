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
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.restassured.http.ContentType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.fineract.testing.support.SavingsIntegrationTestBase;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CashInventoryApiHttpIntegrationTest extends SavingsIntegrationTestBase {

  private static final String PATH =
      SavingsTestUtils.CONTEXT_PATH + "/api/v2/base-teller/cash-inventory";
  private static String currency;
  private static String cashierId;

  @BeforeAll
  static void seedInventory() {
    currency =
        querySingleValueInDefaultTenant(
            "SELECT code FROM m_organisation_currency ORDER BY code LIMIT 1");
    executeSqlInDefaultTenant(
        """
        INSERT INTO m_staff (is_loan_officer,office_id,firstname,lastname,display_name,is_active)
        SELECT false,1,'WEB1255','Teller','WEB-1255 Inventory Teller',true
        WHERE NOT EXISTS (SELECT 1 FROM m_staff WHERE display_name='WEB-1255 Inventory Teller');

        INSERT INTO m_tellers (office_id,name,description,state)
        SELECT 1,'WEB-1255 Inventory Window','Cash inventory integration window',300
        WHERE NOT EXISTS (SELECT 1 FROM m_tellers WHERE name='WEB-1255 Inventory Window');

        INSERT INTO m_cashiers (staff_id,teller_id,description,full_day)
        SELECT s.id,t.id,'WEB-1255 inventory assignment',true
        FROM m_staff s,m_tellers t
        WHERE s.display_name='WEB-1255 Inventory Teller'
          AND t.name='WEB-1255 Inventory Window'
          AND NOT EXISTS (
            SELECT 1 FROM m_cashiers c WHERE c.staff_id=s.id AND c.teller_id=t.id
          );
        """);
    cashierId =
        querySingleValueInDefaultTenant(
            "SELECT c.id FROM m_cashiers c JOIN m_staff s ON s.id=c.staff_id"
                + " WHERE s.display_name='WEB-1255 Inventory Teller'");
    final String businessDate =
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
        DELETE FROM m_cashier_transactions
        WHERE entity_type='BASE_TELLER_CASH_INVENTORY_TEST';

        INSERT INTO m_cashier_transactions
          (cashier_id,txn_type,txn_date,txn_amount,created_date,txn_note,
           entity_type,entity_id,currency_code)
        VALUES
          (%s,101,%s,50000,CURRENT_TIMESTAMP,'WEB-1255 opening',
           'BASE_TELLER_CASH_INVENTORY_TEST',1,%s),
          (%s,103,%s,20000,CURRENT_TIMESTAMP,'WEB-1255 inflow',
           'BASE_TELLER_CASH_INVENTORY_TEST',2,%s),
          (%s,104,%s,15000,CURRENT_TIMESTAMP,'WEB-1255 outflow',
           'BASE_TELLER_CASH_INVENTORY_TEST',3,%s),
          (%s,102,%s,10000,CURRENT_TIMESTAMP,'WEB-1255 cutoff',
           'BASE_TELLER_CASH_INVENTORY_TEST',4,%s);
        """,
        cashierId,
        businessDate,
        currency,
        cashierId,
        businessDate,
        currency,
        cashierId,
        businessDate,
        currency,
        cashierId,
        businessDate,
        currency);
  }

  @Test
  void pluginRuntimeExposesContextFiltersAndReconciledInventory() {
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .when()
        .get(PATH + "/context")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("transactionTypes.code", hasItem("CASH"))
        .body("transactionTypes.code", hasItem("CHECK"))
        .body("currencies.code", hasItem(currency))
        .body("custodians.key", hasItem("TELLER:" + cashierId));

    final List<Map<String, Object>> rows =
        given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .queryParam("custodianKey", "TELLER:" + cashierId)
            .queryParam("transactionType", "CASH")
            .queryParam("currencyCode", currency)
            .queryParam("showLastCutOff", true)
            .when()
            .get(PATH)
            .then()
            .statusCode(200)
            .body("[0].custodianKey", equalTo("TELLER:" + cashierId))
            .body("[0].inventoryType", equalTo("CASH"))
            .body("[0].lastCutOffAt", notNullValue())
            .body("[0].asOf", notNullValue())
            .extract()
            .jsonPath()
            .getList("$");

    assertEquals(1, rows.size());
    final Map<String, Object> row = rows.get(0);
    assertAmount("50000.00", row.get("initialBalance"));
    assertAmount("20000.00", row.get("accumulatedInflows"));
    assertAmount("15000.00", row.get("accumulatedOutflows"));
    assertAmount("10000.00", row.get("cutOffs"));
    assertAmount("45000.00", row.get("balance"));

    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .queryParam("transactionType", "CHECK")
        .queryParam("currencyCode", currency)
        .when()
        .get(PATH)
        .then()
        .statusCode(200);
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .when()
        .get(PATH)
        .then()
        .statusCode(200);
  }

  private static void assertAmount(final String expected, final Object actual) {
    assertEquals(0, new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected)));
  }
}
