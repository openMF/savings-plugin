package org.apache.fineract.baseteller.withdrawal;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Schema invariants in an empty disposable database; no financial balance fixtures. */
@Testcontainers
class WithdrawalLiquibaseIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:15-alpine");

  private JdbcTemplate jdbc;

  @BeforeEach
  void setup() {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    for (String table :
        new String[] {
          "m_office",
          "m_cashiers",
          "m_appuser",
          "m_cashier_transactions",
          "m_client",
          "m_savings_account",
          "m_tellers",
          "m_savings_account_transaction"
        }) {
      jdbc.execute("CREATE TABLE " + table + "(id bigint PRIMARY KEY)");
      jdbc.execute("INSERT INTO " + table + " VALUES(1),(2)");
    }
    jdbc.execute(
        "CREATE TABLE m_permission(id bigserial PRIMARY KEY,grouping varchar(100),code varchar(100)"
            + " UNIQUE,entity_name varchar(100),action_name varchar(100),can_maker_checker"
            + " boolean)");
    jdbc.execute("CREATE TABLE m_role(id bigint PRIMARY KEY,name varchar(100))");
    jdbc.execute("INSERT INTO m_role VALUES(1,'Super user'),(2,'Teller')");
    jdbc.execute(
        "CREATE TABLE m_role_permission(role_id bigint,permission_id bigint,PRIMARY"
            + " KEY(role_id,permission_id))");
  }

  private void migrate() throws Exception {
    try (var c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())) {
      var db =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var l =
          new Liquibase("withdrawal-test-changelog.xml", new ClassLoaderResourceAccessor(), db)) {
        l.update(new Contexts(), new LabelExpression());
      }
    }
  }

  @Test
  void migrationAndPermissionsAreRepeatable() throws Exception {
    migrate();
    migrate();
    assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM m_permission", Integer.class));
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_role_permission WHERE role_id=1", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_role_permission WHERE role_id=2", Integer.class));
    assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM databasechangelog", Integer.class));
  }

  @Test
  void completedHeaderNeedsNativeWithdrawalAndCashQuantitiesArePositive() throws Exception {
    migrate();
    String header =
        "INSERT INTO"
            + " m_base_teller_withdrawal(idempotency_key,request_fingerprint,reference,status,business_date,client_id,account_id,office_id,teller_id,cashier_id,created_by,method,amount,currency_code,identifier_id,identification_type_id,identification_mask,created_at,snapshot)"
            + " VALUES('key','hash','WD-1','PROCESSING',CURRENT_DATE,1,1,1,1,1,1,'CASH',100,'AAA',1,1,'****1234',CURRENT_TIMESTAMP,'{}')";
    jdbc.update(header);
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(header));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE m_base_teller_withdrawal SET"
                    + " status='COMPLETED',completed_at=CURRENT_TIMESTAMP"));
    jdbc.update(
        "UPDATE m_base_teller_withdrawal SET"
            + " status='COMPLETED',completed_at=CURRENT_TIMESTAMP,savings_transaction_id=1");
    String cash =
        "INSERT INTO"
            + " m_base_teller_withdrawal_cash_detail(withdrawal_id,denomination_identifier,denomination_type,denomination_value,quantity,line_total)"
            + " VALUES(1,'note','BANKNOTE',100,1,100)";
    jdbc.update(cash);
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(cash));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(cash.replace("'note'", "'negative'").replace("100,1,100", "100,-1,-100")));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update(cash.replace("'note'", "'bad-total'").replace("100,1,100", "100,2,100")));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                header
                    .replace("'key'", "'check'")
                    .replace("'WD-1'", "'WD-2'")
                    .replace("'CASH'", "'CHECK'")));
  }
}
