package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import org.apache.fineract.baseteller.data.CashInventoryData;
import org.apache.fineract.baseteller.data.CashInventoryType;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CashInventoryReadPlatformServiceIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  private JdbcTemplate jdbc;
  private CashInventoryReadPlatformServiceImpl service;
  private LocalDate date;

  @BeforeEach
  void setUp() {
    final DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    dropAndCreateSchema();
    final HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
    businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
    ThreadLocalContextUtil.setBusinessDates(businessDates);
    date = DateUtils.getBusinessLocalDate();
    seedOrganization();

    final Office office = mock(Office.class);
    when(office.getId()).thenReturn(1L);
    when(office.getName()).thenReturn("Branch A");
    when(office.getHierarchy()).thenReturn(".1.");
    final AppUser user = mock(AppUser.class);
    when(user.getOffice()).thenReturn(office);
    when(user.getStaffId()).thenReturn(null);
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    when(context.authenticatedUser()).thenReturn(user);
    service =
        new CashInventoryReadPlatformServiceImpl(
            new NamedParameterJdbcTemplate(dataSource), context);
  }

  @Test
  void openingInflowsOutflowsAndCutoffRespectBalanceInvariant() {
    cashierTransaction(1, 10, 101, "50000.00", "MXN");
    cashierTransaction(2, 10, 103, "20000.00", "MXN");
    cashierTransaction(3, 10, 104, "15000.00", "MXN");
    cashierTransaction(4, 10, 102, "10000.00", "MXN");

    final CashInventoryData row = only(service.inventory("TELLER:10", "CASH", "MXN", true));

    assertEquals(new BigDecimal("50000.00"), row.initialBalance());
    assertEquals(new BigDecimal("20000.00"), row.accumulatedInflows());
    assertEquals(new BigDecimal("15000.00"), row.accumulatedOutflows());
    assertEquals(new BigDecimal("10000.00"), row.cutOffs());
    assertEquals(new BigDecimal("45000.00"), row.balance());
    assertNotNull(row.lastCutOffAt());
    assertEquals(new BigDecimal("10000.00"), row.lastCutOffAmount());
    assertNotNull(row.asOf());
  }

  @Test
  void showLastCutoffFalseHidesMetadataWithoutChangingBalance() {
    cashierTransaction(1, 10, 101, "50000.00", "MXN");
    cashierTransaction(2, 10, 102, "10000.00", "MXN");

    final CashInventoryData row = only(service.inventory("TELLER:10", "CASH", "MXN", false));

    assertEquals(new BigDecimal("40000.00"), row.balance());
    assertEquals(new BigDecimal("10000.00"), row.cutOffs());
    assertNull(row.lastCutOffAt());
    assertNull(row.lastCutOffAmount());
  }

  @Test
  void cashChecksAndCurrenciesRemainSeparateAndFiltersWork() {
    cashierTransaction(1, 10, 101, "50.00", "MXN");
    cashierTransaction(2, 10, 101, "25.00", "USD");
    jdbc.update(
        "INSERT INTO m_base_teller_deposit"
            + " (id,cashier_id,currency_code,funding_type,status,amount,created_on_utc)"
            + " VALUES (1,10,'MXN','CHECK','COMPLETED',30,CURRENT_TIMESTAMP)");
    jdbc.update(
        "INSERT INTO m_base_teller_deposit_check_detail"
            + " (id,deposit_id,amount) VALUES (1,1,30)");

    final List<CashInventoryData> all = service.inventory("TELLER:10", null, null, false);

    assertEquals(3, all.size());
    assertEquals(
        2,
        all.stream().filter(row -> row.inventoryType() == CashInventoryType.CASH).count());
    assertEquals(
        1,
        all.stream().filter(row -> row.inventoryType() == CashInventoryType.CHECK).count());
    assertEquals(1, service.inventory(null, "CHECK", "MXN", false).size());
    assertEquals(1, service.inventory(null, "CASH", "USD", false).size());
  }

  @Test
  void transferIsOutflowNotCutoffAndConservesBranchInventory() {
    jdbc.update(
        "INSERT INTO m_cash_allocation"
            + " (id,operation_type,status,business_date,office_id,currency_code,amount)"
            + " VALUES (1,'SAFE_VAULT_OPENING','COMPLETED',?,1,'MXN',100000),"
            + " (2,'HEAD_CASHIER_ALLOCATION','COMPLETED',?,1,'MXN',50000),"
            + " (3,'OPERATIONAL_TELLER_ALLOCATION','COMPLETED',?,1,'MXN',5000)",
        date,
        date,
        date);
    cashierTransaction(1, 10, 101, "50000.00", "MXN");
    cashierTransaction(2, 10, 102, "5000.00", "MXN");
    cashierTransaction(3, 11, 101, "5000.00", "MXN");
    jdbc.update(
        "UPDATE m_cash_allocation SET source_cashier_transaction_id=2,"
            + " destination_cashier_transaction_id=3 WHERE id=3");

    final List<CashInventoryData> rows = service.inventory(null, "CASH", "MXN", false);
    final CashInventoryData source = row(rows, "TELLER:10");

    assertEquals(new BigDecimal("5000.00"), source.accumulatedOutflows());
    assertEquals(new BigDecimal("0.00"), source.cutOffs());
    assertEquals(
        new BigDecimal("100000.00"),
        rows.stream().map(CashInventoryData::balance).reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  @Test
  void contextAndRequestsCannotEscapeOfficeHierarchy() {
    assertFalse(
        service.context().custodians().stream()
            .anyMatch(custodian -> custodian.key().equals("TELLER:20")));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> service.inventory("TELLER:20", null, null, false));
  }

  @Test
  void rejectsInvalidTypeAndUnsupportedCurrencyAndReturnsEmptyResult() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> service.inventory(null, "COIN", null, false));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> service.inventory(null, null, "EUR", false));
    assertEquals(List.of(), service.inventory(null, null, null, false));
  }

  private void seedOrganization() {
    jdbc.update("INSERT INTO m_office VALUES (1,'Branch A','.1.'),(2,'Branch B','.2.')");
    jdbc.update("INSERT INTO m_staff VALUES (100,'Teller A'),(101,'Teller B'),(200,'Other')");
    jdbc.update(
        "INSERT INTO m_tellers VALUES (1,1,'Window A',300,NULL,NULL),"
            + "(2,1,'Window B',300,NULL,NULL),(3,2,'Window C',300,NULL,NULL)");
    jdbc.update(
        "INSERT INTO m_cashiers VALUES (10,1,100,NULL,NULL),(11,2,101,NULL,NULL),"
            + "(20,3,200,NULL,NULL)");
    jdbc.update(
        "INSERT INTO m_appuser VALUES (1000,100,'teller-a'),(1001,101,'teller-b'),"
            + "(2000,200,'other')");
    jdbc.update(
        "INSERT INTO m_organisation_currency VALUES ('MXN','Mexican Peso',2),"
            + "('USD','US Dollar',2)");
  }

  private void cashierTransaction(
      final long id,
      final long cashierId,
      final int type,
      final String amount,
      final String currency) {
    jdbc.update(
        "INSERT INTO m_cashier_transactions"
            + " (id,cashier_id,txn_type,txn_date,txn_amount,created_date,currency_code)"
            + " VALUES (?,?,?,?,?,CURRENT_TIMESTAMP,?)",
        id,
        cashierId,
        type,
        date,
        new BigDecimal(amount),
        currency);
  }

  private static CashInventoryData only(final List<CashInventoryData> rows) {
    assertEquals(1, rows.size());
    return rows.get(0);
  }

  private static CashInventoryData row(
      final List<CashInventoryData> rows, final String custodianKey) {
    return rows.stream()
        .filter(value -> value.custodianKey().equals(custodianKey))
        .findFirst()
        .orElseThrow();
  }

  private void dropAndCreateSchema() {
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    jdbc.execute("CREATE TABLE m_office(id BIGINT PRIMARY KEY,name VARCHAR(100),hierarchy VARCHAR(100))");
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
        "CREATE TABLE m_organisation_currency(code VARCHAR(3),name VARCHAR(100),decimal_places INT)");
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
        "CREATE TABLE m_cash_operation_transaction(id BIGINT PRIMARY KEY,transaction_type VARCHAR(30),"
            + "business_date DATE,currency_code VARCHAR(3),cash_total NUMERIC(19,6),office_id BIGINT,"
            + "cashier_id BIGINT,status VARCHAR(30),completed_on_utc TIMESTAMP,"
            + "cashier_transaction_id BIGINT)");
    jdbc.execute(
        "CREATE TABLE m_cashier_reconciliation(id BIGINT PRIMARY KEY,business_date DATE,"
            + "currency_code VARCHAR(3),cash_total NUMERIC(19,6),office_id BIGINT,status VARCHAR(30),"
            + "completed_on_utc TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE m_savings_account_transaction(id BIGINT PRIMARY KEY,transaction_date DATE)");
    jdbc.execute(
        "CREATE TABLE m_base_teller_deposit(id BIGINT PRIMARY KEY,cashier_id BIGINT,"
            + "currency_code VARCHAR(3),funding_type VARCHAR(20),status VARCHAR(30),"
            + "amount NUMERIC(19,6),created_on_utc TIMESTAMP,savings_transaction_id BIGINT)");
    jdbc.execute(
        "CREATE TABLE m_base_teller_savings_opening(id BIGINT PRIMARY KEY,cashier_id BIGINT,"
            + "currency_code VARCHAR(3),funding_type VARCHAR(20),status VARCHAR(30),"
            + "amount NUMERIC(19,6),created_on_utc TIMESTAMP,initial_deposit_transaction_id BIGINT)");
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
        "CREATE TABLE m_cashier_reconciliation_check(id BIGINT PRIMARY KEY,reconciliation_id BIGINT,"
            + "deposit_check_detail_id BIGINT,amount NUMERIC(19,6))");
    jdbc.execute(
        "CREATE TABLE m_base_teller_returned_check(id BIGINT PRIMARY KEY,cashier_id BIGINT,"
            + "currency_code VARCHAR(3),amount NUMERIC(19,6),returned_on_date DATE,status VARCHAR(30),"
            + "deposit_check_detail_id BIGINT)");
  }
}
