package org.apache.fineract.baseteller.foreigncurrency;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** Real PostgreSQL, real Liquibase and transaction boundaries. Authentication alone is stubbed. */
class FxInfrastructureIntegrationTest {
  private static final String CHANGELOG =
      "db/changelog/tenant/module/savings/parts/078-create-foreign-currency-drafts.xml";
  private static PostgreSQLContainer<?> container;
  private static String url, username, password;
  private JdbcTemplate jdbc, root;
  private DriverManagerDataSource ds;
  private TransactionTemplate tx;
  private PlatformSecurityContext security;
  private AppUser actor;
  private FxRateService rates;
  private String schema;

  @BeforeAll
  static void database() {
    url = System.getProperty("web1264.test.jdbcUrl");
    if (url == null) {
      container = new PostgreSQLContainer<>("postgres:15-alpine");
      container.start();
      url = container.getJdbcUrl();
      username = container.getUsername();
      password = container.getPassword();
    } else {
      username = System.getProperty("web1264.test.username", "postgres");
      password = System.getProperty("web1264.test.password", "");
    }
  }

  @AfterAll
  static void shutdown() {
    if (container != null) container.stop();
  }

  @BeforeEach
  void setup() throws Exception {
    schema = "web1264_" + UUID.randomUUID().toString().replace("-", "");
    root = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    root.execute("CREATE SCHEMA " + schema);
    ds =
        new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, username, password);
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    jdbc.execute(
        "CREATE TABLE m_office(id bigint PRIMARY KEY,name varchar(100),hierarchy varchar(100))");
    jdbc.execute(
        "INSERT INTO m_office VALUES(1,'Head','.1.'),(2,'Branch','.1.2.'),(3,'Other','.3.')");
    jdbc.execute("CREATE TABLE m_appuser(id bigint PRIMARY KEY)");
    jdbc.execute("INSERT INTO m_appuser VALUES(1),(2)");
    jdbc.execute(
        "CREATE TABLE m_organisation_currency(code varchar(3) PRIMARY KEY,name"
            + " varchar(100),decimal_places int)");
    jdbc.execute(
        "INSERT INTO m_organisation_currency"
            + " VALUES('USD','Dollar',2),('EUR','Euro',2),('JPY','Yen',0)");
    jdbc.execute(
        "CREATE TABLE m_permission(id bigserial PRIMARY KEY,grouping varchar(100),code varchar(100)"
            + " UNIQUE,entity_name varchar(100),action_name varchar(100),can_maker_checker"
            + " boolean)");
    jdbc.execute("CREATE TABLE m_role(id bigint PRIMARY KEY,name varchar(100))");
    jdbc.execute("INSERT INTO m_role VALUES(1,'Super user'),(2,'Teller')");
    jdbc.execute(
        "CREATE TABLE m_role_permission(role_id bigint,permission_id bigint,PRIMARY"
            + " KEY(role_id,permission_id))");
    // Empty financial tables are sentinels, not simulated transactions or balances.
    for (String table :
        List.of(
            "m_cashier_transactions",
            "acc_gl_journal_entry",
            "m_base_teller_withdrawal",
            "m_cash_exchange",
            "m_cashier_reconciliation",
            "m_cash_allocation",
            "m_exchange_rate")) jdbc.execute("CREATE TABLE " + table + "(id bigint PRIMARY KEY)");
    migrate();
    security = mock(PlatformSecurityContext.class);
    actor = mock(AppUser.class);
    var office = mock(Office.class);
    when(office.getHierarchy()).thenReturn(".1.");
    when(actor.getOffice()).thenReturn(office);
    when(actor.getId()).thenReturn(1L);
    when(security.authenticatedUser()).thenReturn(actor);
    rates = new FxRateService(security, jdbc);
    businessDate();
  }

  @AfterEach
  void cleanup() {
    ThreadLocalContextUtil.reset();
    if (root != null && schema != null) root.execute("DROP SCHEMA " + schema + " CASCADE");
  }

  private static void businessDate() {
    ThreadLocalContextUtil.setTenant(
        new org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant(
            1L, "web1264", "FX test", "UTC", null));
    ThreadLocalContextUtil.setBusinessDates(
        new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 10, 21))));
  }

  private void migrate() throws Exception {
    try (var c = ds.getConnection()) {
      var db =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var l = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), db)) {
        l.update(new Contexts(), new LabelExpression());
      }
    }
  }

  private FxData.RateRequest request(long version) {
    return new FxData.RateRequest(
        "2026-10-21",
        "USD",
        "EUR",
        new BigDecimal("2.34"),
        List.of(
            new FxData.BranchInput(1L, version, new BigDecimal("-0.01"), new BigDecimal("0.02"))),
        null);
  }

  private FxData.RateRequest confirmed(FxData.RateRequest r) {
    return new FxData.RateRequest(
        r.effectiveDate(),
        r.baseCurrency(),
        r.quoteCurrency(),
        r.dailyBaseRate(),
        r.branches(),
        rates.preview(r).previewToken());
  }

  private List<FxData.RateDraft> save(FxData.RateRequest r) {
    return tx.execute(s -> rates.save(r));
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  @Test
  void repeatableMigrationPermissionsAndTenantSchemaScope() throws Exception {
    migrate();
    assertEquals(3, count("databasechangelog"));
    assertEquals(6, count("m_permission"));
    assertEquals(
        6,
        jdbc.queryForObject("SELECT COUNT(*) FROM m_role_permission WHERE role_id=1", Long.class));
    assertEquals(
        0,
        jdbc.queryForObject("SELECT COUNT(*) FROM m_role_permission WHERE role_id=2", Long.class));
    assertNull(rates.context().nationalCurrency());
    assertFalse(rates.context().capabilities().publicationEnabled());
    // Each test uses a new schema, and drafts remain in that tenant's search path only.
    assertEquals(schema, jdbc.queryForObject("SELECT current_schema()", String.class));
    assertEquals(0, count("m_fx_rate_draft"));
  }

  @Test
  void previewDoesNotPersistAndConfirmationRequiresExactOperatorBoundPreview() {
    var r = request(0);
    rates.preview(r);
    assertEquals(0, count("m_fx_rate_draft"));
    assertEquals(0, count("m_fx_rate_audit"));
    assertThrows(GeneralPlatformDomainRuleException.class, () -> save(r));
    var ready = confirmed(r);
    when(actor.getId()).thenReturn(2L);
    assertThrows(GeneralPlatformDomainRuleException.class, () -> save(ready));
    assertEquals(0, count("m_fx_rate_draft"));
  }

  @Test
  void immutableHistoricalVersionsAndAuditPreserveBothValues() {
    var first = save(confirmed(request(0))).getFirst();
    var second = save(confirmed(request(1))).getFirst();
    assertEquals(1, first.version());
    assertEquals(2, second.version());
    assertEquals("DRAFT", second.status());
    assertEquals(first, rates.retrieve(first.id()));
    var audits = rates.audit(second.id());
    assertEquals(2, audits.size());
    assertNull(audits.getFirst().previous());
    assertEquals(first.id(), audits.getLast().previous().id());
    assertEquals(second.id(), audits.getLast().proposed().id());
    assertEquals(1L, audits.getLast().operatorId());
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE m_fx_rate_draft SET status='ACTIVE' WHERE id=?", second.id()));
    assertEquals(0, count("m_exchange_rate"));
  }

  @Test
  void allBranchesAreValidatedAndBulkDraftsCommitTogether() {
    var r = request(0);
    var bulk =
        new FxData.RateRequest(
            r.effectiveDate(),
            r.baseCurrency(),
            r.quoteCurrency(),
            r.dailyBaseRate(),
            List.of(
                r.branches().getFirst(),
                new FxData.BranchInput(2L, 0L, BigDecimal.ONE, BigDecimal.TEN)),
            null);
    assertEquals(2, save(confirmed(bulk)).size());
    assertEquals(2, count("m_fx_rate_audit"));
    var bad =
        new FxData.RateRequest(
            r.effectiveDate(),
            r.baseCurrency(),
            r.quoteCurrency(),
            r.dailyBaseRate(),
            List.of(
                new FxData.BranchInput(1L, 1L, BigDecimal.ONE, BigDecimal.TEN),
                new FxData.BranchInput(3L, 0L, BigDecimal.ONE, BigDecimal.TEN)),
            "unused");
    assertThrows(GeneralPlatformDomainRuleException.class, () -> save(bad));
    assertEquals(2, count("m_fx_rate_draft"));
  }

  @Test
  void auditFailureRollsBackEveryBranchDraft() {
    var r = confirmed(request(0));
    jdbc.execute(
        "ALTER TABLE m_fx_rate_audit ADD CONSTRAINT injected_audit_failure CHECK(operator_id&lt;0)"
            .replace("&lt;", "<"));
    assertThrows(DataIntegrityViolationException.class, () -> save(r));
    assertEquals(0, count("m_fx_rate_draft"));
    assertEquals(0, count("m_fx_rate_audit"));
  }

  @Test
  void concurrentConfirmationAndLostResponseRetryCannotCreateDuplicateRevisions() throws Exception {
    var r = confirmed(request(0));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var jobs =
          pool.invokeAll(
              List.of(
                  () -> {
                    businessDate();
                    try {
                      save(r);
                      return true;
                    } catch (GeneralPlatformDomainRuleException e) {
                      return false;
                    }
                  },
                  () -> {
                    businessDate();
                    try {
                      save(r);
                      return true;
                    } catch (GeneralPlatformDomainRuleException e) {
                      return false;
                    }
                  }));
      assertEquals(
          1,
          jobs.stream()
              .filter(
                  j -> {
                    try {
                      return (Boolean) j.get();
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .count());
    }
    assertThrows(GeneralPlatformDomainRuleException.class, () -> save(r));
    assertEquals(1, count("m_fx_rate_draft"));
    assertEquals(1, count("m_fx_rate_audit"));
  }

  @Test
  void disabledPublicationAndTradesHaveZeroPersistedFinancialEffects() {
    save(confirmed(request(0)));
    var teller = new FxTellerService(security, jdbc, rates, null, null);
    for (int retry = 0; retry < 2; retry++) {
      for (var op : FxData.Operation.values())
        assertThrows(GeneralPlatformDomainRuleException.class, () -> teller.confirm(op));
      assertThrows(GeneralPlatformDomainRuleException.class, rates::publish);
    }
    for (String table :
        List.of(
            "m_cashier_transactions",
            "acc_gl_journal_entry",
            "m_base_teller_withdrawal",
            "m_cash_exchange",
            "m_cashier_reconciliation",
            "m_cash_allocation",
            "m_exchange_rate")) assertEquals(0, count(table), table);
    assertEquals(1, count("m_fx_rate_draft"));
    assertEquals(1, count("m_fx_rate_audit"));
  }

  @Test
  void unsupportedCurrenciesAndOutOfScopeBranchesDoNotPersistDrafts() {
    var r = request(0);
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            rates.preview(
                new FxData.RateRequest(
                    r.effectiveDate(), "USD", "MXN", r.dailyBaseRate(), r.branches(), null)));
    assertThrows(
        GeneralPlatformDomainRuleException.class, () -> rates.history(3L, null, null, null));
    assertEquals(0, count("m_fx_rate_draft"));
  }
}
