/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.concurrent.*;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Actual PostgreSQL, Liquibase, tenant routing and concurrent service transactions. */
class MonthlyFeeAssessmentIntegrationTest {
  ProductPolicyWorkflowIntegrationTest fixture;
  JdbcTemplate jdbc;
  MonthlyFeeAssessmentService service;
  MonthlyFeeEligibility eligibility;
  static final LocalDate START = LocalDate.of(2026, 10, 1), DAY = LocalDate.of(2026, 11, 8);

  @BeforeAll
  static void database() throws Exception {
    ProductPolicyWorkflowIntegrationTest.database();
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      j.execute(
          "ALTER TABLE m_savings_account ADD COLUMN product_id bigint, ADD COLUMN status_enum"
              + " integer DEFAULT 300, ADD COLUMN sub_status_enum integer DEFAULT 0, ADD COLUMN"
              + " deposit_type_enum integer DEFAULT 100, ADD COLUMN activatedon_date date DEFAULT"
              + " '2026-09-01', ADD COLUMN closedon_date date, ADD COLUMN currency_code varchar(3)"
              + " DEFAULT 'MXN', ADD COLUMN currency_digits integer DEFAULT 2, ADD COLUMN"
              + " account_balance_derived numeric DEFAULT 1000");
      j.update("INSERT INTO m_savings_account(id,product_id) VALUES(901,1),(902,1),(903,2)");
      for (int i = 0; i < 2; i++)
        try (var connection = ds.getConnection()) {
          var db =
              DatabaseFactory.getInstance()
                  .findCorrectDatabaseImplementation(new JdbcConnection(connection));
          try (var lb =
              new Liquibase(
                  "db/changelog/tenant/module/savings/parts/086-monthly-fee-diagnostics.xml",
                  new ClassLoaderResourceAccessor(),
                  db)) {
            lb.update(new Contexts(), new LabelExpression());
          }
        }
    }
  }

  @BeforeEach
  void setup() {
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      j.update("DELETE FROM m_savings_monthly_fee_assessment");
      j.update("DELETE FROM m_savings_monthly_fee_run");
      j.update(
          "UPDATE m_savings_account SET"
              + " status_enum=300,sub_status_enum=0,activatedon_date='2026-09-01',closedon_date=NULL,product_id=CASE"
              + " WHEN id=903 THEN 2 ELSE 1 END");
      j.update("UPDATE m_charge SET amount=10,is_active=true,is_deleted=false WHERE id=1");
    }
    fixture = new ProductPolicyWorkflowIntegrationTest();
    fixture.setup();
    jdbc = fixture.jdbc;
    ProductPolicyWorkflowIntegrationTest.setDay(DAY);
    eligibility =
        proxy(
            new MonthlyFeeEligibility(jdbc, fixture.workflow, new ProductPolicyCoreSnapshot(jdbc)));
    service = newService(eligibility);
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

  MonthlyFeeAssessmentService newService(MonthlyFeeEligibility evaluator) {
    return proxy(new MonthlyFeeAssessmentService(fixture.workflow, jdbc, evaluator));
  }

  void approve(boolean fee) {
    ProductPolicyWorkflowIntegrationTest.setDay(START);
    fixture.tx(
        () ->
            fixture.drafts.save(
                1,
                ProductPolicyValidationTest.JSON.replace(
                    "\"feeApplicable\":false",
                    fee ? "\"feeApplicable\":true,\"feeChargeId\":1" : "\"feeApplicable\":false")));
    fixture.submit(START);
    ProductPolicyWorkflowIntegrationTest.ACTOR.set(2L);
    fixture.decide(1, true);
    jdbc.update(
        "INSERT INTO m_savings_account_policy"
            + " VALUES(901,1,1,'MAIN',CURRENT_TIMESTAMP),(902,1,1,'MAIN',CURRENT_TIMESTAMP)");
    ProductPolicyWorkflowIntegrationTest.setDay(DAY);
  }

  @Test
  void approvedDisabledFeeIsDistinctFromUnapprovedCalculationAndDrafts() {
    fixture.draft();
    var run = service.create("2026-10");
    assertEquals("DIAGNOSTIC_COMPLETE", service.processChunk(run.id(), 100).status());
    assertTrue(
        service.assessments(run.id(), 0, 100).items().stream()
            .allMatch(a -> a.reason().equals("ACCOUNT_POLICY_BINDING_INVALID")));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_monthly_fee_assessment WHERE savings_account_id=903",
            Integer.class));
  }

  @Test
  void approvedFeeDisabledAndFeeEnabledAreRecordedWithoutFinancialEffects() {
    approve(true);
    var run = service.create("2026-10");
    assertEquals(run.id(), service.create("2026-10").id());
    assertEquals(run.id(), service.history(null, 1).items().getFirst().id());
    assertTrue(service.history("2026-10", 1).items().isEmpty());
    var completed = service.processChunk(run.id(), 100);
    assertEquals(2, completed.blocked());
    var rows = service.assessments(run.id(), 0, 1);
    assertTrue(rows.hasMore());
    assertEquals("FINANCIAL_RULES_UNAPPROVED", rows.items().getFirst().reason());
    assertEquals(1L, rows.items().getFirst().policyVersion());
    assertEquals(
        0, rows.items().getFirst().configuredFeeAmount().compareTo(new java.math.BigDecimal("10")));
    assertEquals("NOT_ATTEMPTED", service.reconciliation(run.id()).status());
    assertFalse(service.reconciliation(run.id()).nativeAccountingVerified());
    assertEquals(2, service.processChunk(run.id(), 100).evaluated());
    assertEquals(
        3000,
        jdbc.queryForObject(
            "SELECT SUM(account_balance_derived) FROM m_savings_account", Integer.class));
  }

  @Test
  void disabledFeeDoesNotInventAnAverageOrNativeCharge() {
    approve(false);
    var run = service.create("2026-10");
    service.processChunk(run.id(), 100);
    assertTrue(
        service.assessments(run.id(), 0, 100).items().stream()
            .allMatch(a -> a.outcome() == MonthlyFeeData.Outcome.NO_FEE_CONFIGURED));
  }

  @Test
  void simultaneousWorkersAndRestartCannotDuplicateAssessments() throws Exception {
    approve(true);
    var run = service.create("2026-10");
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      Callable<Void> work =
          () -> {
            ProductPolicyPersistenceIntegrationTest.tenant("one");
            ProductPolicyWorkflowIntegrationTest.setDay(DAY);
            try {
              start.await();
              service.processChunk(run.id(), 1);
              return null;
            } finally {
              ThreadLocalContextUtil.reset();
            }
          };
      var a = workers.submit(work);
      var b = workers.submit(work);
      start.countDown();
      a.get(30, TimeUnit.SECONDS);
      b.get(30, TimeUnit.SECONDS);
    }
    assertEquals(2, newService(eligibility).processChunk(run.id(), 100).evaluated());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_monthly_fee_assessment", Integer.class));
    assertThrows(
        org.springframework.dao.DuplicateKeyException.class,
        () ->
            jdbc.update(
                "INSERT INTO"
                    + " m_savings_monthly_fee_assessment(run_id,tenant_identifier,savings_account_id,savings_product_id,period,outcome,reason_code,financial_outcome,assessed_by,recorded_at)"
                    + " VALUES(?,'one',901,1,'2026-10','BLOCKED','duplicate','NOT_ATTEMPTED',1,CURRENT_TIMESTAMP)",
                run.id()));
  }

  @Test
  void chunkRollbackPreservesEarlierCheckpointAndRestartResumes() {
    approve(true);
    var run = service.create("2026-10");
    assertEquals(901, service.processChunk(run.id(), 1).afterAccountId());
    var failure = mock(MonthlyFeeEligibility.class);
    when(failure.evaluate(eq(902L), any())).thenThrow(new IllegalStateException("interrupted"));
    assertThrows(
        IllegalStateException.class, () -> newService(failure).processChunk(run.id(), 100));
    assertEquals(901, service.get(run.id()).afterAccountId());
    assertEquals(1, service.get(run.id()).evaluated());
    assertEquals(2, newService(eligibility).processChunk(run.id(), 100).evaluated());
  }

  @Test
  void unknownOutcomeIsQuarantinedWithoutOverwriteOrRetry() {
    approve(true);
    var run = service.create("2026-10");
    // Synthetic technical evidence, not a claim that a native financial operation was executed.
    jdbc.update(
        "INSERT INTO"
            + " m_savings_monthly_fee_assessment(run_id,tenant_identifier,savings_account_id,savings_product_id,period,outcome,reason_code,financial_outcome,assessed_by,recorded_at)"
            + " VALUES(?,'one',901,1,'2026-10','UNKNOWN_OUTCOME','SYNTHETIC_UNKNOWN','UNKNOWN_OUTCOME',1,CURRENT_TIMESTAMP)",
        run.id());
    var recovered = newService(eligibility).processChunk(run.id(), 100);
    assertEquals("RECONCILIATION_REQUIRED", recovered.status());
    assertEquals(1, recovered.unknown());
    assertEquals(1, recovered.evaluated()); // Quarantine before evaluating any other account.
    assertEquals(
        "SYNTHETIC_UNKNOWN", service.assessments(run.id(), 0, 100).items().getFirst().reason());
    assertFalse(service.reconciliation(run.id()).financialRetryAllowed());
    assertEquals(recovered, service.processChunk(run.id(), 100));
  }

  @Test
  void missingConfiguredPolicyIsBlockedEvenWhenAnOldBindingRemains() {
    approve(false);
    jdbc.update("DELETE FROM m_savings_product_policy WHERE savings_product_id=1");
    assertEquals(
        "POLICY_MISSING",
        fixture.tx(() -> eligibility.evaluate(901, java.time.YearMonth.of(2026, 10))).reason());
  }

  @Test
  void simultaneousRunCreationUsesTheSameDurableIdentity() throws Exception {
    approve(false);
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      Callable<String> create =
          () -> {
            ProductPolicyPersistenceIntegrationTest.tenant("one");
            ProductPolicyWorkflowIntegrationTest.setDay(DAY);
            try {
              start.await();
              return service.create("2026-10").id();
            } finally {
              ThreadLocalContextUtil.reset();
            }
          };
      var a = workers.submit(create);
      var b = workers.submit(create);
      start.countDown();
      assertEquals(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    }
    assertEquals(
        1, jdbc.queryForObject("SELECT COUNT(*) FROM m_savings_monthly_fee_run", Integer.class));
  }

  @Test
  void twoTenantsHaveIndependentIdentitiesAndWrongTenantCannotReadOrProcess() {
    approve(false);
    var run = service.create("2026-10");
    ProductPolicyPersistenceIntegrationTest.tenant("two");
    assertThrows(MonthlyFeeAssessmentService.NotFound.class, () -> service.get(run.id()));
    assertThrows(
        MonthlyFeeAssessmentService.NotFound.class, () -> service.processChunk(run.id(), 100));
    var other = service.create("2026-10");
    assertNotEquals(run.id(), other.id());
    assertEquals(0, service.processChunk(other.id(), 100).evaluated());
    ProductPolicyPersistenceIntegrationTest.tenant("one");
    assertEquals(0, service.get(run.id()).evaluated());
  }

  @Test
  void partialAccountInvalidChargeCoreDriftAndHistoricalTransitionsFailClosed() {
    approve(true);
    jdbc.update("UPDATE m_savings_account SET activatedon_date='2026-10-15' WHERE id=901");
    assertEquals(
        "PARTIAL_OR_INCOMPLETE_HISTORY",
        fixture.tx(() -> eligibility.evaluate(901, java.time.YearMonth.of(2026, 10))).reason());
    jdbc.update("UPDATE m_charge SET is_active=false WHERE id=1");
    assertEquals(
        "FEE_CHARGE_INVALID",
        fixture.tx(() -> eligibility.evaluate(902, java.time.YearMonth.of(2026, 10))).reason());
    jdbc.update("UPDATE m_savings_product SET nominal_annual_interest_rate=1 WHERE id=1");
    try {
      assertEquals(
          "CORE_SNAPSHOT_CHANGED",
          fixture.tx(() -> eligibility.evaluate(902, java.time.YearMonth.of(2026, 10))).reason());
    } finally {
      jdbc.update("UPDATE m_savings_product SET nominal_annual_interest_rate=0 WHERE id=1");
    }
    assertEquals(
        "POLICY_NOT_EFFECTIVE_FOR_FULL_PERIOD",
        fixture.tx(() -> eligibility.evaluate(902, java.time.YearMonth.of(2026, 9))).reason());
  }

  @Test
  void permissionsTenantBusinessDateAndFuturePeriodsAreRequired() {
    var actor = fixture.security.authenticatedUser();
    doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
        .when(actor)
        .validateHasPermissionTo(MonthlyFeeAssessmentService.ASSESS);
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.create("2026-10"));
    reset(actor);
    when(actor.getId()).thenReturn(1L);
    assertThrows(RuntimeException.class, () -> service.create("2026-11"));
    ThreadLocalContextUtil.reset();
    assertThrows(RuntimeException.class, () -> service.template());
  }

  @Test
  void nativeBatchAdapterExecutesExplicitRunAndRejectsWrongTenant() throws Exception {
    approve(true);
    var run = service.create("2026-10");
    var config = new MonthlyFeeDiagnosticJobConfig(service);
    var repository =
        new org.springframework.batch.core.repository.support.ResourcelessJobRepository();
    var job =
        config.savingsMonthlyFeeDiagnosticJob(
            repository, new DataSourceTransactionManager(jdbc.getDataSource()));
    var operator = new org.springframework.batch.core.launch.support.TaskExecutorJobOperator();
    operator.setJobRepository(repository);
    operator.setJobRegistry(
        new org.springframework.batch.core.configuration.support.MapJobRegistry());
    operator.afterPropertiesSet();
    var parameters =
        new org.springframework.batch.core.job.parameters.JobParametersBuilder()
            .addString("tenant", "one")
            .addString("runId", run.id())
            .toJobParameters();
    assertEquals(
        org.springframework.batch.core.BatchStatus.COMPLETED,
        operator.start(job, parameters).getStatus());
    assertEquals(2, service.get(run.id()).evaluated());
    assertEquals("DIAGNOSTIC_COMPLETE", service.get(run.id()).status());
    // In-memory Batch metadata is only the launcher; all assessment/checkpoint persistence is
    // PostgreSQL.
    var badParams =
        new org.springframework.batch.core.job.parameters.JobParametersBuilder()
            .addString("tenant", "two")
            .addString("runId", run.id())
            .toJobParameters();
    assertEquals(
        org.springframework.batch.core.BatchStatus.FAILED,
        operator.start(job, badParams).getStatus());
    assertEquals(2, service.get(run.id()).evaluated());
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
