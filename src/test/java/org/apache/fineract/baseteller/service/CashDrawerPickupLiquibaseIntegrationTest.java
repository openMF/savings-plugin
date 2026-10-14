package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.DriverManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
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
class CashDrawerPickupLiquibaseIntegrationTest {
  @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");
  private JdbcTemplate jdbc;

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    for (String table : new String[] {"m_office", "m_cashiers", "m_appuser"}) {
      jdbc.execute("CREATE TABLE " + table + " (id bigint PRIMARY KEY)");
      jdbc.execute("INSERT INTO " + table + " VALUES(1)");
    }
    jdbc.execute("CREATE TABLE m_permission(id bigserial PRIMARY KEY,grouping varchar(100),code varchar(100) UNIQUE,entity_name varchar(100),action_name varchar(100),can_maker_checker boolean)");
  }

  private void migrate() throws Exception {
    try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try (var liquibase = new Liquibase("db/changelog/tenant/module/savings/parts/071-create-cash-drawer-pickup-schema.xml", new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());
      }
    }
  }

  @Test
  void migrationCanRunTwiceAndEnforcesUniqueCustody() throws Exception {
    migrate();
    migrate();
    assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM m_permission", Integer.class));
    jdbc.update("INSERT INTO m_cash_drawer_pickup(idempotency_key,request_fingerprint,reference,status,business_date,office_id,source_cashier_id,destination_cashier_id,authorized_by,authorized_at,created_by,snapshot) VALUES('key','hash','PU-1','COMPLETED','2026-01-01',1,1,1,1,CURRENT_TIMESTAMP,1,'{}')");
    jdbc.update("INSERT INTO m_cash_drawer_pickup_currency(pickup_id,currency_code,business_date,status,source_cashier_id,destination_cashier_id,cash_total,check_total) VALUES(1,'AAA','2026-01-01','COMPLETED',1,1,0,10)");
    jdbc.update("INSERT INTO m_cash_drawer_pickup_check(pickup_currency_id,source_type,source_id,amount,source_cashier_id,destination_cashier_id) VALUES(1,'DEPOSIT',1,10,1,2)");
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO m_cash_drawer_pickup_check(pickup_currency_id,source_type,source_id,amount,source_cashier_id,destination_cashier_id) VALUES(1,'DEPOSIT',1,10,1,2)"));
  }
}
