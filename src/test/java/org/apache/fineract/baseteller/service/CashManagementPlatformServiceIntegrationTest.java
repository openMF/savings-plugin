package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.baseteller.data.*;
import org.apache.fineract.baseteller.validation.CashManagementValidator;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.FineractRequestContextHolder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.teller.data.CashierData;
import org.apache.fineract.organisation.teller.data.TellerData;
import org.apache.fineract.organisation.teller.service.TellerManagementReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real PostgreSQL transactions, native row locks, migrations and uniqueness constraints. */
@Testcontainers
class CashManagementPlatformServiceIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

  private final LocalDate date = LocalDate.of(2026, 11, 26);
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private CashManagementPlatformServiceImpl service;
  private AppUser authorizer;
  private PortfolioCommandSourceWritePlatformService commands;

  @BeforeEach
  void setup() throws Exception {
    var ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    setDate();
    CashManagementTestSchema.create(jdbc);
    for (String table :
        List.of(
            "m_cashier_reconciliation_check",
            "m_cashier_reconciliation",
            "m_cash_operation_check",
            "m_cash_operation_transaction",
            "m_end_of_day_closing_check")) jdbc.execute("DROP TABLE " + table);
    jdbc.execute(
        "CREATE TABLE m_permission(id bigserial PRIMARY KEY, grouping varchar(100),code varchar(100) UNIQUE,entity_name varchar(100),action_name varchar(100),can_maker_checker boolean)");
    migrate("059-create-cashier-reconciliation-schema.xml");
    migrate("068-create-end-of-day-closing-schema.xml");
    migrate("069-separate-closing-settlement.xml");
    jdbc.execute("ALTER TABLE m_base_teller_deposit ADD COLUMN office_id bigint");
    jdbc.execute(
        "ALTER TABLE m_base_teller_deposit_check_detail ADD COLUMN check_bank varchar(100), ADD COLUMN check_number varchar(100), ADD COLUMN clearing_status varchar(30)");
    jdbc.update("INSERT INTO m_office VALUES(1,'Office','.1.'),(2,'Other','.2.')");
    jdbc.update("INSERT INTO m_staff VALUES(10,'Cashier'),(20,'Authorizer')");
    jdbc.update(
        "INSERT INTO m_tellers VALUES(1,1,'Teller',300,NULL,NULL),(2,2,'Other',300,NULL,NULL)");
    jdbc.update("INSERT INTO m_cashiers VALUES(1,1,10,NULL,NULL),(2,1,20,NULL,NULL)");
    jdbc.update("INSERT INTO m_appuser VALUES(1,10,'cashier'),(2,20,'authorizer')");
    jdbc.execute("CREATE TABLE m_currency(code varchar(3),decimal_places int)");
    jdbc.update("INSERT INTO m_currency VALUES('AAA',2),('BBB',2)");
    jdbc.update("INSERT INTO m_organisation_currency VALUES('AAA','A',2),('BBB','B',2)");
    authorizer = mock(AppUser.class);
    when(authorizer.getId()).thenReturn(2L);
    when(authorizer.getStaffId()).thenReturn(20L);
    var office = mock(Office.class);
    when(office.getHierarchy()).thenReturn(".1.");
    when(authorizer.getOffice()).thenReturn(office);
    var security = mock(PlatformSecurityContext.class);
    when(security.authenticatedUser()).thenReturn(authorizer);
    var tellers = mock(TellerManagementReadPlatformService.class);
    when(tellers.findCashier(1L))
        .thenReturn(
            CashierData.instance(
                1L, 1L, "Office", 10L, "Cashier", 1L, "Teller", "", null, null, true, null, null));
    var teller =
        TellerData.instance(
            1L, 1L, null, null, "Teller", "", null, null, null, "Office", false, true);
    when(tellers.findTeller(1L)).thenReturn(teller);
    commands = mock(PortfolioCommandSourceWritePlatformService.class);
    when(commands.logCommandSource(any()))
        .thenAnswer(
            call -> {
              var wrapper =
                  (org.apache.fineract.commands.domain.CommandWrapper) call.getArgument(0);
              var body =
                  com.google.gson.JsonParser.parseString(wrapper.getJson()).getAsJsonObject();
              jdbc.update(
                  "INSERT INTO m_cashier_transactions(id,cashier_id,txn_type,txn_date,txn_amount,currency_code) VALUES(900,1,102,?,?,?)",
                  date,
                  body.get("txnAmount").getAsBigDecimal(),
                  body.get("currencyCode").getAsString());
              var result = mock(CommandProcessingResult.class);
              when(result.getSubResourceId()).thenReturn(900L);
              when(result.getTransactionId()).thenReturn("accounting-test");
              return result;
            });
    service =
        new CashManagementPlatformServiceImpl(
            jdbc,
            new NamedParameterJdbcTemplate(ds),
            security,
            tellers,
            new CashManagementValidator(),
            commands,
            new FineractRequestContextHolder());
  }

  @AfterEach
  void cleanup() {
    ThreadLocalContextUtil.reset();
  }

  private void setDate() {
    ThreadLocalContextUtil.setBusinessDates(
        new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, date)));
  }

  private void migrate(String name) throws Exception {
    try (var connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      var db =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try (var l =
          new Liquibase(
              "db/changelog/tenant/module/savings/parts/" + name,
              new ClassLoaderResourceAccessor(),
              db)) {
        l.update(new Contexts(), new LabelExpression());
      }
    }
  }

  private void open() {
    jdbc.update(
        "INSERT INTO m_cashier_transactions(id,cashier_id,txn_type,txn_date,txn_amount,currency_code) VALUES(1,1,101,?,100,'AAA')",
        date);
    jdbc.update(
        "INSERT INTO m_cash_allocation(id,operation_type,status,business_date,office_id,currency_code,amount,destination_cashier_transaction_id,destination_cashier_id) VALUES(1,'OPERATIONAL_TELLER_ALLOCATION','COMPLETED',?,1,'AAA',100,1,1)",
        date);
  }

  private CashierClosingRequest request(String key, int quantity) {
    return new CashierClosingRequest(
        key,
        1L,
        date,
        "AAA",
        quantity == 0
            ? List.of()
            : List.of(new BaseTellerDenominationData("unit", BigDecimal.ONE, (long) quantity)),
        List.of());
  }

  private CashierClosingReceiptData close(String key, int quantity) {
    return tx.execute(status -> service.close(request(key, quantity)));
  }

  private void noSideEffects() {
    assertEquals(
        0, jdbc.queryForObject("SELECT COUNT(*) FROM m_cashier_reconciliation", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_cashier_reconciliation_denomination", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_cashier_transactions WHERE txn_type=102", Integer.class));
    verifyNoInteractions(commands);
  }

  @Test
  void unopenedZeroCashAndInflowsCannotAuthorizeClosing() {
    jdbc.update(
        "INSERT INTO m_cashier_transactions(id,cashier_id,txn_type,txn_date,txn_amount,currency_code) VALUES(1,1,103,?,100,'AAA')",
        date);
    assertEquals(
        CashierClosingStatus.NOT_OPENED, service.closingContext(1L, "AAA", date).registerStatus());
    var error =
        assertThrows(GeneralPlatformDomainRuleException.class, () -> close("never-opened", 0));
    assertTrue(error.getMessage().contains("has not been opened"));
    noSideEffects();
  }

  @Test
  void completedOpeningAllowsClosingAndPersistsReceiptStatus() {
    open();
    assertEquals(
        CashierClosingStatus.OPEN, service.closingContext(1L, "AAA", date).registerStatus());
    var receipt = close("valid", 100);
    assertEquals(CashManagementStatus.COMPLETED, receipt.status());
    assertEquals(0, BigDecimal.valueOf(100).compareTo(receipt.expectedAmount()));
    assertEquals(0, receipt.difference().signum());
    var refreshed = service.closingContext(1L, "AAA", date);
    assertEquals(CashierClosingStatus.COMPLETED, refreshed.registerStatus());
    assertEquals(receipt.id(), refreshed.closingId());
    verify(commands, times(1)).logCommandSource(any());
  }

  @Test
  void openedRegisterRemainsEligibleAtZeroBalance() {
    open();
    jdbc.update(
        "INSERT INTO m_cashier_transactions(id,cashier_id,txn_type,txn_date,txn_amount,currency_code) VALUES(2,1,104,?,100,'AAA')",
        date);
    assertEquals(0, service.closingContext(1L, "AAA", date).expectedAmount().signum());
    assertEquals(CashManagementStatus.COMPLETED, close("zero-balance", 0).status());
    verifyNoInteractions(commands);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "destination_cashier_id=2",
        "business_date=DATE '2026-11-25'",
        "currency_code='BBB'",
        "office_id=2",
        "status='PROCESSING'",
        "status='CANCELLED'",
        "status='REVERSED'",
        "operation_type='SAFE_VAULT_OPENING'",
        "destination_cashier_transaction_id=NULL"
      })
  void mismatchedOrIncompleteOpeningCannotClose(String mutation) {
    open();
    jdbc.update("UPDATE m_cash_allocation SET " + mutation);
    assertThrows(GeneralPlatformDomainRuleException.class, () -> close("invalid", 0));
    noSideEffects();
  }

  @Test
  void nativeAllocationWithoutOpeningRecordIsInsufficient() {
    open();
    jdbc.update("DELETE FROM m_cash_allocation");
    assertThrows(GeneralPlatformDomainRuleException.class, () -> close("native-only", 100));
    noSideEffects();
  }

  @Test
  void completedReplaySucceedsButDifferentKeyAndFingerprintFail() {
    open();
    var receipt = close("same", 100);
    assertEquals(receipt, close("same", 100));
    assertThrows(GeneralPlatformDomainRuleException.class, () -> close("new", 100));
    assertThrows(GeneralPlatformDomainRuleException.class, () -> close("same", 0));
    verify(commands, times(1)).logCommandSource(any());
    assertEquals(
        1, jdbc.queryForObject("SELECT COUNT(*) FROM m_cashier_reconciliation", Integer.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"CREATE_CASHIER_CLOSING", "AUTHORIZE_CASHIER_CLOSING"})
  void unauthorizedUsersProduceNoSideEffects(String permission) {
    open();
    doThrow(new IllegalStateException("forbidden"))
        .when(authorizer)
        .validateHasPermissionTo(permission);
    assertThrows(IllegalStateException.class, () -> close("unauthorized", 100));
    noSideEffects();
  }

  @Test
  void selfAuthorizationProducesNoSideEffects() {
    open();
    when(authorizer.getStaffId()).thenReturn(10L);
    assertThrows(GeneralPlatformDomainRuleException.class, () -> close("self", 100));
    noSideEffects();
  }

  @Test
  void commonPostingBoundaryRejectsFabricatedOpenPosition() {
    var position = service.closingContext(1L, "AAA", date);
    var cashier =
        CashierData.instance(
            1L, 1L, "Office", 10L, "Cashier", 1L, "Teller", "", null, null, true, null, null);
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            tx.execute(
                status ->
                    service.postClosing(
                        request("bypass", 0),
                        position,
                        cashier,
                        List.of(),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        authorizer,
                        authorizer)));
    noSideEffects();
  }

  @Test
  void concurrentDistinctClosingsProduceOnlyOneReconciliation() throws Exception {
    concurrent(false);
  }

  @Test
  void concurrentMatchingReplayReturnsTheSameReceipt() throws Exception {
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    concurrent(true);
  }

  @Test
  void authorizedHistoricalReplayPreservesTheCompletedReceipt() {
    open();
    var receipt = close("historical", 100);
    ThreadLocalContextUtil.setBusinessDates(
        new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, date.plusDays(1))));
    assertEquals(receipt, close("historical", 100));
    verify(commands, times(1)).logCommandSource(any());
  }

  private void concurrent(boolean sameKey) throws Exception {
    open();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var tasks = new ArrayList<Future<Object>>();
      for (int i = 0; i < 2; i++) {
        final String key = sameKey ? "shared" : "key-" + i;
        tasks.add(
            pool.submit(
                () -> {
                  setDate();
                  ready.countDown();
                  start.await();
                  try {
                    return close(key, 100);
                  } catch (GeneralPlatformDomainRuleException e) {
                    return e;
                  } finally {
                    ThreadLocalContextUtil.reset();
                  }
                }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      var first = tasks.get(0).get(20, TimeUnit.SECONDS);
      var second = tasks.get(1).get(20, TimeUnit.SECONDS);
      if (sameKey) assertEquals(first, second);
      else
        assertTrue(
            (first instanceof CashierClosingReceiptData)
                != (second instanceof CashierClosingReceiptData));
      assertEquals(
          1, jdbc.queryForObject("SELECT COUNT(*) FROM m_cashier_reconciliation", Integer.class));
      verify(commands, times(1)).logCommandSource(any());
    } finally {
      start.countDown();
      pool.shutdownNow();
    }
  }
}
