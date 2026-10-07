/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TransactionHistoryLiquibaseIntegrationTest {

  private static final String CHANGELOG =
      "db/changelog/tenant/module/savings/parts/066-add-base-teller-transaction-history.xml";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    jdbcTemplate =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbcTemplate.execute("DROP TABLE IF EXISTS m_base_teller_credit_payment");
    jdbcTemplate.execute("DROP TABLE IF EXISTS m_base_teller_savings_opening");
    jdbcTemplate.execute("DROP TABLE IF EXISTS m_base_teller_deposit");
    jdbcTemplate.execute("DROP TABLE IF EXISTS m_role_permission");
    jdbcTemplate.execute("DROP TABLE IF EXISTS m_permission");
    jdbcTemplate.execute("DROP TABLE IF EXISTS m_role");
    jdbcTemplate.execute("DROP TABLE IF EXISTS databasechangelog");
    jdbcTemplate.execute("DROP TABLE IF EXISTS databasechangeloglock");
    jdbcTemplate.execute(
        "CREATE TABLE m_permission (id BIGSERIAL PRIMARY KEY, grouping VARCHAR(100),"
            + " code VARCHAR(100) UNIQUE, entity_name VARCHAR(100), action_name VARCHAR(100),"
            + " can_maker_checker BOOLEAN)");
    jdbcTemplate.execute("CREATE TABLE m_role (id BIGSERIAL PRIMARY KEY, name VARCHAR(100))");
    jdbcTemplate.execute(
        "CREATE TABLE m_role_permission (role_id BIGINT, permission_id BIGINT,"
            + " UNIQUE(role_id,permission_id))");
    jdbcTemplate.execute(
        "CREATE TABLE m_base_teller_deposit (office_id BIGINT, completed_on_utc TIMESTAMP,"
            + " cashier_id BIGINT)");
    jdbcTemplate.execute(
        "CREATE TABLE m_base_teller_savings_opening (office_id BIGINT,"
            + " completed_on_utc TIMESTAMP, cashier_id BIGINT)");
    jdbcTemplate.execute(
        "CREATE TABLE m_base_teller_credit_payment (office_id BIGINT, business_date DATE,"
            + " cashier_id BIGINT)");
    jdbcTemplate.update("INSERT INTO m_role(name) VALUES ('Super user')");
  }

  @Test
  void migrationAddsPermissionAndIndexesAndIsIdempotent() throws Exception {
    migrate();
    migrate();

    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_permission"
                + " WHERE code='READ_BASE_TELLER_TRANSACTION_HISTORY'",
            Integer.class));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_role_permission rp"
                + " JOIN m_permission p ON p.id=rp.permission_id"
                + " WHERE p.code='READ_BASE_TELLER_TRANSACTION_HISTORY'",
            Integer.class));
    assertEquals(1, indexCount("idx_bt_deposit_history"));
    assertEquals(1, indexCount("idx_bt_opening_history"));
    assertEquals(1, indexCount("idx_bt_credit_payment_history"));
  }

  private int indexCount(String indexName) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname=?",
        Integer.class,
        indexName);
  }

  private void migrate() throws Exception {
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
}
