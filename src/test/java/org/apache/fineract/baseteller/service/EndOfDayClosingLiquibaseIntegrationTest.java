package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import liquibase.*;
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
class EndOfDayClosingLiquibaseIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

  private JdbcTemplate jdbc;

  @BeforeEach
  void setup() {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    for (String t : new String[] {"m_office", "m_cashiers", "m_cashier_reconciliation"}) {
      jdbc.execute("CREATE TABLE " + t + " (id bigint PRIMARY KEY)");
      jdbc.execute("INSERT INTO " + t + " VALUES(1)");
    }
    jdbc.execute(
        "CREATE TABLE m_permission (id bigserial PRIMARY KEY,grouping varchar(100),code"
            + " varchar(100) UNIQUE,entity_name varchar(100),action_name"
            + " varchar(100),can_maker_checker boolean)");
  }

  private void migrate() throws Exception {
    for (String file :
        new String[] {
          "068-create-end-of-day-closing-schema.xml", "069-separate-closing-settlement.xml"
        }) {
      try (var c =
          DriverManager.getConnection(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
        var db =
            DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
        try (var l =
            new Liquibase(
                "db/changelog/tenant/module/savings/parts/" + file,
                new ClassLoaderResourceAccessor(),
                db)) {
          l.update(new Contexts(), new LabelExpression());
        }
      }
    }
  }

  @Test
  void migrationRunsTwiceWithoutDuplicatingPermissions() throws Exception {
    migrate();
    migrate();
    assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM m_permission", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
                "SELECT voucher_total FROM m_cashier_reconciliation WHERE id=1",
                java.math.BigDecimal.class)
            .signum());
  }

  @Test
  void constraintsProtectSessionAndCheckUniqueness() throws Exception {
    migrate();
    jdbc.update(
        "INSERT INTO"
            + " m_end_of_day_closing(idempotency_key,request_fingerprint,business_date,office_id,cashier_id,snapshot)"
            + " VALUES('a','hash','2026-01-01',1,1,'{}')");
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO"
                    + " m_end_of_day_closing(idempotency_key,request_fingerprint,business_date,office_id,cashier_id,snapshot)"
                    + " VALUES('b','hash','2026-01-01',1,1,'{}')"));
    jdbc.update(
        "INSERT INTO"
            + " m_end_of_day_closing_check(closing_id,reconciliation_id,source_type,source_id,currency_code,amount)"
            + " VALUES(1,1,'CREDIT',1,'AAA',10)");
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO"
                    + " m_end_of_day_closing_check(closing_id,reconciliation_id,source_type,source_id,currency_code,amount)"
                    + " VALUES(1,1,'CREDIT',1,'AAA',10)"));
  }
}
