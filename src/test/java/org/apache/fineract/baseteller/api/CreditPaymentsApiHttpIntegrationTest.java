/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.LocalDate;
import org.apache.fineract.testing.support.SavingsIntegrationTestBase;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CreditPaymentsApiHttpIntegrationTest extends SavingsIntegrationTestBase {

  private static final String PATH =
      SavingsTestUtils.CONTEXT_PATH + "/api/v2/base-teller/credit-payments";
  private static final String API = SavingsTestUtils.CONTEXT_PATH + "/api/v1";
  private static final String USERNAME = "web1253-teller";
  private static final String NO_CLEAR_USERNAME = "web1253-no-clear";
  private static Long clientId;
  private static Long loanId;
  private static Long cashPaymentTypeId;
  private static Long checkPaymentTypeId;
  private static Long bankId;
  private static String currency;
  private static String businessDate;

  @BeforeAll
  static void setUpCreditAndTeller() {
    currency = querySingleValueInDefaultTenant("SELECT code FROM m_organisation_currency ORDER BY code LIMIT 1");
    cashPaymentTypeId = resource(postAdmin("/paymenttypes",
        "{\"name\":\"WEB-1253 Cash\",\"description\":\"Cash credit payment\",\"isCashPayment\":true,\"position\":1}"));
    checkPaymentTypeId = resource(postAdmin("/paymenttypes",
        "{\"name\":\"WEB-1253 Check\",\"description\":\"Check credit payment\",\"isCashPayment\":false,\"position\":2}"));
    businessDate = LocalDate.now().toString();

    executeSqlInDefaultTenant(
        """
        INSERT INTO m_service_payment_denomination
          (identifier,currency_code,value,denomination_type,active)
        VALUES ('web1253-10',%s,10,'BANKNOTE',true)
        ON CONFLICT (currency_code,identifier) DO UPDATE SET value=10,active=true;

        INSERT INTO m_base_teller_bank (code,name,active)
        VALUES ('WEB1253-BANK','WEB-1253 Test Bank',true)
        ON CONFLICT (code) DO UPDATE SET active=true;

        INSERT INTO m_role (name,description,is_disabled)
        VALUES ('WEB-1253 teller','WEB-1253 integration role',false)
        ON CONFLICT (name) DO NOTHING;

        INSERT INTO m_role (name,description,is_disabled)
        VALUES ('WEB-1253 teller no clear','WEB-1253 restricted integration role',false)
        ON CONFLICT (name) DO NOTHING;

        INSERT INTO m_role_permission (role_id,permission_id)
        SELECT r.id,p.id FROM m_role r,m_permission p
        WHERE r.name='WEB-1253 teller' AND p.code IN
          ('READ_BASE_TELLER_CREDIT_PAYMENT','CREATE_BASE_TELLER_CREDIT_PAYMENT',
           'REPRINT_BASE_TELLER_CREDIT_PAYMENT','RETURN_BASE_TELLER_CREDIT_PAYMENT_CHECK',
           'AUTHORIZE_BASE_TELLER_CHECK_CLEARING','REPAYMENT_LOAN')
        ON CONFLICT DO NOTHING;

        INSERT INTO m_role_permission (role_id,permission_id)
        SELECT r.id,p.id FROM m_role r,m_permission p
        WHERE r.name='WEB-1253 teller no clear' AND p.code IN
          ('READ_BASE_TELLER_CREDIT_PAYMENT','CREATE_BASE_TELLER_CREDIT_PAYMENT',
           'REPRINT_BASE_TELLER_CREDIT_PAYMENT','RETURN_BASE_TELLER_CREDIT_PAYMENT_CHECK',
           'REPAYMENT_LOAN')
        ON CONFLICT DO NOTHING;

        INSERT INTO m_staff (is_loan_officer,office_id,firstname,lastname,display_name,is_active)
        VALUES (false,1,'WEB1253','Teller','WEB-1253 Teller',true);

        INSERT INTO m_tellers (office_id,name,description,state)
        VALUES (1,'WEB-1253 Window','Credit payment window',300);

        INSERT INTO m_cashiers (staff_id,teller_id,description,full_day)
        SELECT s.id,t.id,'WEB-1253 cashier',true FROM m_staff s,m_tellers t
        WHERE s.display_name='WEB-1253 Teller' AND t.name='WEB-1253 Window';

        INSERT INTO m_appuser (
          office_id,staff_id,username,firstname,lastname,password,email,firsttime_login_remaining,
          nonexpired,nonlocked,nonexpired_credentials,enabled,last_time_password_updated,
          password_never_expires)
        SELECT 1,s.id,%s,'WEB1253','Teller',u.password,'web1253@example.test',false,
               true,true,true,true,CURRENT_DATE,true
        FROM m_appuser u,m_staff s
        WHERE u.username='mifos' AND s.display_name='WEB-1253 Teller'
        ON CONFLICT (username) DO UPDATE SET staff_id=EXCLUDED.staff_id,office_id=EXCLUDED.office_id;

        INSERT INTO m_appuser (
          office_id,staff_id,username,firstname,lastname,password,email,firsttime_login_remaining,
          nonexpired,nonlocked,nonexpired_credentials,enabled,last_time_password_updated,
          password_never_expires)
        SELECT 1,s.id,%s,'WEB1253','Restricted',u.password,'web1253-restricted@example.test',false,
               true,true,true,true,CURRENT_DATE,true
        FROM m_appuser u,m_staff s
        WHERE u.username='mifos' AND s.display_name='WEB-1253 Teller'
        ON CONFLICT (username) DO UPDATE SET staff_id=EXCLUDED.staff_id,office_id=EXCLUDED.office_id;

        INSERT INTO m_appuser_role (appuser_id,role_id)
        SELECT u.id,r.id FROM m_appuser u,m_role r
        WHERE u.username=%s AND r.name='WEB-1253 teller'
        ON CONFLICT DO NOTHING;

        INSERT INTO m_appuser_role (appuser_id,role_id)
        SELECT u.id,r.id FROM m_appuser u,m_role r
        WHERE u.username=%s AND r.name='WEB-1253 teller no clear'
        ON CONFLICT DO NOTHING;
        """,
        currency,
        USERNAME,
        NO_CLEAR_USERNAME,
        USERNAME,
        NO_CLEAR_USERNAME);
    bankId = Long.valueOf(querySingleValueInDefaultTenant("SELECT id FROM m_base_teller_bank WHERE code='WEB1253-BANK'"));
    createAndDisburseLoan();
  }

  @Test
  void cashPendingClearReturnAndIdempotencyUseNativeFinancialState() {
    get(PATH + "/context").then().body("officeId", equalTo(1)).body("cashierId", notNullValue());
    get(PATH + "/loans/" + loanId).then().body("id", equalTo(loanId.intValue()))
        .body("repaymentSchedule", notNullValue()).body("transactions", notNullValue());
    final String beforeCash = outstanding();
    final String cashBody =
        paymentBody("cash-payment-1", "CASH", 20, cashPaymentTypeId,
            "\"denominations\":[{\"denominationId\":\"web1253-10\",\"quantity\":2}]");
    final Response cash = post(PATH, cashBody, 200);
    cash.then().body("status", equalTo("COMPLETED")).body("loanTransactionId", notNullValue())
        .body("cashierTransactionId", notNullValue()).body("principalPortion", notNullValue())
        .body("tenderAmount", equalTo(20.0F));
    final String afterCash = outstanding();
    assertNotEquals(beforeCash, afterCash);
    assertEquals("1", querySingleValueInDefaultTenant("SELECT COUNT(*) FROM m_cashier_transactions WHERE entity_type='BASE_TELLER_CREDIT_PAYMENT'"));
    assertEquals("0.000000", querySingleValueInDefaultTenant(
        "SELECT (SUM(CASE WHEN type_enum=1 THEN amount ELSE 0 END)-"
            + "SUM(CASE WHEN type_enum=2 THEN amount ELSE 0 END))::numeric(19,6)"
            + " FROM acc_gl_journal_entry WHERE loan_transaction_id IN"
            + " (SELECT id FROM m_loan_transaction WHERE loan_id=" + loanId + ")"));
    post(PATH, cashBody, 200).then().body("loanTransactionId", equalTo(cash.path("loanTransactionId")));
    get(PATH + "/" + cash.path("receiptNumber")).then()
        .body("loanTransactionId", equalTo(cash.path("loanTransactionId")))
        .body("denominations[0].quantity", equalTo(2));

    final String pendingBody =
        paymentBody("pending-check-1", "CHECK", 20, checkPaymentTypeId, checkJson("90001", "SUBJECT_TO_COLLECTION"));
    final String beforePending = outstanding();
    final Response pending = post(PATH, pendingBody, 200);
    pending.then().body("status", equalTo("PENDING_COLLECTION")).body("loanTransactionId", equalTo(null));
    assertEquals(beforePending, outstanding());
    final Number pendingCheckId = pending.path("check.id");
    post(PATH, pendingBody, 200).then().body("check.id", equalTo(pendingCheckId));
    final String clearBody = "{\"idempotencyKey\":\"clear-check-1\"}";
    postAs(NO_CLEAR_USERNAME, PATH + "/checks/" + pendingCheckId + "/clear", clearBody, 403);
    assertEquals(beforePending, outstanding());
    final Response cleared = post(PATH + "/checks/" + pendingCheckId + "/clear", clearBody, 200);
    cleared.then().body("status", equalTo("CLEARED")).body("loanTransactionId", notNullValue());
    assertNotEquals(beforePending, outstanding());
    post(PATH + "/checks/" + pendingCheckId + "/clear", clearBody, 200)
        .then().body("loanTransactionId", equalTo(cleared.path("loanTransactionId")));

    final Response returned =
        post(PATH, paymentBody("return-check-1", "CHECK", 20, checkPaymentTypeId,
            checkJson("90002", "SUBJECT_TO_COLLECTION")), 200);
    final String beforeReturn = outstanding();
    final Number returnedCheckId = returned.path("check.id");
    final String returnBody = "{\"idempotencyKey\":\"return-transition-1\",\"reason\":\"NSF\"}";
    post(PATH + "/checks/" + returnedCheckId + "/return", returnBody, 200)
        .then().body("status", equalTo("RETURNED")).body("loanTransactionId", equalTo(null));
    post(PATH + "/checks/" + returnedCheckId + "/return", returnBody, 200)
        .then().body("status", equalTo("RETURNED")).body("loanTransactionId", equalTo(null));
    assertEquals(beforeReturn, outstanding());
    assertEquals("LOAN", querySingleValueInDefaultTenant("SELECT origin_type FROM m_base_teller_returned_check WHERE credit_payment_check_id=" + returnedCheckId));
  }

  private static void createAndDisburseLoan() {
    final Long fund = gl("WEB1253-FUND", "WEB-1253 Fund", 1);
    final Long portfolio = gl("WEB1253-PORT", "WEB-1253 Portfolio", 1);
    final Long interest = gl("WEB1253-INT", "WEB-1253 Interest", 4);
    final Long fee = gl("WEB1253-FEE", "WEB-1253 Fee", 4);
    final Long penalty = gl("WEB1253-PEN", "WEB-1253 Penalty", 4);
    final Long recovery = gl("WEB1253-REC", "WEB-1253 Recovery", 4);
    final Long writeOff = gl("WEB1253-WOFF", "WEB-1253 Write Off", 5);
    final Long suspense = gl("WEB1253-SUSP", "WEB-1253 Suspense", 1);
    final Long overpayment = gl("WEB1253-OVER", "WEB-1253 Overpayment", 2);
    clientId = resource(postAdmin("/clients", "{\"officeId\":1,\"legalFormId\":1,\"firstname\":\"WEB1253\",\"lastname\":\"Borrower\",\"active\":true,\"activationDate\":\"" + businessDate + "\",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}"));
    final String product = "{\"name\":\"WEB-1253 Loan\",\"shortName\":\"W125\",\"currencyCode\":\"" + currency
        + "\",\"digitsAfterDecimal\":2,\"inMultiplesOf\":0,\"principal\":1000,\"minPrincipal\":100,\"maxPrincipal\":2000,"
        + "\"numberOfRepayments\":10,\"repaymentEvery\":1,\"repaymentFrequencyType\":2,\"interestRatePerPeriod\":10,"
        + "\"interestRateFrequencyType\":3,\"amortizationType\":1,\"interestType\":0,\"interestCalculationPeriodType\":1,"
        + "\"daysInYearType\":365,\"daysInMonthType\":30,\"isInterestRecalculationEnabled\":false,"
        + "\"transactionProcessingStrategyCode\":\"mifos-standard-strategy\",\"accountingRule\":2,\"fundSourceAccountId\":" + fund
        + ",\"loanPortfolioAccountId\":" + portfolio + ",\"interestOnLoanAccountId\":" + interest
        + ",\"incomeFromFeeAccountId\":" + fee + ",\"incomeFromPenaltyAccountId\":" + penalty
        + ",\"incomeFromRecoveryAccountId\":" + recovery + ",\"writeOffAccountId\":" + writeOff
        + ",\"transfersInSuspenseAccountId\":" + suspense + ",\"overpaymentLiabilityAccountId\":" + overpayment
        + ",\"locale\":\"en\"}";
    final Long productId = resource(postAdmin("/loanproducts", product));
    final String application = "{\"clientId\":" + clientId + ",\"loanType\":\"individual\",\"productId\":" + productId
        + ",\"principal\":1000,\"loanTermFrequency\":10,\"loanTermFrequencyType\":2,\"numberOfRepayments\":10,"
        + "\"repaymentEvery\":1,\"repaymentFrequencyType\":2,\"interestRatePerPeriod\":10,\"interestRateFrequencyType\":3,"
        + "\"amortizationType\":1,\"interestType\":0,\"interestCalculationPeriodType\":1,"
        + "\"transactionProcessingStrategyCode\":\"mifos-standard-strategy\",\"expectedDisbursementDate\":\"" + businessDate
        + "\",\"submittedOnDate\":\"" + businessDate + "\",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}";
    loanId = resource(postAdmin("/loans", application));
    postAdmin("/loans/" + loanId + "?command=approve", "{\"approvedLoanAmount\":1000,\"approvedOnDate\":\"" + businessDate + "\",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}");
    postAdmin("/loans/" + loanId + "?command=disburse", "{\"actualDisbursementDate\":\"" + businessDate + "\",\"transactionAmount\":1000,\"paymentTypeId\":" + cashPaymentTypeId + ",\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}");
  }

  private static Long gl(final String code, final String name, final int type) {
    return resource(postAdmin("/glaccounts", "{\"name\":\"" + name + "\",\"glCode\":\"" + code + "\",\"type\":" + type + ",\"usage\":1,\"manualEntriesAllowed\":true}"));
  }

  private static Response postAdmin(final String path, final String body) {
    return given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(body).when().post(API + path).then().log().ifValidationFails().statusCode(200).extract().response();
  }

  private static Response post(final String path, final String body, final int status) {
    return postAs(USERNAME, path, body, status);
  }

  private static Response postAs(final String username, final String path, final String body, final int status) {
    return given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), username, "password"))
        .body(body).when().post(path).then().log().ifValidationFails().statusCode(status).extract().response();
  }

  private static Response get(final String path) {
    return given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), USERNAME, "password"))
        .when().get(path).then().log().ifValidationFails().statusCode(200).extract().response();
  }

  private static Long resource(final Response response) {
    return ((Number) response.path("resourceId")).longValue();
  }

  private static String outstanding() {
    return querySingleValueInDefaultTenant("SELECT principal_outstanding_derived::text FROM m_loan WHERE id=" + loanId);
  }

  private static String paymentBody(final String key, final String method, final int amount,
      final Long paymentType, final String detail) {
    return "{\"idempotencyKey\":\"" + key + "\",\"clientId\":" + clientId + ",\"loanId\":" + loanId
        + ",\"paymentMethod\":\"" + method + "\",\"amount\":" + amount + ",\"currencyCode\":\"" + currency
        + "\",\"paymentTypeId\":" + paymentType + "," + detail + "}";
  }

  private static String checkJson(final String number, final String classification) {
    return "\"check\":{\"bankId\":" + bankId + ",\"checkType\":\"PERSONAL\",\"checkNumber\":\"" + number
        + "\",\"accountNumber\":\"ACC-1\",\"routingCode\":\"RT-1\",\"classification\":\"" + classification + "\"}";
  }
}
