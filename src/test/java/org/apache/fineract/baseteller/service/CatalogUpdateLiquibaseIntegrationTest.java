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
class CatalogUpdateLiquibaseIntegrationTest {

  private static final String CHANGELOG =
      "db/changelog/tenant/module/savings/parts/064-create-base-teller-catalog-update-schema.xml";

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
    jdbcTemplate.execute("CREATE TABLE m_office (id BIGINT PRIMARY KEY)");
    jdbcTemplate.execute("CREATE TABLE m_appuser (id BIGINT PRIMARY KEY)");
    jdbcTemplate.execute(
        "CREATE TABLE m_permission (id BIGSERIAL PRIMARY KEY, grouping VARCHAR(100),"
            + " code VARCHAR(100) UNIQUE, entity_name VARCHAR(100), action_name VARCHAR(100),"
            + " can_maker_checker BOOLEAN)");
    jdbcTemplate.execute("CREATE TABLE m_role (id BIGSERIAL PRIMARY KEY, name VARCHAR(100) UNIQUE)");
    jdbcTemplate.execute(
        "CREATE TABLE m_role_permission (role_id BIGINT, permission_id BIGINT,"
            + " UNIQUE(role_id,permission_id))");
    jdbcTemplate.update("INSERT INTO m_office VALUES (1),(2)");
    jdbcTemplate.update("INSERT INTO m_appuser VALUES (7)");
    jdbcTemplate.update("INSERT INTO m_role (name) VALUES ('Super user')");
    migrate();
  }

  @Test
  void migrationCreatesStateAuditPermissionsAndConstraints() {
    assertTrue(tableExists("m_base_teller_catalog_update"));
    assertTrue(tableExists("m_base_teller_catalog_update_audit"));
    assertEquals(
        2,
        count(
            "SELECT COUNT(*) FROM m_permission"
                + " WHERE code IN ('READ_BASE_TELLER_CATALOG_UPDATE',"
                + " 'UPDATE_BASE_TELLER_CATALOG_UPDATE')"));
    assertEquals(2, count("SELECT COUNT(*) FROM m_role_permission"));

    insertState(1L, "GENERAL");
    assertThrows(DataIntegrityViolationException.class, () -> insertState(1L, "GENERAL"));
    assertThrows(DataIntegrityViolationException.class, () -> insertState(999L, "USERS"));
  }

  @Test
  void repeatedMigrationIsIdempotent() throws Exception {
    migrate();

    assertEquals(
        3,
        count(
            "SELECT COUNT(*) FROM databasechangelog"
                + " WHERE id LIKE 'ss-064-base-teller-catalog-update-%'"));
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM m_permission"
                + " WHERE code='READ_BASE_TELLER_CATALOG_UPDATE'"));
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

  private void insertState(final Long officeId, final String category) {
    jdbcTemplate.update(
        "INSERT INTO m_base_teller_catalog_update"
            + " (office_id,category,status) VALUES (?,?,'UPDATE_REQUIRED')",
        officeId,
        category);
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
    final Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
    return value == null ? 0 : value;
  }
}
