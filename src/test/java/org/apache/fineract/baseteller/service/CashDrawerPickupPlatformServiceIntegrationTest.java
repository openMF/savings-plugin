package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.baseteller.data.CashDrawerPickupRequest;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CashDrawerPickupPlatformServiceIntegrationTest {
  @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");
  private final LocalDate date = LocalDate.of(2026, 10, 14);
  private JdbcTemplate jdbc;
  private CashDrawerPickupPlatformService service;
  private TransactionTemplate tx;
  private CashExchangeInventory inventory;
  private PortfolioCommandSourceWritePlatformService commands;
  private AuthenticationProvider authentication;

  @BeforeEach
  void setup() throws Exception {
    var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, date)));
    jdbc.execute("CREATE TABLE m_office(id bigint PRIMARY KEY,name varchar(100),hierarchy varchar(100))");
    jdbc.execute("CREATE TABLE m_staff(id bigint PRIMARY KEY,display_name varchar(100))");
    jdbc.execute("CREATE TABLE m_tellers(id bigint PRIMARY KEY,office_id bigint,name varchar(100),state int,valid_from date,valid_to date)");
    jdbc.execute("CREATE TABLE m_cashiers(id bigint PRIMARY KEY,teller_id bigint,staff_id bigint,start_date date,end_date date)");
    jdbc.execute("CREATE TABLE m_appuser(id bigint PRIMARY KEY)");
    jdbc.execute("CREATE TABLE m_permission(id bigserial PRIMARY KEY,grouping varchar(100),code varchar(100) UNIQUE,entity_name varchar(100),action_name varchar(100),can_maker_checker boolean)");
    try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try (var liquibase = new Liquibase("db/changelog/tenant/module/savings/parts/071-create-cash-drawer-pickup-schema.xml", new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());
      }
    }
    jdbc.execute("CREATE TABLE m_cash_exchange_mutex(id bigint PRIMARY KEY)");
    jdbc.execute("CREATE TABLE m_cash_allocation(id bigint PRIMARY KEY,operation_type varchar(50),status varchar(20),business_date date,office_id bigint,destination_cashier_id bigint,currency_code varchar(3))");
    jdbc.execute("CREATE TABLE m_cashier_reconciliation(id bigint PRIMARY KEY,cashier_id bigint,business_date date)");
    jdbc.execute("CREATE TABLE m_service_payment_denomination(id bigint PRIMARY KEY,identifier varchar(100),denomination_type varchar(10),value numeric(19,6),currency_code varchar(3),active boolean)");
    jdbc.execute("CREATE TABLE m_organisation_currency(code varchar(3),decimal_places int)");
    jdbc.execute("CREATE TABLE m_client(id bigint PRIMARY KEY,display_name varchar(100))");
    jdbc.execute("CREATE TABLE m_base_teller_deposit(id bigint PRIMARY KEY,cashier_id bigint,office_id bigint,client_id bigint,currency_code varchar(3),status varchar(30),created_on_utc timestamp)");
    jdbc.execute("CREATE TABLE m_base_teller_deposit_check_detail(id bigint PRIMARY KEY,deposit_id bigint,check_bank varchar(100),check_number varchar(100),amount numeric(19,6),clearing_status varchar(30))");
    jdbc.execute("CREATE TABLE m_base_teller_savings_opening(id bigint PRIMARY KEY,cashier_id bigint,office_id bigint,client_id bigint,currency_code varchar(3),funding_type varchar(10),status varchar(30),created_on_utc timestamp,check_bank varchar(100),check_number varchar(100),amount numeric(19,6))");
    jdbc.execute("CREATE TABLE m_base_teller_credit_payment(id bigint PRIMARY KEY,cashier_id bigint,office_id bigint,client_id bigint,currency_code varchar(3),status varchar(30),amount numeric(19,6))");
    jdbc.execute("CREATE TABLE m_base_teller_credit_payment_check(id bigint PRIMARY KEY,credit_payment_id bigint,status varchar(30),accepted_on_utc timestamp,bank_name_snapshot varchar(100),check_number varchar(100))");
    jdbc.execute("CREATE TABLE m_base_teller_returned_check(id bigint PRIMARY KEY,deposit_check_detail_id bigint,status varchar(30))");
    jdbc.execute("CREATE TABLE m_cashier_reconciliation_check(id bigint PRIMARY KEY,deposit_check_detail_id bigint)");
    jdbc.execute("CREATE TABLE m_cash_operation_check(id bigint PRIMARY KEY,deposit_check_detail_id bigint)");
    jdbc.execute("CREATE TABLE m_transaction_reversal_check(id bigint PRIMARY KEY,source_type varchar(50),source_check_id bigint)");
    jdbc.execute("CREATE TABLE m_end_of_day_closing_check(id bigint PRIMARY KEY,source_type varchar(10),source_id bigint)");
    for (String name : List.of("m_cash_allocation_denomination", "m_cashier_transactions", "m_cash_operation_transaction", "m_service_payment", "m_savings_account_transaction", "m_cash_exchange", "m_cash_exchange_detail", "m_transaction_reversal")) jdbc.execute("CREATE TABLE " + name + "(id bigint PRIMARY KEY)");
    jdbc.update("INSERT INTO m_office VALUES(1,'Office','.1.')");
    jdbc.update("INSERT INTO m_staff VALUES(10,'Base'),(20,'Head')");
    jdbc.update("INSERT INTO m_tellers VALUES(1,1,'Base',300,NULL,NULL),(2,1,'Head',300,NULL,NULL)");
    jdbc.update("INSERT INTO m_cashiers VALUES(1,1,10,NULL,NULL),(2,2,20,NULL,NULL)");
    jdbc.update("INSERT INTO m_appuser VALUES(1),(2)");
    jdbc.update("INSERT INTO m_cash_exchange_mutex VALUES(1)");
    jdbc.update("INSERT INTO m_cash_allocation VALUES(1,'HEAD_CASHIER_ALLOCATION','COMPLETED',?,1,2,'AAA')",date);
    jdbc.update("INSERT INTO m_organisation_currency VALUES('AAA',2)");
    jdbc.update("INSERT INTO m_service_payment_denomination VALUES(1,'bill','NOTE',100,'AAA',true)");
    jdbc.update("INSERT INTO m_client VALUES(1,'Client')");
    jdbc.update("INSERT INTO m_base_teller_deposit VALUES(1,1,1,1,'AAA','PENDING_COLLECTION',?)",java.sql.Timestamp.valueOf(date.atStartOfDay()));
    jdbc.update("INSERT INTO m_base_teller_deposit_check_detail VALUES(1,1,'Bank','Ref',20,'SUBJECT_TO_COLLECTION')");

    var office = mock(Office.class);
    when(office.getHierarchy()).thenReturn(".1.");
    var actor = mock(AppUser.class);
    when(actor.getId()).thenReturn(1L);
    when(actor.getStaffId()).thenReturn(10L);
    when(actor.getOffice()).thenReturn(office);
    var authorizer = mock(AppUser.class);
    when(authorizer.getId()).thenReturn(2L);
    when(authorizer.getStaffId()).thenReturn(20L);
    when(authorizer.getUsername()).thenReturn("head");
    when(authorizer.getOffice()).thenReturn(office);
    when(authorizer.hasAnyPermission("AUTHORIZE_BASE_TELLER_CASH_DRAWER_PICKUP")).thenReturn(true);
    var security = mock(PlatformSecurityContext.class);
    when(security.authenticatedUser()).thenReturn(actor);
    authentication = mock(AuthenticationProvider.class);
    Authentication verified = UsernamePasswordAuthenticationToken.authenticated(authorizer, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    when(authentication.authenticate(any())).thenReturn(verified);
    inventory = mock(CashExchangeInventory.class);
    when(inventory.quantities(1L,"AAA",date)).thenReturn(Map.of("bill",4L));
    when(inventory.quantities(2L,"AAA",date)).thenReturn(Map.of("bill",1L));
    commands = mock(PortfolioCommandSourceWritePlatformService.class);
    var movement = mock(CommandProcessingResult.class);
    when(movement.getSubResourceId()).thenReturn(100L,101L);
    when(commands.logCommandSource(any())).thenReturn(movement);
    service = new CashDrawerPickupPlatformService(jdbc,security,inventory,commands,authentication,new org.apache.fineract.infrastructure.core.domain.FineractRequestContextHolder());
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  private CashDrawerPickupRequest request(String key,long quantity,boolean check) {
    return new CashDrawerPickupRequest(1L,2L,key,List.of(new CashDrawerPickupRequest.Currency("AAA",
        List.of(new CashDrawerPickupRequest.Quantity("bill",BigDecimal.valueOf(quantity))),
        check ? List.of(new CashDrawerPickupRequest.Check("DEPOSIT",1L)) : List.of())));
  }

  @Test
  void previewIsReadOnlyAndCreateTransfersOnceWithHistoricalReceipt() {
    var preview = tx.execute(x -> service.preview(request("key",2,true)));
    assertEquals(new BigDecimal("220.000000"),preview.currencies().get(0).totalToCollect());
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM m_cash_drawer_pickup",Integer.class));
    var first = tx.execute(x -> service.create(request("key",2,true),"head","test-only-secret"));
    var second = tx.execute(x -> service.create(request("key",2,true),"head","test-only-secret"));
    assertEquals(first,second);
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM m_cash_drawer_pickup",Integer.class));
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM m_cash_drawer_pickup_check",Integer.class));
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM m_cash_drawer_pickup_cash",Integer.class));
    assertEquals(1L,jdbc.queryForObject("SELECT source_cashier_id FROM m_cash_drawer_pickup_check",Long.class));
    assertEquals(2L,jdbc.queryForObject("SELECT destination_cashier_id FROM m_cash_drawer_pickup_check",Long.class));
    assertEquals("SUBJECT_TO_COLLECTION",jdbc.queryForObject("SELECT clearing_status FROM m_base_teller_deposit_check_detail WHERE id=1",String.class));
    assertEquals(first,service.receipt(first.id()));
    assertFalse(first.toString().contains("test-only-secret"));
    verify(commands,times(2)).logCommandSource(any());
  }

  @Test
  void insufficientCashAndDuplicateCheckLeaveCustodyUnchanged() {
    assertThrows(RuntimeException.class,() -> tx.execute(x -> service.create(request("too-much",5,false),"head","secret")));
    var duplicate = new CashDrawerPickupRequest(1L,2L,"duplicate",List.of(new CashDrawerPickupRequest.Currency("AAA",List.of(),
        List.of(new CashDrawerPickupRequest.Check("DEPOSIT",1L),new CashDrawerPickupRequest.Check("DEPOSIT",1L)))));
    assertThrows(RuntimeException.class,() -> tx.execute(x -> service.create(duplicate,"head","secret")));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM m_cash_drawer_pickup",Integer.class));
    verifyNoInteractions(commands);
  }

  @Test
  void checkOnlyPickupDoesNotRequireCashComposition() {
    when(inventory.quantities(anyLong(), eq("AAA"), eq(date))).thenThrow(new IllegalStateException("unknown cash"));
    var request = new CashDrawerPickupRequest(1L,2L,"check-only",List.of(
        new CashDrawerPickupRequest.Currency("AAA",List.of(),List.of(new CashDrawerPickupRequest.Check("DEPOSIT",1L)))));
    var receipt = tx.execute(x -> service.create(request,"head","test-only-secret"));
    assertEquals(new BigDecimal("20.000000"),receipt.currencies().get(0).totalToCollect());
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM m_cash_drawer_pickup_check",Integer.class));
    verifyNoInteractions(commands);
  }

  @Test
  void disabledDenominationAndWrongCurrencyAreRejected() {
    jdbc.update("UPDATE m_service_payment_denomination SET active=false WHERE identifier='bill'");
    assertThrows(RuntimeException.class,() -> tx.execute(x -> service.preview(request("disabled",1,false))));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM m_cash_drawer_pickup",Integer.class));
  }

  @Test
  void authorizerMustOwnSelectedHeadCashier() {
    var wrong = mock(AppUser.class);
    when(wrong.getId()).thenReturn(3L);
    when(wrong.getStaffId()).thenReturn(99L);
    when(wrong.hasAnyPermission("AUTHORIZE_BASE_TELLER_CASH_DRAWER_PICKUP")).thenReturn(true);
    var verified = UsernamePasswordAuthenticationToken.authenticated(wrong,null,List.of(new SimpleGrantedAuthority("ROLE_USER")));
    when(authentication.authenticate(any())).thenReturn(verified);
    assertThrows(RuntimeException.class,() -> tx.execute(x -> service.create(request("wrong-head",2,true),"wrong","secret")));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM m_cash_drawer_pickup",Integer.class));
    verifyNoInteractions(commands);
  }

  @Test
  void currenciesKeepTotalsSeparate() {
    jdbc.update("INSERT INTO m_organisation_currency VALUES('BBB',2)");
    jdbc.update("INSERT INTO m_service_payment_denomination VALUES(2,'coin','COIN',1,'BBB',true)");
    jdbc.update("INSERT INTO m_cash_allocation VALUES(2,'HEAD_CASHIER_ALLOCATION','COMPLETED',?,1,2,'BBB')",date);
    when(inventory.quantities(1L,"BBB",date)).thenReturn(Map.of("coin",5L));
    when(inventory.quantities(2L,"BBB",date)).thenReturn(Map.of("coin",1L));
    var multi = new CashDrawerPickupRequest(1L,2L,null,List.of(
        new CashDrawerPickupRequest.Currency("AAA",List.of(new CashDrawerPickupRequest.Quantity("bill",BigDecimal.valueOf(2))),List.of()),
        new CashDrawerPickupRequest.Currency("BBB",List.of(new CashDrawerPickupRequest.Quantity("coin",BigDecimal.valueOf(3))),List.of())));
    var preview = tx.execute(x -> service.preview(multi));
    assertEquals(2,preview.currencies().size());
    assertEquals(new BigDecimal("200.000000"),preview.currencies().get(0).cash());
    assertEquals(new BigDecimal("3.000000"),preview.currencies().get(1).cash());
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM m_cash_drawer_pickup",Integer.class));
  }

  @Test
  void invalidHeadCredentialLeavesNoPickup() {
    when(authentication.authenticate(any())).thenThrow(new org.springframework.security.authentication.BadCredentialsException("invalid"));
    assertThrows(RuntimeException.class,() -> tx.execute(x -> service.create(request("invalid",2,true),"head","wrong")));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM m_cash_drawer_pickup",Integer.class));
    verifyNoInteractions(commands);
  }
}
