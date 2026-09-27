/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CreditPaymentLiquibaseIntegrationTest {

  private static final String CHANGELOG =
      "db/changelog/tenant/module/savings/parts/063-create-base-teller-credit-payment-schema.xml";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() throws Exception {
    final DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("DROP SCHEMA public CASCADE");
    jdbcTemplate.execute("CREATE SCHEMA public");
    jdbcTemplate.execute("CREATE TABLE m_client (id BIGINT PRIMARY KEY)");
    jdbcTemplate.execute("CREATE TABLE m_loan (id BIGINT PRIMARY KEY)");
    jdbcTemplate.execute("CREATE TABLE m_payment_type (id BIGINT PRIMARY KEY)");
    jdbcTemplate.execute("CREATE TABLE m_appuser (id BIGINT PRIMARY KEY)");
    jdbcTemplate.execute("CREATE TABLE m_office (id BIGINT PRIMARY KEY)");
    jdbcTemplate.execute("CREATE TABLE m_tellers (id BIGINT PRIMARY KEY)");
    jdbcTemplate.execute("CREATE TABLE m_cashiers (id BIGINT PRIMARY KEY)");
    jdbcTemplate.execute("CREATE TABLE m_loan_transaction (id BIGINT PRIMARY KEY)");
    jdbcTemplate.execute("CREATE TABLE m_cashier_transactions (id BIGINT PRIMARY KEY)");
    jdbcTemplate.execute(
        "CREATE TABLE m_permission (id BIGSERIAL PRIMARY KEY, grouping VARCHAR(100),"
            + " code VARCHAR(100) UNIQUE, entity_name VARCHAR(100), action_name VARCHAR(100),"
            + " can_maker_checker BOOLEAN)");
    jdbcTemplate.execute("CREATE TABLE m_role (id BIGSERIAL PRIMARY KEY, name VARCHAR(100) UNIQUE)");
    jdbcTemplate.execute(
        "CREATE TABLE m_role_permission (role_id BIGINT, permission_id BIGINT,"
            + " UNIQUE(role_id,permission_id))");
    jdbcTemplate.execute(
        "CREATE TABLE m_base_teller_returned_check (id BIGSERIAL PRIMARY KEY,"
            + " deposit_id BIGINT NOT NULL, deposit_check_detail_id BIGINT NOT NULL)");
    jdbcTemplate.update("INSERT INTO m_client VALUES (1)");
    jdbcTemplate.update("INSERT INTO m_loan VALUES (2)");
    jdbcTemplate.update("INSERT INTO m_payment_type VALUES (3)");
    jdbcTemplate.update("INSERT INTO m_appuser VALUES (4)");
    jdbcTemplate.update("INSERT INTO m_office VALUES (1)");
    jdbcTemplate.update("INSERT INTO m_role (name) VALUES ('Super user')");

    try (Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      final Database database =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)
          .update(new Contexts(), new LabelExpression());
    }
  }

  @Test
  void createsWorkflowCatalogPermissionsAndDatabaseIdempotency() {
    assertTrue(tableExists("m_base_teller_bank"));
    assertTrue(tableExists("m_base_teller_credit_payment"));
    assertTrue(tableExists("m_base_teller_credit_payment_cash_detail"));
    assertTrue(tableExists("m_base_teller_credit_payment_check"));
    assertEquals(
        4,
        count(
            "SELECT COUNT(*) FROM m_permission WHERE code LIKE '%BASE_TELLER_CREDIT_PAYMENT%'"));
    assertEquals(4, count("SELECT COUNT(*) FROM m_role_permission"));

    insertPayment("payment-1", "CP-1");
    assertThrows(
        DataIntegrityViolationException.class, () -> insertPayment("payment-1", "CP-2"));
    assertThrows(
        DataIntegrityViolationException.class, () -> insertPayment("payment-2", "CP-1"));
  }

  @Test
  void scopesCheckIdentityAndAllowsLoanOriginReturnedCheck() {
    jdbcTemplate.update("INSERT INTO m_base_teller_bank (code,name) VALUES ('BANK-1','Bank One')");
    insertPayment("check-1", "CP-CHECK-1");
    final Long paymentId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM m_base_teller_credit_payment WHERE idempotency_key='check-1'",
            Long.class);
    final Long bankId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM m_base_teller_bank WHERE code='BANK-1'", Long.class);
    insertCheck(paymentId, bankId);
    assertThrows(DataIntegrityViolationException.class, () -> insertCheck(paymentId, bankId));

    final Long checkId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM m_base_teller_credit_payment_check WHERE credit_payment_id=?",
            Long.class,
            paymentId);
    jdbcTemplate.update(
        "INSERT INTO m_base_teller_returned_check"
            + " (deposit_id,deposit_check_detail_id,origin_type,credit_payment_id,"
            + " credit_payment_check_id,loan_id) VALUES (NULL,NULL,'LOAN',?,?,2)",
        paymentId,
        checkId);
    assertEquals(
        "LOAN",
        jdbcTemplate.queryForObject(
            "SELECT origin_type FROM m_base_teller_returned_check", String.class));
  }

  private void insertPayment(final String key, final String receipt) {
    jdbcTemplate.update(
        "INSERT INTO m_base_teller_credit_payment"
            + " (idempotency_key,request_fingerprint,receipt_number,status,payment_method,client_id,"
            + " loan_id,amount,currency_code,payment_type_id,business_date,operator_id,office_id,"
            + " client_name_snapshot,loan_account_no_snapshot,operator_name_snapshot)"
            + " VALUES (?,repeat('0',64),?,'PENDING_COLLECTION','CHECK',1,2,25,'USD',3,"
            + " CURRENT_DATE,4,1,'Client','0002','user')",
        key,
        receipt);
  }

  private void insertCheck(final Long paymentId, final Long bankId) {
    jdbcTemplate.update(
        "INSERT INTO m_base_teller_credit_payment_check"
            + " (credit_payment_id,loan_id,bank_id,bank_name_snapshot,check_type,check_number,"
            + " classification,status,accepted_by) VALUES (?,2,?,'Bank One','PERSONAL','12345',"
            + " 'SUBJECT_TO_COLLECTION','PENDING_COLLECTION',4)",
        paymentId,
        bankId);
  }

  private boolean tableExists(final String table) {
    return count(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'"
                + " AND table_name='"
                + table
                + "'")
        > 0;
  }

  private int count(final String sql) {
    final Integer result = jdbcTemplate.queryForObject(sql, Integer.class);
    return result == null ? 0 : result;
  }
}
