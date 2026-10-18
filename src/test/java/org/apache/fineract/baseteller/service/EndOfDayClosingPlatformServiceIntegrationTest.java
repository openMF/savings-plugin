package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.*;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.baseteller.data.*;
import org.apache.fineract.baseteller.data.EndOfDayClosingRequest.*;
import org.apache.fineract.baseteller.validation.CashManagementValidator;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.teller.data.CashierData;
import org.apache.fineract.organisation.teller.service.TellerManagementReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class EndOfDayClosingPlatformServiceIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

  private JdbcTemplate jdbc;
  private EndOfDayClosingPlatformService service;
  private TransactionTemplate tx;
  private LocalDate date = LocalDate.of(2026, 1, 1);
  private EndOfDayClosingAuthorization authorization;
  private PortfolioCommandSourceWritePlatformService commands;
  private AppUser actor;

  @BeforeEach
  void setup() throws Exception {
    var ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    ThreadLocalContextUtil.setBusinessDates(
        new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, date)));
    for (String table :
        List.of(
            "m_loan_transaction",
            "m_client_transaction",
            "m_account_transfer_transaction",
            "m_payment_detail",
            "m_payment_type",
            "m_savings_account",
            "m_loan",
            "m_client")) {
      jdbc.execute("DROP TABLE IF EXISTS " + table + " CASCADE");
    }
    dropAndCreateSchema();
    for (String table :
        new String[] {
          "m_cashier_reconciliation_check",
          "m_cashier_reconciliation",
          "m_cash_operation_check",
          "m_cash_operation_transaction",
          "m_end_of_day_closing_check"
        }) jdbc.execute("DROP TABLE " + table);
    jdbc.execute(
        "CREATE TABLE m_permission(id bigserial PRIMARY KEY,grouping varchar(100),code varchar(100)"
            + " UNIQUE,entity_name varchar(100),action_name varchar(100),can_maker_checker"
            + " boolean)");
    migrate("059-create-cashier-reconciliation-schema.xml");
    migrate("068-create-end-of-day-closing-schema.xml");
    migrate("069-separate-closing-settlement.xml");
    for (String table :
        List.of(
            "m_loan_transaction",
            "m_client_transaction",
            "m_account_transfer_transaction",
            "m_payment_detail",
            "m_payment_type",
            "m_savings_account",
            "m_loan",
            "m_client")) {
      jdbc.execute("CREATE TABLE " + table + " (id bigint)");
    }
    jdbc.execute(
        "ALTER TABLE m_base_teller_deposit ADD COLUMN office_id bigint, ADD COLUMN operator_id"
            + " bigint");
    jdbc.execute(
        "ALTER TABLE m_base_teller_deposit_check_detail ADD COLUMN check_type varchar(50), ADD"
            + " COLUMN check_bank varchar(100), ADD COLUMN check_number varchar(100)");
    jdbc.execute(
        "ALTER TABLE m_base_teller_savings_opening ADD COLUMN office_id bigint DEFAULT 1, ADD"
            + " COLUMN operator_id bigint, ADD COLUMN check_type varchar(50), ADD COLUMN check_bank"
            + " varchar(100), ADD COLUMN check_number varchar(100)");
    jdbc.execute(
        "ALTER TABLE m_base_teller_credit_payment ADD COLUMN office_id bigint DEFAULT 1, ADD COLUMN"
            + " status varchar(30)");
    jdbc.execute(
        "ALTER TABLE m_base_teller_credit_payment_check ADD COLUMN check_type varchar(50), ADD"
            + " COLUMN bank_name_snapshot varchar(100), ADD COLUMN check_number varchar(100), ADD"
            + " COLUMN accepted_by bigint");
    jdbc.execute("CREATE TABLE m_service_payment(id bigint PRIMARY KEY)");
    jdbc.execute("CREATE TABLE m_base_teller_returned_check_payment(id bigint PRIMARY KEY)");
    jdbc.execute(
        "CREATE TABLE m_service_payment_denomination(id bigint PRIMARY KEY,identifier"
            + " varchar(100),currency_code varchar(3),denomination_type varchar(10),value"
            + " numeric(19,6),active boolean)");
    jdbc.update("INSERT INTO m_office VALUES(1,'Office','.1.'),(2,'Other','.2.')");
    jdbc.update("INSERT INTO m_staff VALUES(10,'Cashier'),(20,'Primary'),(30,'Other')");
    jdbc.update(
        "INSERT INTO m_tellers VALUES(1,1,'Teller',300,NULL,NULL),(2,2,'Other',300,NULL,NULL)");
    jdbc.update(
        "INSERT INTO m_cashiers VALUES(1,1,10,NULL,NULL),(2,1,20,NULL,NULL),(3,2,30,NULL,NULL)");
    jdbc.update("INSERT INTO m_appuser VALUES(1,10,'cashier'),(2,20,'primary')");
    jdbc.update(
        "INSERT INTO m_organisation_currency VALUES('AAA','Currency A',2),('BBB','Currency B',2)");
    jdbc.update(
        "INSERT INTO m_service_payment_denomination"
            + " VALUES(1,'bill','AAA','BILL',100,true),(2,'coin','BBB','COIN',1,true),(3,'disabled','AAA','BILL',50,false)");
    jdbc.update(
        "INSERT INTO"
            + " m_cashier_transactions(id,cashier_id,txn_type,txn_date,txn_amount,created_date,currency_code)"
            + " VALUES(1,1,101,?,1000,CURRENT_TIMESTAMP,'AAA')",
        date);
    actor = mock(AppUser.class);
    when(actor.getId()).thenReturn(1L);
    when(actor.getStaffId()).thenReturn(10L);
    var primary = mock(AppUser.class);
    when(primary.getId()).thenReturn(2L);
    when(primary.getUsername()).thenReturn("primary");
    var office = mock(Office.class);
    when(office.getHierarchy()).thenReturn(".1.");
    when(actor.getOffice()).thenReturn(office);
    when(primary.getOffice()).thenReturn(office);
    var security = mock(PlatformSecurityContext.class);
    when(security.authenticatedUser()).thenReturn(actor);
    var teller = mock(TellerManagementReadPlatformService.class);
    var cashier =
        CashierData.instance(
            1L, 1L, "Office", 10L, "Cashier", 1L, "Teller", "", null, null, true, null, null);
    when(teller.findCashier(1L)).thenReturn(cashier);
    when(teller.retrieveCashierTransactionsWithSummary(
            anyLong(), anyBoolean(), isNull(), isNull(), anyString(), any()))
        .thenAnswer(
            call -> {
              BigDecimal nativeCash =
                  jdbc.queryForObject(
                      "SELECT COALESCE(SUM(CASE WHEN txn_type IN (101,103) THEN txn_amount ELSE"
                          + " -txn_amount END),0) FROM m_cashier_transactions WHERE cashier_id=?"
                          + " AND currency_code=?",
                      BigDecimal.class,
                      call.getArgument(0),
                      call.getArgument(4));
              return org.apache.fineract.organisation.teller.data.CashierTransactionsWithSummaryData
                  .instance(
                      null,
                      nativeCash,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      "Office",
                      1L,
                      "Teller",
                      1L,
                      "Cashier");
            });
    commands = mock(PortfolioCommandSourceWritePlatformService.class);
    when(commands.logCommandSource(any()))
        .thenAnswer(
            call -> {
              var wrapper =
                  (org.apache.fineract.commands.domain.CommandWrapper) call.getArgument(0);
              var body =
                  com.google.gson.JsonParser.parseString(wrapper.getJson()).getAsJsonObject();
              Long nativeId =
                  jdbc.queryForObject(
                      "SELECT COALESCE(MAX(id),899)+1 FROM m_cashier_transactions WHERE id>=900",
                      Long.class);
              jdbc.update(
                  "INSERT INTO"
                      + " m_cashier_transactions(id,cashier_id,txn_type,txn_date,txn_amount,created_date,currency_code)"
                      + " VALUES(?,1,102,?,?,CURRENT_TIMESTAMP,?)",
                  nativeId,
                  date,
                  body.get("txnAmount").getAsBigDecimal(),
                  body.get("currencyCode").getAsString());
              var result = mock(CommandProcessingResult.class);
              when(result.getResourceId()).thenReturn(1L);
              when(result.getSubResourceId()).thenReturn(nativeId);
              when(result.getTransactionId()).thenReturn("native-test");
              return result;
            });
    var named = new NamedParameterJdbcTemplate(ds);
    var inventory = new CashInventoryReadPlatformServiceImpl(named, security);
    var closing =
        new CashManagementPlatformServiceImpl(
            jdbc,
            named,
            security,
            teller,
            new CashManagementValidator(),
            commands,
            new org.apache.fineract.infrastructure.core.domain.FineractRequestContextHolder());
    authorization = mock(EndOfDayClosingAuthorization.class);
    when(authorization.authorize(anyString(), anyString(), any(), any(), any(), anyList()))
        .thenReturn(primary);
    service =
        new EndOfDayClosingPlatformService(
            jdbc, named, security, inventory, closing, teller, authorization);
  }

  @AfterEach
  void cleanup() {
    ThreadLocalContextUtil.reset();
  }

  private void migrate(String file) throws Exception {
    try (var connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      var db =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try (var l =
          new Liquibase(
              "db/changelog/tenant/module/savings/parts/" + file,
              new ClassLoaderResourceAccessor(),
              db)) {
        l.update(new Contexts(), new LabelExpression());
      }
    }
  }

  private EndOfDayClosingRequest request(int bills, String key) {
    return new EndOfDayClosingRequest(
        1L,
        date.toString(),
        key,
        List.of(
            new CurrencyDeclaration(
                "AAA",
                List.of(new Quantity("bill", BigDecimal.valueOf(bills))),
                BigDecimal.ZERO,
                List.of()),
            new CurrencyDeclaration("BBB", List.of(), BigDecimal.ZERO, List.of())));
  }

  private EndOfDayClosingData complete(EndOfDayClosingRequest r) {
    return tx.execute(status -> service.complete(r, "primary", "test-only-secret"));
  }

  @Test
  void previewDoesNotPersistOrSettle() {
    var p = service.preview(request(9, "preview"));
    assertEquals("PREVIEW", p.status());
    assertEquals(0, p.balancesByCurrency().get(0).shortage().compareTo(new BigDecimal("100")));
    assertEquals(
        0, jdbc.queryForObject("SELECT COUNT(*) FROM m_end_of_day_closing", Integer.class));
    verifyNoInteractions(commands);
  }

  @Test
  void completeIsIdempotentAndReportSnapshotSurvivesMutableNames() {
    var r = request(10, "complete");
    var result = complete(r);
    assertEquals("COMPLETED", result.status());
    assertEquals(2L, result.authorizedBy());
    assertEquals(result, complete(r));
    assertEquals(
        1, jdbc.queryForObject("SELECT COUNT(*) FROM m_end_of_day_closing", Integer.class));
    assertEquals(
        2, jdbc.queryForObject("SELECT COUNT(*) FROM m_cashier_reconciliation", Integer.class));
    verify(commands, times(1)).logCommandSource(any());
    jdbc.update("UPDATE m_tellers SET name='Changed'");
    assertEquals(result, service.retrieve(result.id(), true));
    assertFalse(
        jdbc.queryForObject("SELECT snapshot FROM m_end_of_day_closing", String.class)
            .contains("test-only-secret"));
    assertThrows(RuntimeException.class, () -> complete(request(9, "complete")));
    assertThrows(RuntimeException.class, () -> complete(request(10, "other")));
  }

  @Test
  void physicalOverageSettlesSystemBalanceOnly() {
    var result = complete(physicalDeclaration(1050, "overage"));
    var b = result.balancesByCurrency().get(0);
    assertEquals("COMPLETED", result.status());
    assertEquals("OVERAGE", b.reconciliationStatus());
    assertEquals(0, b.overage().compareTo(new BigDecimal("50")));
    assertEquals(0, b.shortage().signum());
    assertEquals(0, b.settledCash().compareTo(new BigDecimal("1000")));
    assertEquals(
        0,
        jdbc.queryForObject(
                "SELECT settled_cash_total FROM m_cashier_reconciliation WHERE currency_code='AAA'",
                BigDecimal.class)
            .compareTo(new BigDecimal("1000")));
    assertEquals(
        0,
        jdbc.queryForObject(
                "SELECT txn_amount FROM m_cashier_transactions WHERE id=900", BigDecimal.class)
            .compareTo(new BigDecimal("1000")));
    assertEquals(result, service.retrieve(result.id(), true));
  }

  @Test
  void physicalShortageSettlesSystemBalanceOnly() {
    var result = complete(physicalDeclaration(950, "shortage"));
    var b = result.balancesByCurrency().get(0);
    assertEquals("SHORTAGE", b.reconciliationStatus());
    assertEquals(0, b.shortage().compareTo(new BigDecimal("50")));
    assertEquals(0, b.overage().signum());
    assertEquals(0, b.settledCash().compareTo(new BigDecimal("1000")));
    assertEquals(
        0,
        jdbc.queryForObject(
                "SELECT txn_amount FROM m_cashier_transactions WHERE id=900", BigDecimal.class)
            .compareTo(new BigDecimal("1000")));
  }

  @Test
  void invalidAuthorizationWithEitherDiscrepancyPostsNothing() {
    when(authorization.authorize(anyString(), anyString(), any(), any(), any(), anyList()))
        .thenThrow(new IllegalArgumentException("Denied"));
    for (int amount : new int[] {1050, 950}) {
      assertThrows(
          RuntimeException.class, () -> complete(physicalDeclaration(amount, "denied-" + amount)));
      assertEquals(
          0, jdbc.queryForObject("SELECT COUNT(*) FROM m_end_of_day_closing", Integer.class));
      assertEquals(
          0, jdbc.queryForObject("SELECT COUNT(*) FROM m_cashier_reconciliation", Integer.class));
    }
    verifyNoInteractions(commands);
  }

  @Test
  void mixedCurrenciesKeepPhysicalDiscrepanciesSeparate() {
    jdbc.update(
        "INSERT INTO"
            + " m_cashier_transactions(id,cashier_id,txn_type,txn_date,txn_amount,created_date,currency_code)"
            + " VALUES(2,1,101,?,500,CURRENT_TIMESTAMP,'BBB')",
        date);
    var r = physicalDeclaration(1050, "mixed");
    var result =
        complete(
            new EndOfDayClosingRequest(
                1L,
                date.toString(),
                "mixed",
                List.of(
                    r.currencies().get(0),
                    new CurrencyDeclaration(
                        "BBB",
                        List.of(new Quantity("coin", new BigDecimal("475"))),
                        BigDecimal.ZERO,
                        List.of()))));
    assertEquals(0, result.balancesByCurrency().get(0).overage().compareTo(new BigDecimal("50")));
    assertEquals(0, result.balancesByCurrency().get(1).shortage().compareTo(new BigDecimal("25")));
    assertEquals(
        0, result.balancesByCurrency().get(1).settledCash().compareTo(new BigDecimal("500")));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_cashier_transactions WHERE txn_type=102", Integer.class));
  }

  private EndOfDayClosingRequest physicalDeclaration(int amount, String key) {
    jdbc.update(
        "INSERT INTO m_service_payment_denomination VALUES(4,'unit','AAA','COIN',1,true) ON"
            + " CONFLICT(id) DO NOTHING");
    var r = request(0, key);
    return new EndOfDayClosingRequest(
        1L,
        date.toString(),
        key,
        List.of(
            new CurrencyDeclaration(
                "AAA",
                List.of(new Quantity("unit", BigDecimal.valueOf(amount))),
                BigDecimal.ZERO,
                List.of()),
            r.currencies().get(1)));
  }

  @Test
  void failedAuthorizationCommitsNothing() {
    when(authorization.authorize(anyString(), anyString(), any(), any(), any(), anyList()))
        .thenThrow(new IllegalArgumentException("Denied"));
    assertThrows(RuntimeException.class, () -> complete(request(10, "invalid")));
    assertEquals(
        0, jdbc.queryForObject("SELECT COUNT(*) FROM m_end_of_day_closing", Integer.class));
    verifyNoInteractions(commands);
  }

  @Test
  void nativeFailureRollsBackEveryCurrencyAndSnapshot() {
    doThrow(new IllegalStateException("Native failed")).when(commands).logCommandSource(any());
    assertThrows(RuntimeException.class, () -> complete(request(10, "failure")));
    assertEquals(
        0, jdbc.queryForObject("SELECT COUNT(*) FROM m_end_of_day_closing", Integer.class));
    assertEquals(
        0, jdbc.queryForObject("SELECT COUNT(*) FROM m_cashier_reconciliation", Integer.class));
  }

  @Test
  void stalePreviewUsesCurrentCustody() {
    var r = request(10, "stale");
    assertEquals("BALANCED", service.preview(r).balancesByCurrency().get(0).reconciliationStatus());
    jdbc.update("UPDATE m_cashier_transactions SET txn_amount=1100 WHERE id=1");
    var result = complete(r);
    assertEquals("SHORTAGE", result.balancesByCurrency().get(0).reconciliationStatus());
    assertEquals(0, result.balancesByCurrency().get(0).shortage().compareTo(new BigDecimal("100")));
  }

  @Test
  void disabledDenominationAndOtherCashierAreRejected() {
    assertThrows(RuntimeException.class, () -> service.checks(3L, null));
    var r = request(10, "bad");
    var c =
        new CurrencyDeclaration(
            "AAA", List.of(new Quantity("disabled", BigDecimal.ONE)), BigDecimal.ZERO, List.of());
    assertThrows(
        RuntimeException.class,
        () ->
            service.preview(
                new EndOfDayClosingRequest(
                    1L, date.toString(), "bad", List.of(c, r.currencies().get(1)))));
  }

  @Test
  void pendingDepositOpeningAndCreditChecksAreSelectedOnceAndMovedToVault() {
    jdbc.update(
        "INSERT INTO"
            + " m_base_teller_deposit(id,cashier_id,currency_code,funding_type,status,amount,created_on_utc,office_id,operator_id)"
            + " VALUES(1,1,'AAA','CHECK','PENDING_COLLECTION',20,?,1,1)",
        java.sql.Timestamp.valueOf(date.atStartOfDay()));
    jdbc.update(
        "INSERT INTO"
            + " m_base_teller_deposit_check_detail(id,deposit_id,amount,check_type,check_bank,check_number)"
            + " VALUES(1,1,20,'PERSONAL','Bank','deposit')");
    jdbc.update(
        "INSERT INTO"
            + " m_base_teller_savings_opening(id,cashier_id,currency_code,funding_type,status,amount,created_on_utc,operator_id,check_type,check_bank,check_number)"
            + " VALUES(1,1,'AAA','CHECK','COMPLETED',30,?,1,'PERSONAL','Bank','opening')",
        java.sql.Timestamp.valueOf(date.atStartOfDay()));
    jdbc.update(
        "INSERT INTO m_base_teller_credit_payment(id,cashier_id,currency_code,amount,status)"
            + " VALUES(1,1,'AAA',50,'PENDING_COLLECTION')");
    jdbc.update(
        "INSERT INTO"
            + " m_base_teller_credit_payment_check(id,credit_payment_id,status,accepted_on_utc,check_type,bank_name_snapshot,check_number,accepted_by)"
            + " VALUES(1,1,'PENDING_COLLECTION',?,'PERSONAL','Bank','credit',1)",
        java.sql.Timestamp.valueOf(date.atStartOfDay()));
    var checks = service.checks(1L, "AAA");
    assertEquals(3, checks.size());
    var r = request(10, "checks");
    var a =
        new CurrencyDeclaration(
            "AAA",
            r.currencies().get(0).denominations(),
            BigDecimal.ZERO,
            checks.stream().map(c -> new CheckSelection(c.source(), c.id())).toList());
    var result =
        complete(
            new EndOfDayClosingRequest(
                1L, date.toString(), "checks", List.of(a, r.currencies().get(1))));
    assertEquals("BALANCED", result.balancesByCurrency().get(0).reconciliationStatus());
    assertEquals(
        3, jdbc.queryForObject("SELECT COUNT(*) FROM m_end_of_day_closing_check", Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject("SELECT COUNT(*) FROM m_cashier_reconciliation_check", Integer.class));
    assertTrue(service.checks(1L, "AAA").isEmpty());
    assertEquals(
        "PENDING_COLLECTION",
        jdbc.queryForObject(
            "SELECT status FROM m_base_teller_credit_payment_check WHERE id=1", String.class));
  }

  @Test
  void checkChangedAfterPreviewCannotCompleteAndVouchersStaySeparate() {
    jdbc.update(
        "INSERT INTO m_base_teller_credit_payment(id,cashier_id,currency_code,amount,status)"
            + " VALUES(1,1,'AAA',50,'PENDING_COLLECTION')");
    jdbc.update(
        "INSERT INTO"
            + " m_base_teller_credit_payment_check(id,credit_payment_id,status,accepted_on_utc,check_type,bank_name_snapshot,check_number,accepted_by)"
            + " VALUES(1,1,'PENDING_COLLECTION',?,'PERSONAL','Bank','credit',1)",
        java.sql.Timestamp.valueOf(date.atStartOfDay()));
    var r = request(9, "stale-check");
    var a =
        new CurrencyDeclaration(
            "AAA",
            r.currencies().get(0).denominations(),
            new BigDecimal("100"),
            List.of(new CheckSelection("CREDIT", 1L)));
    var request =
        new EndOfDayClosingRequest(
            1L, date.toString(), "stale-check", List.of(a, r.currencies().get(1)));
    assertEquals(
        "BALANCED", service.preview(request).balancesByCurrency().get(0).reconciliationStatus());
    jdbc.update(
        "UPDATE m_base_teller_credit_payment_check SET status='RETURNED',returned_on_utc=? WHERE"
            + " id=1",
        java.sql.Timestamp.valueOf(date.atStartOfDay()));
    assertThrows(RuntimeException.class, () -> complete(request));
    assertEquals(
        0, jdbc.queryForObject("SELECT COUNT(*) FROM m_end_of_day_closing", Integer.class));
    verifyNoInteractions(commands);
  }

  @Test
  void vouchersArePersistedAndReportedWithoutNativeCashPosting() {
    var r = request(9, "vouchers");
    var a =
        new CurrencyDeclaration(
            "AAA", r.currencies().get(0).denominations(), new BigDecimal("100"), List.of());
    var result =
        complete(
            new EndOfDayClosingRequest(
                1L, date.toString(), "vouchers", List.of(a, r.currencies().get(1))));
    assertEquals("BALANCED", result.balancesByCurrency().get(0).reconciliationStatus());
    assertEquals(
        0,
        jdbc.queryForObject(
                "SELECT voucher_total FROM m_cashier_reconciliation WHERE currency_code='AAA'",
                BigDecimal.class)
            .compareTo(new BigDecimal("100")));
    assertEquals(
        0,
        jdbc.queryForObject(
                "SELECT txn_amount FROM m_cashier_transactions WHERE id=900", BigDecimal.class)
            .compareTo(new BigDecimal("1000")));
  }

  @Test
  void concurrentIdenticalSubmissionsReturnOneClosing() throws Exception {
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    var ready = new java.util.concurrent.CountDownLatch(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.Callable<EndOfDayClosingData> task =
        () -> {
          ThreadLocalContextUtil.setBusinessDates(
              new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, date)));
          ready.countDown();
          start.await();
          try {
            return complete(request(10, "concurrent"));
          } finally {
            ThreadLocalContextUtil.reset();
          }
        };
    try {
      var first = executor.submit(task);
      var second = executor.submit(task);
      assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
      start.countDown();
      assertEquals(
          first.get(20, java.util.concurrent.TimeUnit.SECONDS),
          second.get(20, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(
          1, jdbc.queryForObject("SELECT COUNT(*) FROM m_end_of_day_closing", Integer.class));
      verify(commands, times(1)).logCommandSource(any());
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void historicalOwnerSurvivesAssignmentChangesAndOtherStaffCannotReadReport() {
    var result = complete(request(10, "owner"));
    jdbc.update("UPDATE m_cashiers SET staff_id=20 WHERE id=1");
    assertEquals(result, service.retrieve(result.id(), true));
    when(actor.getStaffId()).thenReturn(20L);
    assertThrows(RuntimeException.class, () -> service.retrieve(result.id(), true));
  }

  private void dropAndCreateSchema() {
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    jdbc.execute("CREATE TABLE m_base_teller_withdrawal(id bigint PRIMARY KEY,cashier_id bigint,currency_code varchar(3),amount numeric(19,6),method varchar(10),status varchar(20),business_date date)");
    jdbc.execute("CREATE TABLE m_cash_drawer_pickup(id bigint PRIMARY KEY,business_date date,status varchar(20))");
    jdbc.execute("CREATE TABLE m_cash_teller_transfer_currency(id bigint PRIMARY KEY,transfer_id bigint,currency_code varchar(3),business_date date,status varchar(20),source_cashier_id bigint,destination_cashier_id bigint,cash_total numeric(19,6),source_cashier_transaction_id bigint,destination_cashier_transaction_id bigint)");
    jdbc.execute("CREATE TABLE m_cash_teller_transfer_denomination(id bigint PRIMARY KEY,transfer_currency_id bigint,denomination_identifier varchar(100),denomination_type varchar(10),denomination_value numeric(19,6),quantity bigint,line_total numeric(19,6))");
    jdbc.execute("CREATE TABLE m_cash_drawer_pickup_currency(id bigint PRIMARY KEY,pickup_id bigint,currency_code varchar(3),source_cashier_transaction_id bigint,destination_cashier_transaction_id bigint)");
    jdbc.execute("CREATE TABLE m_cash_drawer_pickup_check(id bigint PRIMARY KEY,pickup_currency_id bigint,source_type varchar(10),source_id bigint,source_cashier_id bigint,destination_cashier_id bigint,amount numeric(19,6))");
    jdbc.execute("CREATE TABLE m_cash_drawer_pickup_cash(id bigint PRIMARY KEY)");
    jdbc.execute(
        "CREATE TABLE m_transaction_reversal(id bigint PRIMARY KEY,cashier_id bigint,business_date"
            + " date,currency_code varchar(3),cash_effect numeric(19,6),check_effect"
            + " numeric(19,6),reversed_at timestamp)");
    jdbc.execute(
        "CREATE TABLE m_transaction_reversal_check(id bigint,source_type"
            + " varchar(50),source_check_id bigint)");
    jdbc.execute("CREATE TABLE m_transaction_reversal_denomination(id bigint)");
    jdbc.execute("CREATE TABLE m_cash_exchange(id bigint)");
    jdbc.execute("CREATE TABLE m_cash_exchange_detail(id bigint)");
    jdbc.execute("CREATE TABLE m_cash_exchange_mutex(id bigint PRIMARY KEY)");
    jdbc.update("INSERT INTO m_cash_exchange_mutex VALUES(1)");
    jdbc.execute(
        "CREATE TABLE m_office(id BIGINT PRIMARY KEY,name VARCHAR(100),hierarchy VARCHAR(100))");
    jdbc.execute("CREATE TABLE m_staff(id BIGINT PRIMARY KEY,display_name VARCHAR(100))");
    jdbc.execute(
        "CREATE TABLE m_tellers(id BIGINT PRIMARY KEY,office_id BIGINT,name VARCHAR(100),"
            + "state INT,valid_from DATE,valid_to DATE)");
    jdbc.execute(
        "CREATE TABLE m_cashiers(id BIGINT PRIMARY KEY,teller_id BIGINT,staff_id BIGINT,"
            + "start_date DATE,end_date DATE)");
    jdbc.execute(
        "CREATE TABLE m_appuser(id BIGINT PRIMARY KEY,staff_id BIGINT,username VARCHAR(100))");
    jdbc.execute(
        "CREATE TABLE m_organisation_currency(code VARCHAR(3),name VARCHAR(100),decimal_places"
            + " INT)");
    jdbc.execute(
        "CREATE TABLE m_cashier_transactions(id BIGINT PRIMARY KEY,cashier_id BIGINT,txn_type INT,"
            + "txn_date DATE,txn_amount NUMERIC(19,6),created_date TIMESTAMP,txn_note VARCHAR(100),"
            + "entity_type VARCHAR(100),entity_id BIGINT,currency_code VARCHAR(3))");
    jdbc.execute(
        "CREATE TABLE m_cash_allocation(id BIGINT PRIMARY KEY,operation_type VARCHAR(40),"
            + "status VARCHAR(20),business_date DATE,office_id BIGINT,currency_code VARCHAR(3),"
            + "amount NUMERIC(19,6),source_cashier_transaction_id BIGINT,"
            + "destination_cashier_transaction_id BIGINT)");
    jdbc.execute(
        "CREATE TABLE m_cash_operation_transaction(id BIGINT PRIMARY KEY,transaction_type"
            + " VARCHAR(30),business_date DATE,currency_code VARCHAR(3),cash_total"
            + " NUMERIC(19,6),office_id BIGINT,cashier_id BIGINT,status"
            + " VARCHAR(30),completed_on_utc TIMESTAMP,cashier_transaction_id BIGINT)");
    jdbc.execute(
        "CREATE TABLE m_cashier_reconciliation(id BIGINT PRIMARY KEY,business_date"
            + " DATE,currency_code VARCHAR(3),cash_total NUMERIC(19,6),office_id BIGINT,status"
            + " VARCHAR(30),cashier_id BIGINT,completed_on_utc TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE m_savings_account_transaction(id BIGINT PRIMARY KEY,transaction_date DATE)");
    jdbc.execute(
        "CREATE TABLE m_base_teller_deposit(id BIGINT PRIMARY KEY,cashier_id BIGINT,"
            + "currency_code VARCHAR(3),funding_type VARCHAR(20),status VARCHAR(30),"
            + "amount NUMERIC(19,6),created_on_utc TIMESTAMP,savings_transaction_id BIGINT)");
    jdbc.execute(
        "CREATE TABLE m_base_teller_savings_opening(id BIGINT PRIMARY KEY,cashier_id"
            + " BIGINT,currency_code VARCHAR(3),funding_type VARCHAR(20),status VARCHAR(30),amount"
            + " NUMERIC(19,6),created_on_utc TIMESTAMP,initial_deposit_transaction_id BIGINT)");
    jdbc.execute(
        "CREATE TABLE m_base_teller_deposit_check_detail(id BIGINT PRIMARY KEY,deposit_id BIGINT,"
            + "amount NUMERIC(19,6))");
    jdbc.execute(
        "CREATE TABLE m_base_teller_credit_payment(id BIGINT PRIMARY KEY,cashier_id BIGINT,"
            + "currency_code VARCHAR(3),amount NUMERIC(19,6))");
    jdbc.execute(
        "CREATE TABLE m_base_teller_credit_payment_check(id BIGINT PRIMARY KEY,"
            + "credit_payment_id BIGINT,status VARCHAR(30),accepted_on_utc TIMESTAMP,"
            + "returned_on_utc TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE m_cash_operation_check(id BIGINT PRIMARY KEY,operation_id BIGINT,"
            + "deposit_check_detail_id BIGINT,amount NUMERIC(19,6))");
    jdbc.execute(
        "CREATE TABLE m_end_of_day_closing_check(id BIGINT PRIMARY KEY,reconciliation_id"
            + " BIGINT,source_type VARCHAR(10),currency_code VARCHAR(3),amount NUMERIC(19,6))");
    jdbc.execute(
        "CREATE TABLE m_cashier_reconciliation_check(id BIGINT PRIMARY KEY,reconciliation_id"
            + " BIGINT,deposit_check_detail_id BIGINT,amount NUMERIC(19,6))");
    jdbc.execute(
        "CREATE TABLE m_base_teller_returned_check(id BIGINT PRIMARY KEY,cashier_id"
            + " BIGINT,currency_code VARCHAR(3),amount NUMERIC(19,6),returned_on_date DATE,status"
            + " VARCHAR(30),deposit_check_detail_id BIGINT)");
  }
}
