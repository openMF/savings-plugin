/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.prospect.service;

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
class ProspectSchemaLiquibaseIntegrationTest {

  private static final String CHANGELOG =
      "db/changelog/tenant/module/savings/parts/054-create-prospect-registration-schema.xml";

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
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_prospect_stage_event");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_prospect_registration");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_role_permission");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_permission");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_role");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_client");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_office");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_appuser");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS databasechangelog");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS databasechangeloglock");
    this.jdbcTemplate.execute("CREATE TABLE m_office (id BIGINT PRIMARY KEY)");
    this.jdbcTemplate.execute("CREATE TABLE m_client (id BIGINT PRIMARY KEY)");
    this.jdbcTemplate.execute("CREATE TABLE m_appuser (id BIGINT PRIMARY KEY)");
    this.jdbcTemplate.execute(
        "CREATE TABLE m_permission (id BIGSERIAL PRIMARY KEY, grouping VARCHAR(100),"
            + " code VARCHAR(100) UNIQUE, entity_name VARCHAR(100), action_name VARCHAR(100),"
            + " can_maker_checker BOOLEAN)");
    this.jdbcTemplate.execute(
        "CREATE TABLE m_role (id BIGSERIAL PRIMARY KEY, name VARCHAR(100) UNIQUE)");
    this.jdbcTemplate.execute(
        "CREATE TABLE m_role_permission (role_id BIGINT, permission_id BIGINT,"
            + " UNIQUE(role_id, permission_id))");
    this.jdbcTemplate.update("INSERT INTO m_office VALUES (?)", 1L);
    this.jdbcTemplate.update("INSERT INTO m_client VALUES (?)", 11L);
    this.jdbcTemplate.update("INSERT INTO m_appuser VALUES (?)", 7L);
    this.jdbcTemplate.update("INSERT INTO m_role (name) VALUES (?)", "Super user");

    try (Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      final Database database =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      final Liquibase liquibase =
          new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
      liquibase.update(new Contexts(), new LabelExpression());
    }
  }

  @Test
  void prospectMigrationKeepsStageHistoryRestrictiveAndAddsReadPermission() {
    assertTrue(tableExists("m_prospect_registration"));
    assertTrue(tableExists("m_prospect_stage_event"));
    assertEquals("RESTRICT", deleteRule("fk_prospect_stage_event_case"));
    assertEquals(1, count("SELECT COUNT(*) FROM m_permission WHERE code = 'READ_PROSPECT'"));
    assertEquals(1, count("SELECT COUNT(*) FROM m_role_permission"));

    this.jdbcTemplate.update(
        "INSERT INTO m_prospect_registration"
            + " (id, external_ref, office_id, client_id, registration_status, version,"
            + " created_by, created_on_utc, last_modified_by, last_modified_on_utc)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)",
        1L,
        "prospect-1",
        1L,
        11L,
        "PENDING",
        1,
        7L,
        7L);
    this.jdbcTemplate.update(
        "INSERT INTO m_prospect_stage_event"
            + " (id, case_id, sequence_no, stage_code, new_status, occurred_on_utc,"
            + " recorded_on_utc, actor_id, source, event_key)"
            + " VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?)",
        2L,
        1L,
        1L,
        "COMMERCIAL_REGISTRATION",
        "IN_PROGRESS",
        7L,
        "TEST",
        "event-2");

    assertThrows(
        DataIntegrityViolationException.class,
        () -> this.jdbcTemplate.update("DELETE FROM m_prospect_registration WHERE id = ?", 1L));
  }

  private boolean tableExists(final String tableName) {
    return count(
            "SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema = 'public' AND table_name = '"
                + tableName
                + "'")
        > 0;
  }

  private String deleteRule(final String constraintName) {
    return this.jdbcTemplate.queryForObject(
        "SELECT delete_rule FROM information_schema.referential_constraints"
            + " WHERE constraint_schema = 'public' AND constraint_name = ?",
        String.class,
        constraintName);
  }

  private int count(final String sql) {
    final Integer count = this.jdbcTemplate.queryForObject(sql, Integer.class);
    return count == null ? 0 : count;
  }
}
