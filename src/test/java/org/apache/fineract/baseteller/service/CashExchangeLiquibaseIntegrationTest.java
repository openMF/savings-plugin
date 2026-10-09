package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
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
class CashExchangeLiquibaseIntegrationTest {
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
    for (String table : new String[] {"m_office", "m_tellers", "m_cashiers", "m_appuser"}) {
      jdbc.execute("CREATE TABLE " + table + "(id bigint PRIMARY KEY)");
      jdbc.execute("INSERT INTO " + table + " VALUES(1)");
    }
    jdbc.execute(
        "CREATE TABLE m_permission(id bigserial PRIMARY KEY,grouping varchar(100),code varchar(100)"
            + " UNIQUE,entity_name varchar(100),action_name varchar(100),can_maker_checker"
            + " boolean)");
  }

  private void migrate() throws Exception {
    try (var c =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      var db =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var l =
          new Liquibase(
              "db/changelog/tenant/module/savings/parts/067-create-cash-exchange-schema.xml",
              new ClassLoaderResourceAccessor(),
              db)) {
        l.update(new Contexts(), new LabelExpression());
      }
    }
  }

  @Test
  void cleanAndExistingMigrationsAreIdempotent() throws Exception {
    migrate();
    migrate();
    assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM m_permission", Integer.class));
    assertEquals(
        1, jdbc.queryForObject("SELECT COUNT(*) FROM m_cash_exchange_mutex", Integer.class));
  }

  @Test
  void databaseEnforcesEqualityPositiveAmountAndUniqueKey() throws Exception {
    migrate();
    String sql =
        "INSERT INTO"
            + " m_cash_exchange(idempotency_key,request_fingerprint,receipt_number,status,business_date,office_id,teller_id,cashier_id,teller_name,currency_code,received_total,delivered_total,created_by,created_by_username)"
            + " VALUES(?,repeat('a',64),?,'COMPLETED',CURRENT_DATE,1,1,1,'Test','EUR',?,?,1,'Test')";
    assertThrows(RuntimeException.class, () -> jdbc.update(sql, "unequal", "CE-1", 100, 99));
    assertThrows(RuntimeException.class, () -> jdbc.update(sql, "zero", "CE-2", 0, 0));
    jdbc.update(sql, "same", "CE-3", 100, 100);
    assertThrows(RuntimeException.class, () -> jdbc.update(sql, "same", "CE-4", 100, 100));
  }
}
