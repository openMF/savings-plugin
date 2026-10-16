package org.apache.fineract.baseteller.transfer;

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
class AccountTransferLiquibaseIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

  @Test
  void migrationRunsTwiceAndAddsOnlyLinkedWorkflowMetadata() throws Exception {
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    for (String table :
        java.util.List.of(
            "m_account_transfer_transaction",
            "m_account_transfer_details",
            "m_appuser",
            "m_office")) {
      jdbc.execute("CREATE TABLE " + table + " (id bigint PRIMARY KEY)");
    }
    for (int run = 0; run < 2; run++) {
      try (var connection =
          DriverManager.getConnection(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
        var db =
            DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        try (var migration =
            new Liquibase(
                "db/changelog/tenant/module/savings/parts/074-create-base-teller-account-transfer-workflow.xml",
                new ClassLoaderResourceAccessor(),
                db)) {
          migration.update(new Contexts(), new LabelExpression());
        }
      }
    }
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM databasechangelog", Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname='uk_bt_transfer_idempotency'",
            Integer.class));
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints WHERE"
                + " table_name='m_base_teller_account_transfer' AND constraint_type='FOREIGN KEY'",
            Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM m_account_transfer_transaction", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM m_base_teller_account_transfer", Integer.class));
  }
}
