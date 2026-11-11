/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.GuaranteeSweepData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
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

/**
 * Real PostgreSQL migrations, two tenants, transactions and concurrent claims. No financial posts.
 */
class GuaranteeSweepIntegrationTest {
  MainAccountIntegrationTest base;
  JdbcTemplate jdbc;
  GuaranteeSweepAccess access;
  GuaranteeSweepExecutionService executions;
  GuaranteeSweepDiagnosticService diagnostics;
  GuaranteeSweepReconciliationService reconciliation;
  GuaranteeEligibilityService eligibility;
  GuaranteeSweepAuditService audit;
  SavingsAccount account;
  static final String REQUEST = GuaranteeSweepValidationTest.REQUEST;

  @BeforeAll
  static void database() throws Exception {
    MainAccountIntegrationTest.database();
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      j.execute("ALTER TABLE m_savings_account ADD COLUMN sub_status_enum integer DEFAULT 0");
      j.execute(
          "CREATE TABLE m_loan(id bigint PRIMARY KEY,client_id bigint,group_id"
              + " bigint,loan_status_id integer DEFAULT 300,currency_code varchar(3) DEFAULT"
              + " 'MXN',currency_digits integer DEFAULT 2,total_outstanding_derived numeric(19,6)"
              + " DEFAULT 100)");
      j.execute(
          "CREATE TABLE m_loan_arrears_aging(loan_id bigint PRIMARY KEY,overdue_since_date_derived"
              + " date)");
      j.execute(
          "CREATE TABLE m_loan_transaction(id bigint PRIMARY KEY,loan_id bigint,amount"
              + " numeric,is_reversed boolean,transaction_type_enum integer)");
      j.execute(
          "CREATE TABLE m_savings_account_transaction(id bigint PRIMARY KEY,savings_account_id"
              + " bigint,amount numeric,is_reversed boolean,transaction_type_enum integer)");
      j.execute("CREATE TABLE m_account_transfer_details(id bigint PRIMARY KEY)");
      j.execute(
          "CREATE TABLE m_account_transfer_transaction(id bigint PRIMARY"
              + " KEY,account_transfer_details_id bigint,from_savings_transaction_id"
              + " bigint,to_loan_transaction_id bigint,is_reversed boolean)");
      j.execute(
          "CREATE TABLE acc_gl_journal_entry(id bigint PRIMARY KEY,currency_code"
              + " varchar(3),loan_transaction_id bigint,savings_transaction_id bigint,reversed"
              + " boolean,type_enum integer,amount numeric)");
      j.update("INSERT INTO m_loan(id,client_id) VALUES (501,1),(502,1),(503,2),(504,3)");
      j.update("INSERT INTO m_loan_arrears_aging VALUES (501,'2026-10-01'),(502,'2026-10-01')");
      for (int repeat = 0; repeat < 2; repeat++) {
        try (var connection = ds.getConnection()) {
          var db =
              DatabaseFactory.getInstance()
                  .findCorrectDatabaseImplementation(new JdbcConnection(connection));
          try (var lb =
              new Liquibase(
                  "db/changelog/tenant/module/savings/parts/088-guarantee-sweep-diagnostics.xml",
                  new ClassLoaderResourceAccessor(),
                  db)) {
            lb.update(new Contexts(), new LabelExpression());
          }
        }
      }
      assertEquals(
          5,
          j.queryForObject(
              "SELECT COUNT(*) FROM m_permission WHERE entity_name='GUARANTEE_SWEEP'",
              Integer.class));
      assertEquals(
          0,
          j.queryForObject("SELECT COUNT(*) FROM m_savings_guarantee_allocation", Integer.class));
    }
  }

  @BeforeEach
  void setup() {
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      for (String table : List.of("audit", "run_item", "execution", "run", "allocation"))
        j.update("DELETE FROM m_savings_guarantee_" + table);
      j.update("DELETE FROM acc_gl_journal_entry");
      j.update("DELETE FROM m_account_transfer_transaction");
      j.update("DELETE FROM m_account_transfer_details");
      j.update("DELETE FROM m_savings_account_transaction");
      j.update("DELETE FROM m_loan_transaction");
      j.update(
          "UPDATE m_loan SET loan_status_id=300,currency_code='MXN',total_outstanding_derived=100");
      j.update("UPDATE m_savings_account SET sub_status_enum=0");
    }
    base = new MainAccountIntegrationTest();
    base.setup();
    jdbc = base.jdbc;
    access = new GuaranteeSweepAccess(base.fixture.security, jdbc);
    account = mock(SavingsAccount.class);
    when(account.getId()).thenReturn(901L);
    when(account.clientId()).thenReturn(1L);
    when(account.groupId()).thenReturn(null);
    when(account.productId()).thenReturn(1L);
    when(account.getCurrency()).thenReturn(new MonetaryCurrency("MXN", 2, 1));
    when(account.isActive()).thenReturn(true);
    when(account.allowWithdrawal()).thenReturn(true);
    when(account.getWithdrawableBalance()).thenReturn(new BigDecimal("30"));
    var accounts = mock(SavingsAccountRepositoryWrapper.class);
    when(accounts.findOneWithNotFoundDetection(901L)).thenReturn(account);
    var resolver =
        new ProductTransactionPolicyResolver(
            jdbc, base.fixture.workflow, new ProductPolicyCoreSnapshot(jdbc));
    eligibility =
        new GuaranteeEligibilityService(
            jdbc, access, accounts, resolver, new ProductTransactionPolicyEvaluator());
    audit = proxy(new GuaranteeSweepAuditService(jdbc));
    executions = proxy(new GuaranteeSweepExecutionService(jdbc, access, eligibility, audit));
    diagnostics = proxy(new GuaranteeSweepDiagnosticService(jdbc, access, eligibility, audit));
    reconciliation =
        proxy(new GuaranteeSweepReconciliationService(jdbc, access, executions, audit));
    base.prepare();
    base.bind(901);
    jdbc.update("UPDATE m_savings_account SET status_enum=300 WHERE id=901");
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

  @Test
  void delinquentAndCurrentLoansAreDiagnosticsAndNativeBalanceIsNotGuaranteeAvailability() {
    assertEquals(901, executions.sources(1, 0, 100).getFirst().savingsAccountId());
    assertEquals(2, executions.loans(1, 0, 100).size());
    var d = executions.preview(1, 501, 901, new BigDecimal("10.00"));
    assertEquals(State.BLOCKED_PENDING_POLICY, d.state());
    assertNull(d.availableGuaranteeFunds());
    assertEquals(new BigDecimal("30"), d.nativeWithdrawableBalance());
    assertFalse(d.reasons().contains("NO_NATIVE_PAST_DUE_EVIDENCE"));
    jdbc.update("DELETE FROM m_loan_arrears_aging WHERE loan_id=501");
    try {
      assertTrue(
          executions
              .preview(1, 501, 901, BigDecimal.ONE)
              .reasons()
              .contains("NO_NATIVE_PAST_DUE_EVIDENCE"));
    } finally {
      jdbc.update("INSERT INTO m_loan_arrears_aging VALUES(501,'2026-10-01')");
    }
    assertTrue(d.reasons().contains("BE08_APPROVED_GUARANTEE_RELATIONSHIPS_UNAVAILABLE"));
    assertEquals(2, count("audit"));
    assertEquals(0, count("execution"));
  }

  @Test
  void accountLifecycleCurrencyPolicyAndNativeFundsAreAlwaysRechecked() {
    for (String change :
        List.of("status_enum=600", "sub_status_enum=100", "deposit_type_enum=200")) {
      jdbc.update("UPDATE m_savings_account SET " + change + " WHERE id=901");
      assertTrue(
          executions
              .preview(1, 501, 901, BigDecimal.ONE)
              .reasons()
              .contains("SOURCE_LIFECYCLE_OR_BLOCK_UNSUPPORTED"));
      jdbc.update(
          "UPDATE m_savings_account SET status_enum=300,sub_status_enum=0,deposit_type_enum=100"
              + " WHERE id=901");
    }
    when(account.getCurrency()).thenReturn(new MonetaryCurrency("USD", 2, 1));
    assertTrue(
        executions
            .preview(1, 501, 901, BigDecimal.ONE)
            .reasons()
            .contains("CURRENCY_CONVERSION_UNSUPPORTED"));
    when(account.getCurrency()).thenReturn(new MonetaryCurrency("MXN", 2, 1));
    assertTrue(
        executions
            .preview(1, 501, 901, new BigDecimal("31"))
            .reasons()
            .contains("INSUFFICIENT_NATIVE_WITHDRAWABLE_FUNDS"));
    assertTrue(
        executions
            .preview(1, 501, 901, new BigDecimal("101"))
            .reasons()
            .contains("REQUEST_EXCEEDS_NATIVE_OUTSTANDING"));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> executions.preview(1, 501, 901, new BigDecimal("1.001")));
    jdbc.update("DELETE FROM m_savings_main_account_audit");
    jdbc.update("DELETE FROM m_savings_account_policy");
    assertTrue(
        executions
            .preview(1, 501, 901, BigDecimal.ONE)
            .reasons()
            .contains("APPROVED_SOURCE_POLICY_UNAVAILABLE"));
  }

  @Test
  void crossClientOfficeAndTenantAndMissingContextFailBeforeReceiptCreation() {
    assertThrows(PlatformApiDataValidationException.class, () -> executions.apply(1, 503, REQUEST));
    assertThrows(PlatformApiDataValidationException.class, () -> executions.apply(2, 503, REQUEST));
    assertThrows(
        ClientNotFoundException.class, () -> executions.preview(3, 504, 901, BigDecimal.ONE));
    var original = executions.apply(1, 501, REQUEST).receipt();
    ProductPolicyPersistenceIntegrationTest.tenant("two");
    assertThrows(
        PlatformApiDataValidationException.class, () -> executions.get(1, 501, original.id()));
    ProductPolicyPersistenceIntegrationTest.tenant("one");
    ThreadLocalContextUtil.clearTenant();
    assertThrows(AccessDeniedException.class, executions::template);
    ProductPolicyPersistenceIntegrationTest.tenant("one");
    ThreadLocalContextUtil.setBusinessDates(new java.util.HashMap<>());
    assertThrows(AccessDeniedException.class, executions::template);
  }

  @Test
  void permissionsAreRequiredForEveryServiceEntryEvenWithoutSpringMethodSecurity() {
    var user = base.fixture.security.authenticatedUser();
    doThrow(new AccessDeniedException("Denied")).when(user).validateHasPermissionTo(anyString());
    assertThrows(AccessDeniedException.class, executions::template);
    assertThrows(
        AccessDeniedException.class, () -> executions.preview(1, 501, 901, BigDecimal.ONE));
    assertThrows(AccessDeniedException.class, () -> executions.apply(1, 501, REQUEST));
    assertThrows(AccessDeniedException.class, () -> executions.release(1, 501, REQUEST));
    assertThrows(AccessDeniedException.class, () -> executions.loans(1, 0, 100));
    assertThrows(AccessDeniedException.class, () -> executions.history(1, 501, 0, 100, "audit"));
    assertThrows(AccessDeniedException.class, () -> diagnostics.create("2026-10"));
    assertThrows(AccessDeniedException.class, () -> diagnostics.processChunk("missing", 100));
    assertThrows(AccessDeniedException.class, () -> reconciliation.inspect(1, 501, "missing"));
    assertEquals(0, count("execution"));
  }

  @Test
  void duplicateSubmissionStalePreviewPartialFullAndExcessCoverageNeverPost() {
    executions.preview(1, 501, 901, new BigDecimal("10"));
    jdbc.update("UPDATE m_loan SET total_outstanding_derived=5 WHERE id=501");
    var first = executions.apply(1, 501, REQUEST);
    assertTrue(
        first.receipt().evaluation().reasons().contains("REQUEST_EXCEEDS_NATIVE_OUTSTANDING"));
    assertFalse(first.duplicate());
    assertTrue(executions.apply(1, 501, REQUEST).duplicate());
    assertThrows(
        GuaranteeSweepExecutionService.Conflict.class, () -> executions.apply(1, 502, REQUEST));
    assertThrows(
        GuaranteeSweepExecutionService.Conflict.class,
        () -> executions.apply(1, 501, REQUEST.replace("10.00", "11")));
    for (int amount : List.of(2, 5, 50)) {
      var receipt =
          executions
              .apply(
                  1,
                  501,
                  REQUEST
                      .replace("manual-test-01", "coverage-" + amount)
                      .replace("10.00", String.valueOf(amount)))
              .receipt();
      assertNull(receipt.appliedAmount());
      assertNull(receipt.nativeLoanTransactionId());
      assertEquals(State.BLOCKED_PENDING_POLICY, receipt.state());
    }
    assertEquals(4, count("execution"));
    assertEquals(0, count("allocation"));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM m_loan_transaction", Integer.class));
  }

  @Test
  void concurrentManualRequestsAcrossFreshServiceInstancesProduceOneReceiptAndAudit()
      throws Exception {
    var results =
        concurrently(
            () ->
                proxy(new GuaranteeSweepExecutionService(jdbc, access, eligibility, audit))
                    .apply(1, 501, REQUEST));
    assertEquals(results.getFirst().receipt().id(), results.getLast().receipt().id());
    assertEquals(1, results.stream().filter(Submission::duplicate).count());
    assertEquals(1, count("execution"));
    assertEquals(1, count("audit"));
  }

  @Test
  void schedulerRepeatedInvocationsConcurrentWorkersAndRestartResumeOneLoanPerCheckpoint()
      throws Exception {
    var runs = concurrently(() -> diagnostics.create("2026-10"));
    assertEquals(runs.getFirst().id(), runs.getLast().id());
    var first = diagnostics.processChunk(runs.getFirst().id(), 1);
    assertEquals(State.PROCESSING, first.state());
    assertEquals(1, first.evaluated());
    // A newly constructed bean has no in-memory claim state.
    diagnostics =
        proxy(
            new GuaranteeSweepDiagnosticService(
                jdbc,
                access,
                new GuaranteeEligibilityService(
                    jdbc,
                    access,
                    mock(SavingsAccountRepositoryWrapper.class),
                    mock(ProductTransactionPolicyResolver.class),
                    new ProductTransactionPolicyEvaluator()),
                proxy(new GuaranteeSweepAuditService(jdbc))));
    var results = concurrently(() -> diagnostics.processChunk(first.id(), 1));
    assertEquals(State.BLOCKED_PENDING_POLICY, results.getLast().state());
    assertEquals(2, diagnostics.get(first.id()).evaluated());
    assertEquals(2, count("execution"));
    assertEquals(2, count("audit"));
    assertEquals(2, diagnostics.outcomes(first.id(), 0, 100).size());
    assertEquals(2, diagnostics.processChunk(first.id(), 100).evaluated());
    assertEquals(0, count("allocation"));
  }

  @Test
  void auditFailureRollsBackClaimAndInterruptedChunkCanBeSafelyRetried() {
    jdbc.execute(
        "ALTER TABLE m_savings_guarantee_audit ADD CONSTRAINT fail_test"
            + " CHECK(event='ELIGIBILITY_EVALUATED')");
    try {
      assertThrows(DataIntegrityViolationException.class, () -> executions.apply(1, 501, REQUEST));
      assertEquals(0, count("execution"));
      var run = diagnostics.create("2026-10");
      assertThrows(
          DataIntegrityViolationException.class, () -> diagnostics.processChunk(run.id(), 100));
      assertEquals(0, diagnostics.get(run.id()).afterLoanId());
      assertEquals(0, count("run_item"));
    } finally {
      jdbc.execute("ALTER TABLE m_savings_guarantee_audit DROP CONSTRAINT fail_test");
    }
    var run = diagnostics.create("2026-10");
    Thread.currentThread().interrupt();
    try {
      assertThrows(IllegalStateException.class, () -> diagnostics.processChunk(run.id(), 100));
    } finally {
      Thread.interrupted();
    }
    assertEquals(2, diagnostics.processChunk(run.id(), 100).evaluated());
  }

  @Test
  void loanSettlementReversalAndReconciliationNeverAuthorizeReleaseOrRetry() {
    String release = REQUEST.replace(",\"amount\":10.00", "");
    var first = executions.release(1, 501, release).receipt();
    assertTrue(first.evaluation().reasons().contains("LOAN_SETTLEMENT_NOT_VERIFIED"));
    jdbc.update("UPDATE m_loan SET loan_status_id=600,total_outstanding_derived=0 WHERE id=501");
    var settled =
        executions.release(1, 501, release.replace("manual-test-01", "settled-release")).receipt();
    assertFalse(settled.evaluation().reasons().contains("LOAN_SETTLEMENT_NOT_VERIFIED"));
    assertEquals(State.BLOCKED_PENDING_POLICY, settled.state());
    assertTrue(
        executions
            .release(1, 501, release.replace("manual-test-01", "settled-release"))
            .duplicate());
    var r = reconciliation.inspect(1, 501, settled.id());
    assertEquals("NOT_ATTEMPTED", r.status());
    assertFalse(r.nativeAccountingVerified());
    assertFalse(r.financialRetryAllowed());
    // Synthetic corruption fixtures exercise diagnostics only; they are not native posting proof.
    jdbc.update("INSERT INTO m_loan_transaction VALUES(1,501,10,true,2)");
    jdbc.update("INSERT INTO m_savings_account_transaction VALUES(1,901,10,false,2)");
    jdbc.update("INSERT INTO m_account_transfer_details VALUES(1)");
    jdbc.update("INSERT INTO m_account_transfer_transaction VALUES(1,1,1,1,false)");
    jdbc.update(
        "UPDATE m_savings_guarantee_execution SET"
            + " status='RECONCILIATION_REQUIRED',native_loan_transaction_id=1,native_savings_transaction_id=1,native_transfer_id=1"
            + " WHERE id=?",
        first.id());
    r = reconciliation.inspect(1, 501, first.id());
    assertEquals("RECONCILIATION_REQUIRED", r.status());
    assertTrue(r.repaymentReversed());
    assertFalse(r.nativeReferencesMatch());
    assertFalse(r.financialRetryAllowed());
    assertFalse(r.journalEntriesBalanced());
    assertEquals(0, count("allocation"));
  }

  @Test
  void databaseConstraintsCannotAdvertiseSuccessfulExecutionOrAllocateFunds() {
    var receipt = executions.apply(1, 501, REQUEST).receipt();
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE m_savings_guarantee_execution SET status='COMPLETED' WHERE id=?",
                receipt.id()));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE m_savings_guarantee_execution SET applied_amount=10 WHERE id=?",
                receipt.id()));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("DELETE FROM m_savings_guarantee_execution WHERE id=?", receipt.id()));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO"
                    + " m_savings_guarantee_allocation(tenant_identifier,client_id,loan_id,savings_account_id,savings_product_id,policy_version,currency_code,currency_digits,reserved_amount,consumed_amount,released_amount,status,created_by,business_date)"
                    + " VALUES('one',1,501,901,1,1,'MXN',2,10,0,0,'BLOCKED_PENDING_POLICY',1,CURRENT_DATE)"));
  }

  @Test
  void manualAndScheduledRequestsNeverCompeteForOrConsumeUnapprovedSharedFunds() throws Exception {
    var run = diagnostics.create("2026-10");
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var manual =
          workers.submit(
              () -> {
                context();
                start.await();
                try {
                  return executions.apply(1, 501, REQUEST);
                } finally {
                  ThreadLocalContextUtil.reset();
                }
              });
      var scheduled =
          workers.submit(
              () -> {
                context();
                start.await();
                try {
                  return diagnostics.processChunk(run.id(), 100);
                } finally {
                  ThreadLocalContextUtil.reset();
                }
              });
      start.countDown();
      assertEquals(
          State.BLOCKED_PENDING_POLICY, manual.get(20, TimeUnit.SECONDS).receipt().state());
      assertEquals(2, scheduled.get(20, TimeUnit.SECONDS).evaluated());
    }
    assertEquals(3, count("execution"));
    assertEquals(0, count("allocation"));
    assertEquals(
        new BigDecimal("100.000000"),
        jdbc.queryForObject(
            "SELECT total_outstanding_derived FROM m_loan WHERE id=501", BigDecimal.class));
  }

  @Test
  void identicalTenantIdentifiersAndUncertainOutcomesCannotBeReusedOrReposted() {
    var original = executions.apply(1, 501, REQUEST).receipt();
    ProductPolicyPersistenceIntegrationTest.tenant("two");
    var other = executions.apply(1, 501, REQUEST).receipt();
    assertNotEquals(original.id(), other.id());
    assertEquals("two", other.tenant());
    ProductPolicyPersistenceIntegrationTest.tenant("one");
    jdbc.update(
        "UPDATE m_savings_guarantee_execution SET status='RECONCILIATION_REQUIRED' WHERE id=?",
        original.id());
    var duplicate = executions.apply(1, 501, REQUEST);
    assertTrue(duplicate.duplicate());
    assertEquals(State.RECONCILIATION_REQUIRED, duplicate.receipt().state());
    assertFalse(reconciliation.inspect(1, 501, original.id()).financialRetryAllowed());
    assertEquals(1, count("execution"));
  }

  @Test
  void nativeBatchAdapterRequiresExplicitTenantAndKeepsFinancialRunBlocked() throws Exception {
    var run = diagnostics.create("2026-10");
    var config = new GuaranteeSweepDiagnosticJobConfig(diagnostics);
    var repository =
        new org.springframework.batch.core.repository.support.ResourcelessJobRepository();
    var job =
        config.savingsGuaranteeSweepDiagnosticJob(
            repository, new DataSourceTransactionManager(jdbc.getDataSource()));
    var operator = new org.springframework.batch.core.launch.support.TaskExecutorJobOperator();
    operator.setJobRepository(repository);
    operator.setJobRegistry(
        new org.springframework.batch.core.configuration.support.MapJobRegistry());
    operator.afterPropertiesSet();
    var params =
        new org.springframework.batch.core.job.parameters.JobParametersBuilder()
            .addString("tenant", "one")
            .addString("runId", run.id())
            .toJobParameters();
    assertEquals(
        org.springframework.batch.core.BatchStatus.COMPLETED,
        operator.start(job, params).getStatus());
    // Batch launcher completion describes the diagnostic task; the persisted financial run is
    // blocked.
    assertEquals(State.BLOCKED_PENDING_POLICY, diagnostics.get(run.id()).state());
    var wrong =
        new org.springframework.batch.core.job.parameters.JobParametersBuilder()
            .addString("tenant", "two")
            .addString("runId", run.id())
            .toJobParameters();
    assertEquals(
        org.springframework.batch.core.BatchStatus.FAILED, operator.start(job, wrong).getStatus());
    assertEquals(2, count("execution"));
  }

  private static void context() {
    ProductPolicyPersistenceIntegrationTest.tenant("one");
    ProductPolicyWorkflowIntegrationTest.setDay(MainAccountIntegrationTest.DAY);
  }

  long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM m_savings_guarantee_" + table, Long.class);
  }

  <T> List<T> concurrently(Callable<T> call) throws Exception {
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      Callable<T> task =
          () -> {
            ProductPolicyPersistenceIntegrationTest.tenant("one");
            ProductPolicyWorkflowIntegrationTest.setDay(MainAccountIntegrationTest.DAY);
            try {
              start.await();
              return call.call();
            } finally {
              ThreadLocalContextUtil.reset();
            }
          };
      var a = workers.submit(task);
      var b = workers.submit(task);
      start.countDown();
      return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    }
  }

  @AfterEach
  void clear() {
    base.clear();
  }

  @AfterAll
  static void cleanup() {
    ProductPolicyPersistenceIntegrationTest.cleanup();
  }
}
