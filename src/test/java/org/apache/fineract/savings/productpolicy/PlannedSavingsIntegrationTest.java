/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Real two-tenant PostgreSQL migrations, constraints, rollback and races. No financial posts. */
class PlannedSavingsIntegrationTest {
  MainAccountIntegrationTest base;
  ProductPolicyWorkflowIntegrationTest fixture;
  JdbcTemplate jdbc;
  PlannedSavingsService arrangements;
  SavingsPocketService pockets;
  PlannedSavingsAccess access;
  static final String ID = "f9bbc4ca-276d-49e4-b5f2-555672b0b011";
  static final String REVIEW = "f9bbc4ca-276d-49e4-b5f2-555672b0b012";
  static final LocalDate DAY = ProductPolicyWorkflowIntegrationTest.DAY;

  @BeforeAll
  static void database() throws Exception {
    MainAccountIntegrationTest.database();
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      j.execute("ALTER TABLE m_client ADD COLUMN legal_form_enum integer DEFAULT 1");
      j.update(
          "INSERT INTO m_client(id,office_id,legal_form_enum) VALUES(10,1,2),(11,1,2),(12,2,2)");
      j.execute("ALTER TABLE m_savings_account ADD COLUMN sub_status_enum integer DEFAULT 0");
      j.execute("ALTER TABLE m_savings_account ADD COLUMN currency_code varchar(3) DEFAULT 'MXN'");
      j.execute("ALTER TABLE m_savings_account ADD COLUMN currency_digits integer DEFAULT 2");
      for (var column :
          List.of(
              "account_balance_derived",
              "total_interest_earned_derived",
              "total_interest_posted_derived"))
        j.execute(
            "ALTER TABLE m_savings_account ADD COLUMN " + column + " numeric(19,6) DEFAULT 0");
      j.update("INSERT INTO m_savings_account(id) VALUES(904),(905),(906),(907)");
      var product = j.queryForMap("SELECT * FROM m_savings_product WHERE id=1");
      product.put("id", 5L);
      j.update(
          "INSERT INTO m_savings_product("
              + String.join(",", product.keySet())
              + ") VALUES ("
              + String.join(",", Collections.nCopies(product.size(), "?"))
              + ")",
          product.values().toArray());
      migrate(ds);
      migrate(ds);
      assertEquals(
          4,
          j.queryForObject(
              "SELECT COUNT(*) FROM m_permission WHERE code IN ('READ_PLANNED_SAVINGS','CONFIGURE_PLANNED_SAVINGS','MANAGE_SAVINGS_POCKET','REVIEW_PLANNED_SAVINGS')",
              Integer.class));
    }
  }

  // Separate helper allows checking that an already applied migration is harmless on repetition.
  static void migrate(javax.sql.DataSource ds) throws Exception {
    try (var connection = ds.getConnection()) {
      var db =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try (var lb =
          new Liquibase(
              "db/changelog/tenant/module/savings/parts/089-planned-savings-pockets.xml",
              new ClassLoaderResourceAccessor(),
              db)) {
        lb.validate();
        lb.update(new Contexts(), new LabelExpression());
      }
    }
  }

  @BeforeEach
  void setup() {
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      for (var table :
          List.of(
              "m_savings_planned_review",
              "m_savings_planned_audit",
              "m_savings_planned_contribution",
              "m_savings_pocket_relationship",
              "m_savings_planned_arrangement")) j.update("DELETE FROM " + table);
    }
    base = new MainAccountIntegrationTest();
    base.setup();
    fixture = base.fixture;
    jdbc = fixture.jdbc;
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      j.update("UPDATE m_client SET legal_form_enum=CASE WHEN id>=10 THEN 2 ELSE 1 END");
      j.update(
          "UPDATE m_savings_account SET sub_status_enum=0,currency_code='MXN',currency_digits=2,account_balance_derived=0,total_interest_earned_derived=0,total_interest_posted_derived=0");
      j.update("UPDATE m_savings_account SET client_id=1,product_id=2 WHERE id IN(904,905)");
      j.update("UPDATE m_savings_account SET client_id=2,product_id=2 WHERE id=906");
      j.update("UPDATE m_savings_account SET client_id=1,product_id=5 WHERE id=907");
    }
    var nativeAccounts = mock(SavingsAccountRepositoryWrapper.class);
    when(nativeAccounts.findOneWithNotFoundDetection(anyLong()))
        .thenAnswer(
            call -> {
              long id = call.getArgument(0);
              var row = jdbc.queryForMap("SELECT * FROM m_savings_account WHERE id=?", id);
              var a = mock(SavingsAccount.class);
              when(a.getId()).thenReturn(id);
              when(a.groupId()).thenReturn(null);
              when(a.clientId()).thenReturn(((Number) row.get("client_id")).longValue());
              when(a.productId()).thenReturn(((Number) row.get("product_id")).longValue());
              when(a.getCurrency())
                  .thenReturn(
                      new MonetaryCurrency(
                          (String) row.get("currency_code"),
                          ((Number) row.get("currency_digits")).intValue(),
                          1));
              when(a.getWithdrawableBalance())
                  .thenReturn((BigDecimal) row.get("account_balance_derived"));
              when(a.isActive()).thenReturn(((Number) row.get("status_enum")).intValue() == 300);
              return a;
            });
    var core = new ProductPolicyCoreSnapshot(jdbc);
    access =
        spy(
            new PlannedSavingsAccess(
                fixture.security,
                jdbc,
                nativeAccounts,
                new ProductTransactionPolicyResolver(jdbc, fixture.workflow, core),
                fixture.workflow,
                core,
                new ProductPolicyValidation()));
    var audit = new PlannedSavingsAuditService(jdbc);
    arrangements = proxy(new PlannedSavingsService(jdbc, access, audit, fixture.workflow));
    pockets =
        proxy(
            new SavingsPocketService(
                jdbc,
                access,
                fixture.workflow,
                base.service,
                new ProductTransactionPolicyEvaluator(),
                audit));
  }

  @SuppressWarnings("unchecked")
  <T> T proxy(T target) {
    var factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(jdbc.getDataSource()),
            new AnnotationTransactionAttributeSource()));
    return (T) factory.getProxy();
  }

  void planned() {
    base.approve(5, ProductPolicyValidationTest.JSON.replace("\"MAIN\"", "\"PLANNED_MAIN\""));
  }

  void pocketPolicy() {
    base.prepare();
    base.approve(
        2,
        ProductPolicyValidationTest.JSON
            .replace("\"MAIN\"", "\"POCKET\"")
            .replace("\"SINGLE\"", "\"UNLIMITED\""));
    for (long id : List.of(904L, 905L, 906L)) base.bindPolicy(id, 2, "POCKET");
  }

  String pocket(long revision) {
    return "{\"expectedRevision\":"
        + revision
        + ",\"mainSavingsAccountId\":901,\"policyVersion\":1,\"mainPolicyVersion\":1,\"name\":\"Holiday\"}";
  }

  String review(String key, String kind) {
    return "{\"expectedRevision\":1,\"idempotencyKey\":\""
        + key
        + "\",\"kind\":\""
        + kind
        + "\",\"reason\":\"Proposed review\"}";
  }

  long count(String table) {
    return base.count(table);
  }

  @Test
  void configurationHasVersionedSchedulesAndAuditWithoutCreatingNativeAccounts() {
    planned();
    var before = jdbc.queryForList("SELECT * FROM m_savings_account ORDER BY id");
    var a = arrangements.save(1, ID, PlannedSavingsValidationTest.JSON);
    assertEquals(1, a.revision());
    assertNull(a.savingsAccountId());
    assertFalse(a.financialExecutionEnabled());
    assertEquals("DUE_UNVERIFIED", arrangements.schedule(1, ID, 0, 0, 100).getFirst().status());
    assertEquals(5, arrangements.schedule(1, ID, 0, 0, 100).size());
    assertEquals(2, arrangements.schedule(1, ID, 0, 2, 2).size());
    assertThrows(
        PlannedSavingsService.Conflict.class,
        () -> arrangements.save(1, ID, PlannedSavingsValidationTest.JSON));
    var updated =
        arrangements.save(
            1,
            ID,
            PlannedSavingsValidationTest.JSON
                .replace("\"expectedRevision\":0", "\"expectedRevision\":1")
                .replace("123.45", "200.50"));
    assertEquals(2, updated.revision());
    assertEquals(
        0,
        new BigDecimal("123.45")
            .compareTo(arrangements.schedule(1, ID, 1, 0, 100).getFirst().expectedAmount()));
    assertEquals(
        0,
        new BigDecimal("200.50")
            .compareTo(arrangements.schedule(1, ID, 2, 0, 100).getFirst().expectedAmount()));
    assertEquals(2, arrangements.history(1, "ARRANGEMENT", ID, 0, 100).size());
    assertEquals(before, jdbc.queryForList("SELECT * FROM m_savings_account ORDER BY id"));
    assertEquals(1, arrangements.list(1, "", 100).size());
    assertThrows(
        PlatformApiDataValidationException.class, () -> arrangements.schedule(1, ID, 3, 0, 100));
  }

  @Test
  void employerPurposeTermPrecisionAndOwnershipAreValidated() {
    planned();
    for (String json :
        List.of(
            PlannedSavingsValidationTest.JSON.replace(
                "\"employerClientId\":10", "\"employerClientId\":1"),
            PlannedSavingsValidationTest.JSON.replace(
                "\"employerClientId\":10", "\"employerClientId\":2"),
            PlannedSavingsValidationTest.JSON.replace("123.45", "123.456"),
            PlannedSavingsValidationTest.JSON.replace("\"policyVersion\":1", "\"policyVersion\":2"),
            PlannedSavingsValidationTest.JSON.replace(
                "\"savingsProductId\":5", "\"savingsProductId\":1")))
      assertThrows(PlatformApiDataValidationException.class, () -> arrangements.save(1, ID, json));
    assertThrows(
        ClientNotFoundException.class,
        () ->
            arrangements.save(
                1,
                ID,
                PlannedSavingsValidationTest.JSON.replace(
                    "\"employerClientId\":10", "\"employerClientId\":12")));
    arrangements.save(1, ID, PlannedSavingsValidationTest.JSON);
    assertThrows(PlatformApiDataValidationException.class, () -> arrangements.get(2, ID));
    assertThrows(ClientNotFoundException.class, () -> arrangements.get(3, ID));
    assertEquals(0, arrangements.list(2, "", 100).size());
    arrangements.save(
        2,
        UUID.randomUUID().toString(),
        PlannedSavingsValidationTest.JSON.replace(
            "\"employerClientId\":10", "\"employerClientId\":11"));
    assertEquals(2, count("m_savings_planned_arrangement"));
  }

  @Test
  void frozenPolicyCanBeProposedButCannotAuthorizeReleaseOrOverrideTerm() {
    base.approve(
        5,
        ProductPolicyValidationTest.JSON
            .replace("\"MAIN\"", "\"PLANNED_MAIN\"")
            .replace("\"freezing\":\"NONE\"", "\"freezing\":\"TERM\",\"freezingTermDays\":60"));
    arrangements.save(1, ID, PlannedSavingsValidationTest.JSON);
    assertFalse(arrangements.maturity(1, ID).executionEnabled());
    assertEquals(
        "BLOCKED_PENDING_POLICY",
        arrangements.review(1, ID, review(REVIEW, "EMPLOYEE_EXIT")).state());
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            arrangements.save(
                2,
                UUID.randomUUID().toString(),
                PlannedSavingsValidationTest.JSON
                    .replace("\"termDays\":60", "\"termDays\":59")
                    .replace("2026-12-31", "2026-12-30")));
    assertEquals(0, count("m_savings_account_policy"));
  }

  @Test
  void nativeLinkRequiresPreExistingBoundSameClientAccountAndDoesNotOpenOrBindOne() {
    planned();
    arrangements.save(1, ID, PlannedSavingsValidationTest.JSON);
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> arrangements.link(1, ID, "{\"expectedRevision\":1,\"savingsAccountId\":907}"));
    base.bindPolicy(907, 5, "PLANNED_MAIN");
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> arrangements.link(1, ID, "{\"expectedRevision\":1,\"savingsAccountId\":906}"));
    var linked = arrangements.link(1, ID, "{\"expectedRevision\":1,\"savingsAccountId\":907}");
    assertEquals(907, linked.savingsAccountId());
    assertEquals(2, linked.revision());
    assertEquals(5, arrangements.schedule(1, ID, 0, 0, 100).size());
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            arrangements.save(
                1,
                ID,
                PlannedSavingsValidationTest.JSON.replace(
                    "\"expectedRevision\":0", "\"expectedRevision\":2")));
    assertFalse(pockets.closure(1, 907).executionEnabled());
    jdbc.update("UPDATE m_savings_account SET client_id=2 WHERE id=907");
    assertThrows(PlatformApiDataValidationException.class, () -> arrangements.get(1, ID));
    assertEquals(2, arrangements.history(1, "ARRANGEMENT", ID, 0, 100).size());
  }

  @Test
  void maturityBeforeOnAfterAndWithdrawalReviewsStayBlockedAndIdempotent() {
    planned();
    arrangements.save(1, ID, PlannedSavingsValidationTest.JSON);
    assertEquals("BEFORE_MATURITY", arrangements.maturity(1, ID).state());
    ProductPolicyWorkflowIntegrationTest.setDay(LocalDate.of(2026, 12, 31));
    assertEquals("MATURITY_DUE_BLOCKED", arrangements.maturity(1, ID).state());
    var first = arrangements.review(1, ID, review(REVIEW, "MATURITY"));
    ProductPolicyWorkflowIntegrationTest.setDay(LocalDate.of(2027, 1, 1));
    assertEquals("PAST_MATURITY_BLOCKED", arrangements.maturity(1, ID).state());
    assertEquals(first, arrangements.review(1, ID, review(REVIEW, "MATURITY")));
    assertNull(first.nativeTransactionId());
    assertFalse(first.executionEnabled());
    assertThrows(
        PlannedSavingsService.Conflict.class,
        () -> arrangements.review(1, ID, review(REVIEW, "EMPLOYEE_EXIT")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> arrangements.review(1, ID, review(UUID.randomUUID().toString(), "EARLY_WITHDRAWAL")));
    assertEquals(
        "BLOCKED_PENDING_POLICY",
        arrangements
            .review(
                1,
                ID,
                review(UUID.randomUUID().toString(), "EARLY_WITHDRAWAL")
                    .replace("}", ",\"amount\":10}"))
            .state());
    assertEquals(2, count("m_savings_planned_review"));
    assertEquals("OVERDUE_UNVERIFIED", arrangements.schedule(1, ID, 0, 0, 100).getFirst().status());
    assertEquals(
        "one",
        arrangements.history(1, "ARRANGEMENT", ID, 0, 100).getFirst().get("tenant_identifier"));
  }

  @Test
  void multiplePocketsHaveSeparateNativeBalancesNamesAndImmutableMainRelationships() {
    pocketPolicy();
    pockets.save(1, 904, pocket(0));
    pockets.save(1, 905, pocket(0).replace("Holiday", "Emergency"));
    jdbc.update("UPDATE m_savings_account SET account_balance_derived=11 WHERE id=904");
    jdbc.update("UPDATE m_savings_account SET account_balance_derived=22 WHERE id=905");
    assertEquals(2, pockets.list(1, 0, 100).size());
    assertEquals(0, new BigDecimal("11").compareTo(pockets.get(1, 904).nativeAccount().balance()));
    assertEquals(0, new BigDecimal("22").compareTo(pockets.get(1, 905).nativeAccount().balance()));
    assertEquals("Emergency", pockets.get(1, 905).name());
    assertEquals(2, pockets.save(1, 904, pocket(1).replace("Holiday", "Travel")).revision());
    assertThrows(PlannedSavingsService.Conflict.class, () -> pockets.save(1, 904, pocket(0)));
    assertThrows(PlatformApiDataValidationException.class, () -> pockets.save(1, 906, pocket(0)));
    assertThrows(PlatformApiDataValidationException.class, () -> pockets.get(2, 904));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> pockets.save(1, 904, pocket(2).replace("901", "905")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> pockets.retire(1, 904, "{\"expectedRevision\":2}"));
    assertEquals(3, count("m_savings_planned_audit"));
  }

  @Test
  void frozenInsufficientInactiveAndZeroBalanceTransfersAndClosureAlwaysFailClosed() {
    pocketPolicy();
    pockets.save(1, 904, pocket(0));
    var preview = pockets.transferPreview(1, 904, new BigDecimal("10"));
    assertFalse(preview.executionEnabled());
    assertTrue(preview.blockers().contains("INSUFFICIENT_NATIVE_WITHDRAWABLE_FUNDS"));
    assertTrue(preview.blockers().contains("purpose.unsupported"));
    assertFalse(pockets.closure(1, 904).executionEnabled());
    jdbc.update(
        "UPDATE m_savings_account SET account_balance_derived=20,sub_status_enum=100,total_interest_earned_derived=1 WHERE id=904");
    assertTrue(pockets.closure(1, 904).blockers().contains("NATIVE_BALANCE_NOT_ZERO"));
    assertTrue(
        pockets
            .closure(1, 904)
            .blockers()
            .contains("UNPOSTED_INTEREST_OR_INTEREST_RECONCILIATION_UNVERIFIED"));
    assertFalse(pockets.transferPreview(1, 904, new BigDecimal("10")).executionEnabled());
    jdbc.update(
        "UPDATE m_savings_account SET account_balance_derived=0,status_enum=600 WHERE id=904");
    assertEquals(
        "RETIRED", pockets.retire(1, 904, "{\"expectedRevision\":1}").relationshipStatus());
    assertEquals(2, arrangements.history(1, "POCKET", "904", 0, 100).size());
    assertThrows(PlatformApiDataValidationException.class, () -> pockets.save(1, 904, pocket(2)));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> pockets.transferPreview(1, 904, new BigDecimal("0.001")));
  }

  @Test
  void tenantPermissionsBusinessDateAndOfficeScopeAreEnforced() {
    planned();
    arrangements.save(1, ID, PlannedSavingsValidationTest.JSON);
    ProductPolicyPersistenceIntegrationTest.tenant("two");
    assertEquals(0, arrangements.list(1, "", 100).size());
    assertEquals(0, arrangements.history(1, "ARRANGEMENT", ID, 0, 100).size());
    planned();
    arrangements.save(1, ID, PlannedSavingsValidationTest.JSON);
    assertEquals(
        "two",
        arrangements.history(1, "ARRANGEMENT", ID, 0, 100).getFirst().get("tenant_identifier"));
    ProductPolicyPersistenceIntegrationTest.tenant("one");
    var user = fixture.security.authenticatedUser();
    doThrow(new AccessDeniedException("Denied"))
        .when(user)
        .validateHasPermissionTo(PlannedSavingsAccess.READ);
    assertThrows(AccessDeniedException.class, () -> arrangements.get(1, ID));
    assertThrows(AccessDeniedException.class, () -> pockets.list(1, 0, 100));
    doThrow(new AccessDeniedException("Denied"))
        .when(user)
        .validateHasPermissionTo(PlannedSavingsAccess.CONFIGURE);
    assertThrows(
        AccessDeniedException.class,
        () ->
            arrangements.save(1, UUID.randomUUID().toString(), PlannedSavingsValidationTest.JSON));
    doThrow(new AccessDeniedException("Denied"))
        .when(user)
        .validateHasPermissionTo(PlannedSavingsAccess.POCKET);
    assertThrows(AccessDeniedException.class, () -> pockets.save(1, 904, pocket(0)));
    doThrow(new AccessDeniedException("Denied"))
        .when(user)
        .validateHasPermissionTo(PlannedSavingsAccess.REVIEW);
    assertThrows(
        AccessDeniedException.class, () -> arrangements.review(1, ID, review(REVIEW, "MATURITY")));
  }

  @Test
  void concurrentMetadataAndSettlementReviewsCannotDuplicateSchedulesOrAudit() throws Exception {
    planned();
    var results =
        race(
            () -> {
              try {
                arrangements.save(1, ID, PlannedSavingsValidationTest.JSON);
                return true;
              } catch (PlannedSavingsService.Conflict e) {
                return false;
              }
            });
    assertNotEquals(results.getFirst(), results.getLast());
    assertEquals(5, count("m_savings_planned_contribution"));
    var reviews = race(() -> arrangements.review(1, ID, review(REVIEW, "MATURITY")));
    assertEquals(reviews.getFirst(), reviews.getLast());
    assertEquals(1, count("m_savings_planned_review"));
    assertEquals(2, count("m_savings_planned_audit"));
    pocketPolicy();
    var binds =
        race(
            () -> {
              try {
                pockets.save(1, 904, pocket(0));
                return true;
              } catch (PlannedSavingsService.Conflict e) {
                return false;
              }
            });
    assertNotEquals(binds.getFirst(), binds.getLast());
    assertEquals(1, count("m_savings_pocket_relationship"));
  }

  <T> List<T> race(Callable<T> work) throws Exception {
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<T> worker =
          () -> {
            ProductPolicyPersistenceIntegrationTest.tenant("one");
            ProductPolicyWorkflowIntegrationTest.setDay(DAY);
            try {
              start.await();
              return work.call();
            } finally {
              ThreadLocalContextUtil.reset();
            }
          };
      var a = pool.submit(worker);
      var b = pool.submit(worker);
      start.countDown();
      return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    }
  }

  @Test
  void auditFailureRollsBackAllMetadataAndConstraintsPreserveHistory() {
    planned();
    ProductPolicyWorkflowIntegrationTest.ACTOR.set(999L);
    assertThrows(
        DataIntegrityViolationException.class,
        () -> arrangements.save(1, ID, PlannedSavingsValidationTest.JSON));
    assertEquals(0, count("m_savings_planned_arrangement"));
    assertEquals(0, count("m_savings_planned_contribution"));
    ProductPolicyWorkflowIntegrationTest.ACTOR.set(1L);
    arrangements.save(1, ID, PlannedSavingsValidationTest.JSON);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update("DELETE FROM m_savings_product_policy_version WHERE savings_product_id=5"));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE m_savings_planned_arrangement SET status='APPROVED'"));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE m_savings_planned_contribution SET expected_amount=-1"));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("DELETE FROM m_savings_planned_arrangement WHERE id=?", ID));
  }

  @Test
  void cancellingAProposalPreservesSchedulesAndCannotCancelOrReopenNativeAccounts() {
    planned();
    arrangements.save(1, ID, PlannedSavingsValidationTest.JSON);
    var cancel =
        PlannedSavingsValidationTest.JSON
            .replace("\"expectedRevision\":0", "\"expectedRevision\":1")
            .replace("CONFIGURATION_ONLY", "CANCELLED");
    assertEquals(
        PlannedSavingsData.ArrangementStatus.CANCELLED,
        arrangements.save(1, ID, cancel).configuration().status());
    assertEquals(
        "CANCELLED_CONFIGURATION", arrangements.schedule(1, ID, 1, 0, 100).getFirst().status());
    assertEquals(10, count("m_savings_planned_contribution"));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            arrangements.save(
                1,
                ID,
                PlannedSavingsValidationTest.JSON.replace(
                    "\"expectedRevision\":0", "\"expectedRevision\":2")));
    assertTrue(arrangements.maturity(1, ID).blockers().contains("CONFIGURATION_CANCELLED"));
  }

  @Test
  void pocketPurposeCurrencyPolicyDriftAndChangedMainOwnershipCannotBeAdopted() {
    pocketPolicy();
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> pockets.save(1, 901, pocket(0).replace("901", "904")));
    jdbc.update("UPDATE m_savings_account SET currency_code='USD' WHERE id=904");
    assertThrows(PlatformApiDataValidationException.class, () -> pockets.save(1, 904, pocket(0)));
    jdbc.update("UPDATE m_savings_account SET currency_code='MXN' WHERE id=904");
    jdbc.update("UPDATE m_savings_product SET nominal_annual_interest_rate=1 WHERE id=2");
    assertThrows(PlatformApiDataValidationException.class, () -> pockets.save(1, 904, pocket(0)));
    jdbc.update("UPDATE m_savings_product SET nominal_annual_interest_rate=0 WHERE id=2");
    pockets.save(1, 904, pocket(0));
    jdbc.update("UPDATE m_savings_account SET client_id=2 WHERE id=901");
    assertThrows(PlatformApiDataValidationException.class, () -> pockets.get(1, 904));
    assertEquals(1, arrangements.history(1, "POCKET", "904", 0, 100).size());
  }

  @Test
  void frozenPocketMetadataDoesNotGrantFinancialOrInterestCapability() {
    base.prepare();
    base.approve(
        2,
        ProductPolicyValidationTest.JSON
            .replace("\"MAIN\"", "\"POCKET\"")
            .replace("\"SINGLE\"", "\"UNLIMITED\"")
            .replace("\"freezing\":\"NONE\"", "\"freezing\":\"TERM\",\"freezingTermDays\":60"));
    base.bindPolicy(904, 2, "POCKET");
    pockets.save(1, 904, pocket(0));
    var snapshot = pockets.account(1, 904);
    assertTrue(snapshot.blockers().contains("FREEZING_ENFORCEMENT_UNVERIFIED"));
    assertFalse(pockets.transferPreview(1, 904, BigDecimal.ONE).executionEnabled());
    assertFalse(pockets.closure(1, 904).executionEnabled());
  }

  @Test
  void reviewPrecisionActorIdentityAndUnknownFinancialOptionsAreRejected() {
    planned();
    arrangements.save(1, ID, PlannedSavingsValidationTest.JSON);
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            arrangements.review(
                1, ID, review(REVIEW, "EARLY_WITHDRAWAL").replace("}", ",\"amount\":0.001}")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            arrangements.review(
                1, ID, review(REVIEW, "MATURITY").replace("}", ",\"releaseFunds\":true}")));
    arrangements.review(1, ID, review(REVIEW, "MATURITY"));
    ProductPolicyWorkflowIntegrationTest.ACTOR.set(2L);
    assertThrows(
        PlannedSavingsService.Conflict.class,
        () -> arrangements.review(1, ID, review(REVIEW, "MATURITY")));
    assertEquals(1, count("m_savings_planned_review"));
  }

  @Test
  void missingTenantDateAndOfficeAreRejectedBeforeRelationshipQueries() {
    var access = new PlannedSavingsAccess(fixture.security, jdbc, null, null, null, null, null);
    ThreadLocalContextUtil.clearTenant();
    assertThrows(AccessDeniedException.class, () -> access.authorize(PlannedSavingsAccess.READ));
    ProductPolicyPersistenceIntegrationTest.tenant("one");
    ThreadLocalContextUtil.setBusinessDates(new HashMap<>());
    assertThrows(AccessDeniedException.class, () -> access.authorize(PlannedSavingsAccess.READ));
    ProductPolicyWorkflowIntegrationTest.setDay(DAY);
    when(fixture.security.authenticatedUser().getOffice()).thenReturn(null);
    assertThrows(AccessDeniedException.class, () -> access.authorize(PlannedSavingsAccess.READ));
  }

  @Test
  void pocketsAreTenantIsolatedWithIdenticalNativeIdentifiers() {
    pocketPolicy();
    pockets.save(1, 904, pocket(0));
    ProductPolicyPersistenceIntegrationTest.tenant("two");
    assertEquals(0, pockets.list(1, 0, 100).size());
    assertThrows(PlatformApiDataValidationException.class, () -> pockets.get(1, 904));
    pocketPolicy();
    pockets.save(1, 904, pocket(0).replace("Holiday", "Tenant Two"));
    assertEquals("Tenant Two", pockets.get(1, 904).name());
    ProductPolicyPersistenceIntegrationTest.tenant("one");
    assertEquals("Holiday", pockets.get(1, 904).name());
  }

  @Test
  void linkingAfterAConfigurationLockWaitUsesTheLockedRevisionSchedule() throws Exception {
    planned();
    arrangements.save(1, ID, PlannedSavingsValidationTest.JSON);
    base.bindPolicy(907, 5, "PLANNED_MAIN");
    var enteredPolicy = new CountDownLatch(1);
    doAnswer(
            call -> {
              enteredPolicy.countDown();
              return call.callRealMethod();
            })
        .when(access)
        .policy(5, 1, ProductPolicyData.Purpose.PLANNED_MAIN);
    var updated =
        PlannedSavingsValidation.configuration(
                PlannedSavingsValidationTest.JSON.replace("123.45", "200.50"))
            .configuration();
    try (var worker = Executors.newSingleThreadExecutor()) {
      Future<PlannedSavingsData.Arrangement> linked =
          fixture.tx(
              () -> {
                fixture.workflow.lock(5);
                jdbc.update(
                    "UPDATE m_savings_planned_arrangement SET revision=2,configuration_json=? WHERE id=?",
                    RatePropagationJson.JSON.toJson(updated),
                    ID);
                int sequence = 0;
                for (var due : PlannedContributionSchedule.dates(updated))
                  jdbc.update(
                      "INSERT INTO m_savings_planned_contribution VALUES(?,2,?,?,?)",
                      ID,
                      ++sequence,
                      due,
                      updated.contributionAmount());
                var future =
                    worker.submit(
                        () -> {
                          ProductPolicyPersistenceIntegrationTest.tenant("one");
                          ProductPolicyWorkflowIntegrationTest.setDay(DAY);
                          try {
                            return arrangements.link(
                                1, ID, "{\"expectedRevision\":2,\"savingsAccountId\":907}");
                          } finally {
                            ThreadLocalContextUtil.reset();
                          }
                        });
                try {
                  assertTrue(enteredPolicy.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(e);
                }
                return future;
              });
      assertEquals(3, linked.get(20, TimeUnit.SECONDS).revision());
    }
    assertEquals(
        0,
        new BigDecimal("200.50")
            .compareTo(arrangements.schedule(1, ID, 3, 0, 100).getFirst().expectedAmount()));
    assertEquals(
        0,
        new BigDecimal("123.45")
            .compareTo(arrangements.schedule(1, ID, 1, 0, 100).getFirst().expectedAmount()));
  }

  @AfterEach
  void clear() {
    fixture.clear();
  }

  @AfterAll
  static void cleanup() {
    ProductPolicyPersistenceIntegrationTest.cleanup();
  }
}
