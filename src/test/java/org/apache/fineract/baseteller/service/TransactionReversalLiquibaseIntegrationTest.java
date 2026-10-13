package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TransactionReversalLiquibaseIntegrationTest {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  @Test
  void migrationIsRepeatableAndEnforcesUniqueAuditRelationshipsWithoutSecretColumn()
      throws Exception {
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    for (String table :
        java.util.List.of("m_office", "m_cashiers", "m_tellers", "m_appuser", "m_base_teller_bank"))
      jdbc.execute("CREATE TABLE " + table + "(id bigint PRIMARY KEY)");
    jdbc.execute(
        "CREATE TABLE m_permission(id bigserial PRIMARY KEY,grouping varchar(100),code varchar(100)"
            + " UNIQUE,entity_name varchar(100),action_name varchar(100),can_maker_checker"
            + " boolean)");
    jdbc.execute(
        "CREATE TABLE m_cash_exchange(id bigint PRIMARY KEY,status varchar(20),CONSTRAINT"
            + " ck_cash_exchange_completed CHECK(status='COMPLETED'))");
    migrate();
    migrate();
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_permission WHERE"
                + " entity_name='BASE_TELLER_TRANSACTION_REVERSAL'",
            Integer.class));
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name LIKE"
                + " 'm_transaction_reversal%'",
            Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_name LIKE"
                + " 'm_transaction_reversal%' AND column_name IN"
                + " ('authorization_number','authorization_code','password','credential')",
            Integer.class));
    for (String key :
        java.util.List.of(
            "uk_reversal_original",
            "uk_reversal_idempotency",
            "uk_reversal_history",
            "uk_reversal_authorization",
            "uk_reversal_denomination",
            "uk_reversal_check"))
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM pg_constraint WHERE conname=? AND contype='u'",
              Integer.class,
              key));
    assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM databasechangelog", Integer.class));
  }

  private void migrate() throws Exception {
    try (var connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      var database =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      new Liquibase(
              "db/changelog/tenant/module/savings/parts/070-create-transaction-reversal-schema.xml",
              new ClassLoaderResourceAccessor(),
              database)
          .update(new Contexts(), new LabelExpression());
    }
  }
}
