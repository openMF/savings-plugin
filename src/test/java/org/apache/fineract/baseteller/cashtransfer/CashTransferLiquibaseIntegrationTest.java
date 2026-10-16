package org.apache.fineract.baseteller.cashtransfer;

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
class CashTransferLiquibaseIntegrationTest {
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
        new String[] {"m_office", "m_cashiers", "m_appuser", "m_cashier_transactions"}) {
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
          new Liquibase(
              "db/changelog/tenant/module/savings/parts/075-create-cash-teller-transfer-schema.xml",
              new ClassLoaderResourceAccessor(),
              db)) {
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
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM databasechangelog", Integer.class));
  }

  @Test
  void databaseRejectsDuplicateKeySelfTransferAndNonpositiveDenominations() throws Exception {
    migrate();
    String header =
        "INSERT INTO"
            + " m_cash_teller_transfer(idempotency_key,request_fingerprint,reference,status,business_date,office_id,source_cashier_id,destination_cashier_id,created_by,created_at,snapshot)"
            + " VALUES('key','hash','CT-1','PROCESSING',CURRENT_DATE,1,1,2,1,CURRENT_TIMESTAMP,'{}')";
    jdbc.update(header);
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(header));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                header
                    .replace("'key'", "'self'")
                    .replace("'CT-1'", "'CT-self'")
                    .replace("1,1,2,1", "1,1,1,1")));
    jdbc.update(
        "INSERT INTO"
            + " m_cash_teller_transfer_currency(transfer_id,currency_code,business_date,status,source_cashier_id,destination_cashier_id,cash_total)"
            + " VALUES(1,'AAA',CURRENT_DATE,'COMPLETED',1,2,100)");
    String denomination =
        "INSERT INTO"
            + " m_cash_teller_transfer_denomination(transfer_currency_id,denomination_identifier,denomination_type,denomination_value,quantity,line_total)"
            + " VALUES(1,'note','BANKNOTE',100,1,100)";
    jdbc.update(denomination);
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(denomination));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                denomination.replace("'note'", "'negative'").replace("100,1,100", "100,-1,-100")));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                denomination.replace("'note'", "'wrong-total'").replace("100,1,100", "100,2,100")));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                denomination.replace("'note'", "'check'").replace("'BANKNOTE'", "'CHECK'")));
  }
}
