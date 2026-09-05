/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.http.ContentType;
import org.apache.fineract.testing.support.SavingsIntegrationTestBase;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CreditApplicationApiHttpIntegrationTest extends SavingsIntegrationTestBase {

  private static final String CREDIT_APPLICATIONS_PATH =
      SavingsTestUtils.CONTEXT_PATH + "/api/v2/credit-applications";
  private static final long CLIENT_ID = 9_106_100L;
  private static final long PRODUCT_ID = 9_106_101L;
  private static final long LOAN_ID = 9_106_102L;
  private static final String UNAUTHORIZED_USERNAME = "web1061-no-loan";

  @BeforeAll
  static void seedCreditApplicationAndRestrictedUser() {
    executeSqlInPostgres(
        """
        INSERT INTO m_product_loan (
          id, name, short_name, currency_code, currency_digits, principal_amount,
          interest_method_enum, repay_every, repayment_period_frequency_enum,
          number_of_repayments, amortization_method_enum, accounting_type
        ) VALUES (
          %s, 'WEB-1061 HTTP Loan', 'W61H', 'USD', 2, 1500.00,
          0, 1, 2, 12, 1, 1
        );

        INSERT INTO m_client (
          id, account_no, status_enum, office_id, firstname, lastname, display_name,
          submittedon_date, legal_form_enum, created_on_utc, created_by,
          last_modified_on_utc, last_modified_by
        ) VALUES (
          %s, '9106100', 300, 1, 'WEB', 'Applicant', 'WEB Applicant',
          DATE '2026-09-01', 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 1
        );

        INSERT INTO m_loan (
          id, account_no, client_id, product_id, loan_status_id, loan_type_enum,
          currency_code, currency_digits, principal_amount_proposed, principal_amount,
          approved_principal, net_disbursal_amount, interest_method_enum, term_frequency,
          term_period_frequency_enum, repay_every, repayment_period_frequency_enum,
          number_of_repayments, amortization_method_enum, submittedon_date, created_on_utc,
          created_by, last_modified_on_utc, last_modified_by
        ) VALUES (
          %s, '9106102', %s, %s, 100, 1,
          'USD', 2, 1500.00, 1500.00,
          1500.00, 1500.00, 0, 12,
          2, 1, 2,
          12, 1, DATE '2026-09-01', CURRENT_TIMESTAMP,
          1, CURRENT_TIMESTAMP, 1
        );

        WITH state_value AS (
          SELECT cv.id
          FROM m_code_value cv
          JOIN m_code c ON c.id = cv.code_id
          WHERE c.code_name = 'STATE'
          ORDER BY cv.id
          LIMIT 1
        ), address_type AS (
          SELECT cv.id
          FROM m_code_value cv
          JOIN m_code c ON c.id = cv.code_id
          WHERE c.code_name = 'ADDRESS_TYPE'
          ORDER BY cv.id
          LIMIT 1
        ), new_address AS (
          INSERT INTO m_address (county_district, state_province_id)
          SELECT 'WEB Municipality', state_value.id FROM state_value
          RETURNING id
        )
        INSERT INTO m_client_address (client_id, address_id, address_type_id, is_active)
        SELECT %s, new_address.id, address_type.id, true
        FROM new_address CROSS JOIN address_type;

        WITH new_role AS (
          INSERT INTO m_role (name, description, is_disabled)
          VALUES ('WEB-1061 no loan access', 'HTTP permission test role', false)
          RETURNING id
        ), new_user AS (
          INSERT INTO m_appuser (
            office_id, username, firstname, lastname, password, email,
            firsttime_login_remaining, nonexpired, nonlocked, nonexpired_credentials,
            enabled, last_time_password_updated, password_never_expires
          )
          SELECT 1, %s, 'WEB', 'Restricted', password, 'web1061-restricted@example.test',
                 false, true, true, true,
                 true, CURRENT_DATE, true
          FROM m_appuser
          WHERE username = 'mifos'
          RETURNING id
        )
        INSERT INTO m_appuser_role (appuser_id, role_id)
        SELECT new_user.id, new_role.id FROM new_user CROSS JOIN new_role;
        """,
        PRODUCT_ID, CLIENT_ID, LOAN_ID, CLIENT_ID, PRODUCT_ID, CLIENT_ID, UNAUTHORIZED_USERNAME);
  }

  @Test
  void registeredEndpointBindsFiltersAndSerializesPageResponse() {
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .queryParam("submittedFrom", "2026-09-01")
        .queryParam("submittedTo", "2026-09-01")
        .queryParam("productId", PRODUCT_ID)
        .queryParam("status", 100)
        .queryParam("minAmount", "1500")
        .queryParam("maxAmount", "1500")
        .queryParam("currencyCode", "USD")
        .queryParam("limit", 10)
        .queryParam("offset", 0)
        .when()
        .get(CREDIT_APPLICATIONS_PATH)
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("totalFilteredRecords", equalTo(1))
        .body("pageItems.size()", equalTo(1))
        .body("pageItems[0].loanId", equalTo((int) LOAN_ID))
        .body("pageItems[0].accountNo", equalTo("9106102"))
        .body("pageItems[0].clientId", equalTo((int) CLIENT_ID))
        .body("pageItems[0].groupId", equalTo(null))
        .body("pageItems[0].clientName", equalTo("WEB Applicant"))
        .body("pageItems[0].productId", equalTo((int) PRODUCT_ID))
        .body("pageItems[0].productName", equalTo("WEB-1061 HTTP Loan"))
        .body("pageItems[0].amount", equalTo(1500.0F))
        .body("pageItems[0].currencyCode", equalTo("USD"))
        .body("pageItems[0].status.id", equalTo(100))
        .body("pageItems[0].status.code", notNullValue())
        .body("pageItems[0].status.value", notNullValue())
        .body("pageItems[0].submittedOnDate", equalTo("2026-09-01"))
        .body("pageItems[0]", hasKey("stateProvinceId"))
        .body("pageItems[0].municipality", equalTo("WEB Municipality"));
  }

  @Test
  void validationFailureUsesFineractHttpErrorResponse() {
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .queryParam("minAmount", "1000")
        .when()
        .get(CREDIT_APPLICATIONS_PATH)
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("errors[0].parameterName", equalTo("currencyCode"));
  }

  @Test
  void userWithoutReadLoanPermissionIsForbidden() {
    given(
            SavingsTestUtils.requestSpecWithAuth(
                getFineractPort(), UNAUTHORIZED_USERNAME, "password"))
        .when()
        .get(CREDIT_APPLICATIONS_PATH)
        .then()
        .statusCode(403);
  }
}
