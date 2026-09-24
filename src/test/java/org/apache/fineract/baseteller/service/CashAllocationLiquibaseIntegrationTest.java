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
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class CashAllocationLiquibaseIntegrationTest {

  private static final String CHANGELOG =
      "db/changelog/tenant/module/savings/parts/061-create-cash-allocation-schema.xml";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() throws Exception {
    final DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_cash_allocation_denomination");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_cash_allocation");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_role_permission");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_permission");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_role");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_cashiers");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_appuser");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_office");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_currency");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS databasechangelog");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS databasechangeloglock");
    this.jdbcTemplate.execute("CREATE TABLE m_office (id BIGINT PRIMARY KEY)");
    this.jdbcTemplate.execute("CREATE TABLE m_appuser (id BIGINT PRIMARY KEY)");
    this.jdbcTemplate.execute("CREATE TABLE m_cashiers (id BIGINT PRIMARY KEY)");
    this.jdbcTemplate.execute("CREATE TABLE m_currency (code VARCHAR(3) PRIMARY KEY)");
    this.jdbcTemplate.execute(
        "CREATE TABLE m_permission (id BIGSERIAL PRIMARY KEY, grouping VARCHAR(100),"
            + " code VARCHAR(100) UNIQUE, entity_name VARCHAR(100), action_name VARCHAR(100),"
            + " can_maker_checker BOOLEAN)");
    this.jdbcTemplate.execute(
        "CREATE TABLE m_role (id BIGSERIAL PRIMARY KEY, name VARCHAR(100) UNIQUE)");
    this.jdbcTemplate.execute(
        "CREATE TABLE m_role_permission (role_id BIGINT, permission_id BIGINT,"
            + " UNIQUE(role_id, permission_id))");
    this.jdbcTemplate.update("INSERT INTO m_office VALUES (1)");
    this.jdbcTemplate.update("INSERT INTO m_appuser VALUES (1)");
    this.jdbcTemplate.update("INSERT INTO m_cashiers VALUES (10),(11)");
    this.jdbcTemplate.update("INSERT INTO m_currency VALUES ('CRC'),('EUR')");
    this.jdbcTemplate.update("INSERT INTO m_role (name) VALUES ('Super user')");

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
  void migrationCreatesImmutableReceiptSchemaPermissionsAndConstraints() {
    assertTrue(tableExists("m_cash_allocation"));
    assertTrue(tableExists("m_cash_allocation_denomination"));
    assertEquals(
        3,
        count(
            "SELECT COUNT(*) FROM m_permission WHERE code IN"
                + " ('READ_BASE_TELLER_CASH_ALLOCATION','CREATE_BASE_TELLER_CASH_ALLOCATION',"
                + "'REPRINT_BASE_TELLER_CASH_ALLOCATION')"));
    assertEquals(
        3,
        count(
            "SELECT COUNT(*) FROM m_role_permission rp"
                + " JOIN m_role r ON r.id=rp.role_id"
                + " JOIN m_permission p ON p.id=rp.permission_id"
                + " WHERE r.name='Super user' AND p.code LIKE '%BASE_TELLER_CASH_ALLOCATION'"));

    insertAllocation("safe-1", "CA-1", "VAULT", null, "CRC");
    this.jdbcTemplate.update(
        "INSERT INTO m_cash_allocation_denomination"
            + " (allocation_id,denomination_identifier,denomination_type,denomination_value,quantity,line_total)"
            + " SELECT id,'bill-100','BANKNOTE',100,2,200 FROM m_cash_allocation WHERE idempotency_key='safe-1'");

    assertEquals(
        "100.000000",
        this.jdbcTemplate.queryForObject(
            "SELECT denomination_value::text FROM m_cash_allocation_denomination",
            String.class));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> insertAllocation("safe-1", "CA-2", "CASHIER:10", 10L, "CRC"));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> insertAllocation("safe-2", "CA-2", "VAULT", null, "CRC"));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> insertAllocation("safe-3", "CA-3", "CASHIER:999", 999L, "CRC"));
  }

  @Test
  void concurrentOpeningOfSameDrawerHasExactlyOneWinner() throws Exception {
    final Callable<Boolean> create =
        () -> {
          try {
            insertAllocation(
                "head-" + Thread.currentThread().threadId(),
                "CA-" + Thread.currentThread().threadId(),
                "CASHIER:10",
                10L,
                "EUR");
            return true;
          } catch (DataIntegrityViolationException failure) {
            return false;
          }
        };
    try (var executor = Executors.newFixedThreadPool(2)) {
      final List<Future<Boolean>> results = executor.invokeAll(List.of(create, create));
      int successes = 0;
      for (Future<Boolean> result : results) if (result.get()) successes++;
      assertEquals(1, successes);
    }
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM m_cash_allocation WHERE currency_code='EUR'"
                + " AND destination_key='CASHIER:10'"));
  }

  private void insertAllocation(
      final String key,
      final String receipt,
      final String destinationKey,
      final Long destinationCashierId,
      final String currency) {
    this.jdbcTemplate.update(
        "INSERT INTO m_cash_allocation"
            + " (idempotency_key,request_fingerprint,receipt_number,operation_type,status,business_date,"
            + "office_id,office_name,initiated_by,initiated_by_username,source_name,destination_cashier_id,"
            + "destination_name,destination_key,currency_code,amount,destination_balance_before,"
            + "destination_balance_after,completed_on_utc)"
            + " VALUES (?,?,?,'SAFE_VAULT_OPENING','COMPLETED',?,1,'Office',1,'user','source',?,"
            + "'destination',?,?,100,0,100,CURRENT_TIMESTAMP)",
        key,
        "0".repeat(64),
        receipt,
        LocalDate.of(2026, 9, 24),
        destinationCashierId,
        destinationKey,
        currency);
  }

  private boolean tableExists(final String tableName) {
    return count(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'"
                + " AND table_name='"
                + tableName
                + "'")
        > 0;
  }

  private int count(final String sql) {
    final Integer value = this.jdbcTemplate.queryForObject(sql, Integer.class);
    return value == null ? 0 : value;
  }
}
