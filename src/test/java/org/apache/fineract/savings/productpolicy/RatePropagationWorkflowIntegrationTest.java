/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyWorkflowIntegrationTest.*;
import static org.apache.fineract.savings.productpolicy.RatePropagationData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import liquibase.*;
import liquibase.database.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * Real PostgreSQL upgrade, two-tenant routing, immutable evidence, worker races and crash rollback.
 */
class RatePropagationWorkflowIntegrationTest {
  ProductPolicyWorkflowIntegrationTest f;
  RatePropagationService service;
  ReferenceRateService rates;

  static class RecordingExecutor implements AccountRateTransitionExecutor {
    final DisabledAccountRateTransitionExecutor disabled =
        new DisabledAccountRateTransitionExecutor();
    final java.util.concurrent.atomic.AtomicInteger executions =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger reconciliations =
        new java.util.concurrent.atomic.AtomicInteger();

    public boolean financialExecutionSupported() {
      return false;
    }

    public String blockingReason(Source s) {
      return disabled.blockingReason(s);
    }

    public TransitionResult execute(String key, AccountSnapshot a, Source s, LocalDate d) {
      executions.incrementAndGet();
      throw new AssertionError("Unexpected financial invocation");
    }

    public TransitionResult reconcile(String key) {
      reconciliations.incrementAndGet();
      throw new AssertionError("Unexpected reconciliation invocation");
    }
  }

  RecordingExecutor executor;

  @BeforeAll
  static void database() throws Exception {
    ProductPolicyWorkflowIntegrationTest.database();
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      j.execute(
          "ALTER TABLE m_savings_account ADD COLUMN product_id bigint, ADD COLUMN status_enum"
              + " integer DEFAULT 300, ADD COLUMN sub_status_enum integer DEFAULT 0, ADD COLUMN"
              + " deposit_type_enum integer DEFAULT 100, ADD COLUMN created_on_utc timestamp with"
              + " time zone, ADD COLUMN activatedon_date date, ADD COLUMN currency_code varchar(3)"
              + " DEFAULT 'MXN', ADD COLUMN currency_digits integer DEFAULT 2, ADD COLUMN"
              + " nominal_annual_interest_rate numeric(19,6) DEFAULT 6, ADD COLUMN"
              + " interest_compounding_period_enum integer DEFAULT 0, ADD COLUMN"
              + " interest_posting_period_enum integer DEFAULT 0, ADD COLUMN"
              + " interest_calculation_type_enum integer DEFAULT 0, ADD COLUMN"
              + " interest_calculation_days_in_year_type_enum integer DEFAULT 0, ADD COLUMN"
              + " account_balance_derived numeric(19,6) DEFAULT 1000");
      for (int i = 0; i < 2; i++)
        try (var connection = ds.getConnection()) {
          var db =
              DatabaseFactory.getInstance()
                  .findCorrectDatabaseImplementation(new JdbcConnection(connection));
          try (var lb =
              new Liquibase(
                  "db/changelog/tenant/module/savings/parts/084-rate-propagation.xml",
                  new ClassLoaderResourceAccessor(),
                  db)) {
            lb.update(new Contexts(), new LabelExpression());
          }
        }
      assertEquals(
          6,
          j.queryForObject(
              "SELECT COUNT(*) FROM m_permission WHERE entity_name='SAVINGS_RATE_PROPAGATION'",
              Integer.class));
    }
  }

  @AfterAll
  static void cleanup() {
    ProductPolicyWorkflowIntegrationTest.cleanup();
  }

  @BeforeEach
  void setup() {
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      j.execute(
          "TRUNCATE"
              + " m_savings_rate_attempt,m_savings_rate_audit,m_savings_rate_account,m_savings_rate_batch");
    }
    f = new ProductPolicyWorkflowIntegrationTest();
    f.setup();
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      j.update("DELETE FROM m_savings_account");
      j.execute("TRUNCATE m_savings_reference_rate CASCADE");
      j.update("UPDATE m_savings_product SET nominal_annual_interest_rate=6 WHERE id=1");
    }
    rates = new ReferenceRateService(f.workflow, f.jdbc);
    executor = new RecordingExecutor();
    service = newService();
  }

  RatePropagationService newService() {
    return new RatePropagationService(
        f.workflow,
        f.jdbc,
        new RatePropagationSources(
            f.workflow, f.jdbc, new ProductPolicyCoreSnapshot(f.jdbc), rates),
        new RatePropagationPopulation(f.jdbc),
        executor);
  }

  @AfterEach
  void clear() {
    f.clear();
  }

  <T> T tx(Supplier<T> op) {
    return f.tx(op);
  }

  String policy(long revision, String mode) {
    return ProductPolicyValidationTest.JSON
        .replace("\"expectedRevision\":0", "\"expectedRevision\":" + revision)
        .replace("\"classification\":\"SIGHT\"", "\"classification\":\"INTEREST_BEARING_SIGHT\"")
        .replace("\"interestMode\":\"NONE\"", "\"interestMode\":\"" + mode + "\"");
  }

  long approveSource(LocalDate date) {
    ACTOR.set(1L);
    Long revision = f.drafts.get(1).revision();
    var v = tx(() -> f.drafts.save(1, policy(revision == null ? 0 : revision, "FIXED")));
    f.submit(date);
    ACTOR.set(2L);
    f.decide(v.revision(), true);
    ACTOR.set(1L);
    return v.revision();
  }

  void account(long id, Long version) {
    f.jdbc.update(
        "INSERT INTO m_savings_account(id,product_id,created_on_utc,activatedon_date) VALUES"
            + " (?,1,?,?)",
        id,
        org.apache.fineract.infrastructure.core.service.DateUtils.getAuditLocalDateTime()
            .minusDays(10),
        DAY.minusDays(9));
    if (version != null)
      f.jdbc.update(
          "INSERT INTO"
              + " m_savings_account_policy(savings_account_id,savings_product_id,version,purpose,bound_at)"
              + " VALUES (?,1,?,'MAIN',?)",
          id,
          version,
          DAY.atStartOfDay());
  }

  String plan(long version, LocalDate date) {
    return "{\"policyVersion\":" + version + ",\"effectiveDate\":\"" + date + "\"}";
  }

  String createBody(String key, long version, LocalDate date) {
    return plan(version, date).replace("}", ",\"requestKey\":\"" + key + "\"}");
  }

  Batch create(long version, LocalDate date) {
    return tx(() -> service.create(1, createBody(UUID.randomUUID().toString(), version, date)));
  }

  String action(Batch b) {
    return "{\"expectedRevision\":"
        + b.revision()
        + ",\"reason\":\"Reviewed exact configuration-only snapshot\"}";
  }

  Batch discover(Batch b, int limit) {
    return tx(() -> service.discover(b.id(), action(b), limit));
  }

  Batch seal(Batch b) {
    while (!b.planningComplete()) b = discover(b, 2);
    return b;
  }

  Batch authorize(Batch b) {
    Batch sealed = seal(b);
    Batch pending = tx(() -> service.submit(sealed.id(), action(sealed)));
    ACTOR.set(4L);
    var approved = tx(() -> service.decide(pending.id(), true, action(pending)));
    ACTOR.set(1L);
    return approved;
  }

  Batch assess(Batch b, int size) {
    return tx(() -> service.assess(b.id(), action(b), size));
  }

  List<Map<String, Object>> financialState() {
    return f.jdbc.queryForList(
        "SELECT id,product_id,status_enum,nominal_annual_interest_rate,account_balance_derived FROM"
            + " m_savings_account ORDER BY id");
  }

  @Test
  void previewIsReadOnlyAndMixedEligibilityCountersAreExact() {
    long v = approveSource(DAY);
    for (long id = 1; id <= 7; id++) account(id, id == 7 ? null : v);
    f.jdbc.update("UPDATE m_savings_account SET status_enum=600 WHERE id=2");
    f.jdbc.update("UPDATE m_savings_account SET status_enum=100 WHERE id=3");
    f.jdbc.update("UPDATE m_savings_account SET deposit_type_enum=200 WHERE id=4");
    f.jdbc.update("UPDATE m_savings_account SET nominal_annual_interest_rate=9 WHERE id=5");
    f.jdbc.update("UPDATE m_savings_account SET sub_status_enum=100 WHERE id=6");
    var before = financialState();
    var preview = tx(() -> service.preview(1, plan(v, DAY)));
    assertEquals(7, preview.population().totalMatching());
    assertEquals(4, preview.population().totalExcluded());
    assertEquals(3, preview.population().totalBlocked());
    assertEquals(0, preview.population().totalEligible());
    assertEquals(
        0, f.jdbc.queryForObject("SELECT COUNT(*) FROM m_savings_rate_batch", Integer.class));
    assertEquals(before, financialState());
    assertFalse(preview.capabilities().financialExecutionSupported());
    var b = seal(create(v, DAY));
    assertEquals(7, b.counters().totalDiscovered());
    assertEquals(4, b.counters().totalSkipped());
    assertEquals(3, b.counters().totalPending());
  }

  @Test
  void pendingRejectedMissingAndRetroactiveSourcesCannotCreatePlans() {
    assertThrows(RuntimeException.class, () -> create(1, DAY));
    tx(() -> f.drafts.save(1, policy(0, "FIXED")));
    f.submit(DAY);
    assertThrows(RuntimeException.class, () -> create(1, DAY));
    ACTOR.set(2L);
    f.decide(1, false);
    ACTOR.set(1L);
    assertThrows(RuntimeException.class, () -> create(1, DAY));
    assertThrows(RuntimeException.class, () -> create(1, DAY.minusDays(1)));
    assertThrows(
        ProductPolicyService.NotFound.class,
        () -> tx(() -> service.create(999, createBody(UUID.randomUUID().toString(), 1, DAY))));
  }

  @Test
  void idempotentCreationAndSealedPopulationNeverExpandAfterApproval() {
    long v = approveSource(DAY);
    account(1, v);
    account(2, v);
    String body = createBody(UUID.randomUUID().toString(), v, DAY);
    var first = tx(() -> service.create(1, body));
    assertEquals(first.id(), tx(() -> service.create(1, body)).id());
    assertThrows(
        ProductPolicyService.Conflict.class,
        () ->
            tx(() -> service.create(1, body.replace(DAY.toString(), DAY.plusDays(1).toString()))));
    var partial = discover(first, 1);
    assertEquals(1, partial.discoveryCheckpoint());
    account(3, v);
    service = newService(); // New process/service with no in-memory progress.
    var approved = authorize(partial);
    assertEquals(2, approved.counters().totalDiscovered());
    assertEquals(first.sourceHash(), approved.sourceHash());
    assertNotNull(approved.authorizedHash());
    assertEquals(2, service.accounts(first.id(), 0, 100).items().size());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            f.jdbc.update(
                "INSERT INTO"
                    + " m_savings_rate_account(batch_id,savings_account_id,operation_key,account_snapshot,eligibility,eligibility_reason,outcome,attempt_count)"
                    + " VALUES (?,3,?,'{}','BLOCKED','TEST','PENDING',0)",
                first.id(),
                UUID.randomUUID().toString()));
  }

  @Test
  void independentReviewIncludesAllBatchAndSourceDraftContributors() {
    long v = approveSource(DAY);
    account(1, v);
    var b = create(v, DAY);
    ACTOR.set(3L);
    var sealed = seal(b);
    ACTOR.set(1L);
    var pending = tx(() -> service.submit(sealed.id(), action(sealed)));
    for (long actor : new long[] {1, 3}) {
      ACTOR.set(actor);
      assertThrows(
          AccessDeniedException.class,
          () -> tx(() -> service.decide(pending.id(), true, action(pending))));
    }
    ACTOR.set(4L);
    var approved = tx(() -> service.decide(pending.id(), true, action(pending)));
    assertEquals(4, approved.checkerId());
    assertEquals("AUTHORIZED", approved.status());
    assertThrows(
        ProductPolicyService.Conflict.class,
        () -> tx(() -> service.decide(approved.id(), true, action(approved))));
  }

  @Test
  void permissionsAndTenantBoundariesAreRecheckedForEveryOperation() {
    long v = approveSource(DAY);
    var b = create(v, DAY);
    var user = f.security.authenticatedUser();
    doThrow(new AccessDeniedException("Denied"))
        .when(user)
        .validateHasPermissionTo(RatePropagationService.READ);
    assertThrows(AccessDeniedException.class, () -> service.get(b.id()));
    doThrow(new AccessDeniedException("Denied"))
        .when(user)
        .validateHasPermissionTo(RatePropagationService.CHECK);
    assertThrows(
        AccessDeniedException.class, () -> tx(() -> service.decide(b.id(), true, action(b))));
    doThrow(new AccessDeniedException("Denied"))
        .when(user)
        .validateHasPermissionTo(RatePropagationService.ASSESS);
    assertThrows(AccessDeniedException.class, () -> tx(() -> service.start(b.id(), action(b))));
    doThrow(new AccessDeniedException("Denied"))
        .when(user)
        .validateHasPermissionTo(RatePropagationService.CANCEL);
    assertThrows(AccessDeniedException.class, () -> tx(() -> service.cancel(b.id(), action(b))));
    reset(user);
    when(user.getId()).thenAnswer(i -> ACTOR.get());
    ProductPolicyPersistenceIntegrationTest.tenant("two");
    setDay(DAY);
    assertThrows(RatePropagationService.NotFound.class, () -> service.get(b.id()));
    assertThrows(
        RatePropagationService.NotFound.class,
        () -> tx(() -> service.discover(b.id(), action(b), 1)));
    ThreadLocalContextUtil.clearTenant();
    assertThrows(AccessDeniedException.class, service::template);
  }

  @Test
  void futureEffectiveDateAndLateExecutionRemainBlockedWithoutRateChanges() {
    long old = approveSource(DAY);
    account(1, old);
    long future = approveSource(DAY.plusDays(2));
    var b = authorize(create(future, DAY.plusDays(2)));
    assertEquals("SCHEDULED", b.status());
    var before = financialState();
    assertThrows(RatePropagationService.NotReady.class, () -> assess(b, 10));
    assertEquals("EFFECTIVE_DATE_NOT_REACHED", tx(() -> service.start(b.id(), action(b))).code());
    setDay(DAY.plusDays(3));
    var late = assess(b, 10);
    assertEquals("BLOCKED", late.status());
    assertEquals(
        "MISSED_EFFECTIVE_DATE_REAUTHORIZATION_REQUIRED",
        service.accounts(b.id(), 0, 10).items().getFirst().errorCategory());
    assertEquals(before, financialState());
    assertEquals(old, service.accounts(b.id(), 0, 10).items().getFirst().previousPolicyVersion());
  }

  @Test
  void partialAssessmentRestartRepeatedStartAndResumeNeverProduceAppliedResults() {
    long v = approveSource(DAY);
    for (int id = 1; id <= 5; id++) account(id, v);
    var b = authorize(create(v, DAY));
    var before = financialState();
    var partial = assess(b, 2);
    assertEquals("ASSESSING", partial.status());
    assertEquals(2, partial.counters().totalProcessed());
    assertEquals(3, partial.counters().totalPending());
    service = newService();
    var middle = assess(service.get(b.id()), 2);
    var done = assess(middle, 2);
    assertEquals("BLOCKED", done.status());
    assertEquals(5, done.counters().totalBlocked());
    assertEquals(5, done.counters().totalProcessed());
    assertEquals(0, done.counters().totalApplied());
    assertEquals(done.revision(), assess(done, 2).revision());
    for (int i = 0; i < 2; i++)
      assertTrue(tx(() -> service.start(done.id(), action(done))).code().contains("UNSUPPORTED"));
    assertEquals(5, service.attempts(done.id(), 0, 100).items().size());
    assertEquals(before, financialState());
    assertEquals("PLANNING_COMPLETE", done.completionMeaning());
    assertEquals(0, executor.executions.get());
  }

  @Test
  void accountClosureOverridesAndBindingChangesAfterReviewAreReported() {
    long v = approveSource(DAY);
    for (int id = 1; id <= 3; id++) account(id, v);
    var b = authorize(create(v, DAY));
    f.jdbc.update("UPDATE m_savings_account SET status_enum=600 WHERE id=1");
    f.jdbc.update("UPDATE m_savings_account SET nominal_annual_interest_rate=12 WHERE id=2");
    f.jdbc.update("DELETE FROM m_savings_account_policy WHERE savings_account_id=3");
    var before = financialState();
    assess(b, 10);
    for (var i : service.accounts(b.id(), 0, 10).items())
      assertEquals("ACCOUNT_STATE_CHANGED_REVIEW_REQUIRED", i.errorCategory());
    assertEquals(before, financialState());
  }

  @Test
  void staleCoreOrNewPolicySourceCannotExecuteAnAuthorizedSnapshot() {
    long v = approveSource(DAY);
    account(1, v);
    var b = authorize(create(v, DAY));
    f.jdbc.update("UPDATE m_savings_product SET nominal_annual_interest_rate=7 WHERE id=1");
    var blocked = assess(b, 10);
    assertEquals("SOURCE_VERSION_CHANGED_OR_UNAVAILABLE", blocked.reason());
    assertEquals(
        new BigDecimal("6.000000"),
        f.jdbc.queryForObject(
            "SELECT nominal_annual_interest_rate FROM m_savings_account WHERE id=1",
            BigDecimal.class));
    assertEquals(v, service.get(b.id()).source().policyVersion());
  }

  @Test
  void databaseGuardsProtectSourcesPopulationAuditAndFalseSuccess() {
    long v = approveSource(DAY);
    account(1, v);
    var b = authorize(create(v, DAY));
    assess(b, 1);
    for (String sql :
        List.of(
            "UPDATE m_savings_rate_batch SET source_json='{}'",
            "UPDATE m_savings_rate_batch SET population_hash='tampered'",
            "UPDATE m_savings_rate_batch SET checker_id=1",
            "UPDATE m_savings_rate_account SET account_snapshot='{}'",
            "UPDATE m_savings_rate_account SET outcome='APPLIED'",
            "DELETE FROM m_savings_rate_audit",
            "DELETE FROM m_savings_rate_attempt",
            "DELETE FROM m_savings_rate_account",
            "DELETE FROM m_savings_rate_batch"))
      assertThrows(org.springframework.dao.DataAccessException.class, () -> f.jdbc.update(sql));
    assertEquals(b.sourceHash(), service.get(b.id()).sourceHash());
    assertEquals(0, service.get(b.id()).counters().totalApplied());
  }

  @Test
  void accountDisappearancePreservesEvidenceWithoutPreventingNativeApplicationDeletion() {
    long v = approveSource(DAY);
    account(1, null);
    account(2, null);
    f.jdbc.update("UPDATE m_savings_account SET status_enum=100 WHERE id=1");
    var b = authorize(create(v, DAY));
    f.jdbc.update("DELETE FROM m_savings_account WHERE id IN (1,2)");
    var done = assess(b, 2);
    assertEquals(2, done.counters().totalDiscovered());
    assertEquals(1, done.counters().totalSkipped());
    assertEquals(1, done.counters().totalBlocked());
    assertEquals(
        "ACCOUNT_STATE_CHANGED_REVIEW_REQUIRED",
        service.accounts(b.id(), 1, 10).items().getFirst().errorCategory());
  }

  @Test
  void discoveryCrashRollsBackItemsCheckpointAndAuditThenResumesSafely() {
    long v = approveSource(DAY);
    account(1, v);
    account(2, v);
    var b = create(v, DAY);
    f.jdbc.execute(
        "ALTER TABLE m_savings_rate_audit ADD CONSTRAINT test_fail_discovery CHECK"
            + " (action<>'DISCOVER')");
    try {
      assertThrows(RuntimeException.class, () -> discover(b, 2));
    } finally {
      f.jdbc.execute("ALTER TABLE m_savings_rate_audit DROP CONSTRAINT test_fail_discovery");
    }
    assertEquals(0, service.get(b.id()).counters().totalDiscovered());
    assertEquals(0, service.get(b.id()).discoveryCheckpoint());
    service = newService();
    assertEquals(2, seal(service.get(b.id())).counters().totalDiscovered());
  }

  @Test
  void assessmentCrashRollsBackAttemptOutcomesAndProgressThenResumes() {
    long v = approveSource(DAY);
    account(1, v);
    account(2, v);
    var b = authorize(create(v, DAY));
    f.jdbc.execute(
        "ALTER TABLE m_savings_rate_audit ADD CONSTRAINT test_fail_assess CHECK"
            + " (action<>'ASSESS')");
    try {
      assertThrows(RuntimeException.class, () -> assess(b, 2));
    } finally {
      f.jdbc.execute("ALTER TABLE m_savings_rate_audit DROP CONSTRAINT test_fail_assess");
    }
    assertEquals(2, service.get(b.id()).counters().totalPending());
    assertEquals(0, service.attempts(b.id(), 0, 10).items().size());
    service = newService();
    var done = assess(service.get(b.id()), 2);
    assertEquals(2, done.counters().totalProcessed());
  }

  @Test
  void unknownOutcomesAreNeverClaimedRetriedOrAutomaticallyReconciled() {
    long v = approveSource(DAY);
    account(1, v);
    account(2, v);
    var b = authorize(create(v, DAY));
    // Model an imported uncertain outcome. No adapter in this release can create one.
    f.jdbc.update(
        "UPDATE m_savings_rate_account SET"
            + " outcome='UNKNOWN_OUTCOME',error_category='RECONCILIATION_REQUIRED' WHERE"
            + " savings_account_id=1");
    var done = assess(b, 2);
    assertEquals(1, done.counters().totalUnknownOutcome());
    assertEquals(1, done.counters().totalProcessed());
    assertEquals(0, service.accounts(b.id(), 0, 10).items().getFirst().attemptCount());
    assertThrows(
        ProductPolicyService.Conflict.class,
        () -> tx(() -> service.cancel(done.id(), action(done))));
    assertEquals(0, executor.reconciliations.get());
    assertEquals(0, executor.executions.get());
  }

  @Test
  void cancellationAndRejectionAreDurableTerminalConfigurationDecisions() {
    long v = approveSource(DAY);
    account(1, v);
    var b = authorize(create(v, DAY));
    var cancelled = tx(() -> service.cancel(b.id(), action(b)));
    assertEquals("CANCELLED", cancelled.status());
    assertThrows(ProductPolicyService.Conflict.class, () -> assess(cancelled, 1));
    assertEquals(
        cancelled.revision(),
        tx(() -> service.cancel(cancelled.id(), action(cancelled))).revision());
    var draft = seal(create(v, DAY));
    var submitted = tx(() -> service.submit(draft.id(), action(draft)));
    ACTOR.set(4L);
    var rejected = tx(() -> service.decide(submitted.id(), false, action(submitted)));
    assertEquals("REJECTED", rejected.status());
    assertThrows(ProductPolicyService.Conflict.class, () -> assess(rejected, 1));
  }

  @Test
  void stableKeysetPaginationAndLargePopulationRemainBounded() {
    long v = approveSource(DAY);
    f.jdbc.update(
        "INSERT INTO m_savings_account(id,product_id,created_on_utc,activatedon_date) SELECT"
            + " x,1,?,? FROM generate_series(1,1203) x",
        org.apache.fineract.infrastructure.core.service.DateUtils.getAuditLocalDateTime()
            .minusDays(10),
        DAY.minusDays(9));
    f.jdbc.update(
        "INSERT INTO"
            + " m_savings_account_policy(savings_account_id,savings_product_id,version,purpose,bound_at)"
            + " SELECT id,1,?,'MAIN',? FROM m_savings_account",
        v,
        DAY.atStartOfDay());
    var b = create(v, DAY);
    int chunks = 0;
    while (!b.planningComplete()) {
      b = discover(b, 100);
      chunks++;
    }
    assertEquals(13, chunks);
    assertEquals(1203, b.counters().totalDiscovered());
    long after = 0, total = 0;
    while (true) {
      var page = service.accounts(b.id(), after, 100);
      assertTrue(page.items().size() <= 100);
      total += page.items().size();
      after = page.nextAfterId();
      if (!page.hasMore()) break;
    }
    assertEquals(1203, total);
    assertThrows(RuntimeException.class, () -> service.accounts("bad", 0, 10));
    final String id = b.id();
    assertThrows(RuntimeException.class, () -> service.accounts(id, -1, 10));
    assertThrows(RuntimeException.class, () -> service.accounts(id, 0, 101));
  }

  @Test
  void duplicateDiscoveryAndWorkerRacesDoNotDuplicateItemsOrAttempts() throws Exception {
    long v = approveSource(DAY);
    for (int id = 1; id <= 4; id++) account(id, v);
    var b = create(v, DAY);
    race(() -> service.discover(b.id(), action(b), 2));
    var discovered = seal(service.get(b.id()));
    assertEquals(4, discovered.counters().totalDiscovered());
    var approved = authorize(discovered);
    race(() -> service.assess(approved.id(), action(approved), 2));
    var partial = service.get(b.id());
    assertEquals(2, partial.counters().totalProcessed());
    var done = assess(partial, 2);
    assertEquals(4, done.counters().totalProcessed());
    assertEquals(4, service.attempts(b.id(), 0, 100).items().size());
  }

  void race(Supplier<Batch> op) throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<Boolean> task =
          () -> {
            ProductPolicyPersistenceIntegrationTest.tenant("one");
            setDay(DAY);
            ACTOR.set(1L);
            try {
              start.await();
              tx(op);
              return true;
            } catch (ProductPolicyService.Conflict e) {
              return false;
            } finally {
              ThreadLocalContextUtil.reset();
              ACTOR.remove();
            }
          };
      var a = pool.submit(task);
      var b = pool.submit(task);
      start.countDown();
      assertNotEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    }
  }

  @Test
  void aSecondPlanCannotSilentlyOverlapTheReviewedAccountPopulation() {
    long v = approveSource(DAY);
    account(1, v);
    var first = authorize(create(v, DAY));
    var second = seal(create(v, DAY));
    assertEquals(
        "CONFLICTING_RATE_PLAN",
        service.accounts(second.id(), 0, 10).items().getFirst().eligibilityReason());
    assertEquals(0, second.counters().totalEligible());
    assertEquals(1, first.counters().totalPending());
  }

  @Test
  void populationUsesCapturedBusinessDateAndReportsMissingCreationMetadata() {
    long v = approveSource(DAY);
    account(1, v);
    account(2, v);
    account(3, v);
    f.jdbc.update("UPDATE m_savings_account SET created_on_utc=NULL WHERE id=1");
    f.jdbc.update("UPDATE m_savings_account SET activatedon_date=? WHERE id=2", DAY.plusDays(1));
    f.jdbc.update(
        "UPDATE m_savings_account SET created_on_utc=? WHERE id=3",
        org.apache.fineract.infrastructure.core.service.DateUtils.getAuditLocalDateTime()
            .plusDays(1));
    var b = create(v, DAY);
    setDay(DAY.plusDays(2));
    var sealed = seal(b);
    assertEquals(DAY, sealed.populationBusinessDate());
    assertEquals(2, sealed.counters().totalDiscovered());
    assertEquals(2L, sealed.counters().reasons().get("MISSING_OR_FUTURE_ACCOUNT_METADATA"));
    assertFalse(sealed.capabilities().financialExecutionSupported());
  }

  @Test
  void staleRepeatableReadWorkerAbortsInsteadOfReplayingACheckpoint() throws Exception {
    long v = approveSource(DAY);
    account(1, v);
    account(2, v);
    var b = create(v, DAY);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var read = new CountDownLatch(2);
      Callable<Boolean> task =
          () -> {
            ProductPolicyPersistenceIntegrationTest.tenant("one");
            setDay(DAY);
            ACTOR.set(1L);
            var transaction =
                new org.springframework.transaction.support.TransactionTemplate(
                    f.fixture.tx.getTransactionManager());
            transaction.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            try {
              return transaction.execute(
                  status -> {
                    service.get(b.id());
                    read.countDown();
                    try {
                      if (!read.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("Read timed out");
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      throw new IllegalStateException(e);
                    }
                    service.discover(b.id(), action(b), 1);
                    return true;
                  });
            } catch (org.springframework.dao.ConcurrencyFailureException
                | ProductPolicyService.Conflict e) {
              return false;
            } finally {
              ThreadLocalContextUtil.reset();
              ACTOR.remove();
            }
          };
      var first = pool.submit(task);
      var second = pool.submit(task);
      assertNotEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
    }
    assertEquals(1, service.get(b.id()).counters().totalDiscovered());
    assertEquals(2, seal(service.get(b.id())).counters().totalDiscovered());
  }

  @Test
  void tieredAndReferenceSourcesKeepExactVersionsAndDisabledFinancialPaths() {
    String matrix =
        "{\"calculationMethod\":\"UNAPPROVED\",\"brackets\":[{\"lowerBalance\":0,\"upperBalance\":null,\"annualRatePercent\":6}]}";
    tx(
        () ->
            f.drafts.save(
                1, policy(0, "TIERED").replace("}}", ",\"interestMatrix\":" + matrix + "}}")));
    f.submit(DAY);
    ACTOR.set(2L);
    f.decide(1, true);
    ACTOR.set(1L);
    account(1, 1L);
    var tiered = authorize(create(1, DAY));
    assertNotNull(tiered.source().policy().interestMatrix());
    var result = assess(tiered, 1);
    assertEquals("FINERACT_TIERED_EXECUTION_UNSUPPORTED", result.reason());
    tx(() -> service.cancel(result.id(), action(result)));
    tx(
        () ->
            rates.define(
                "{\"referenceId\":\"CETES_TEST\",\"referenceType\":\"CETES\",\"tenor\":\"TEST_TENOR\",\"sourceDescription\":\"Synthetic"
                    + " test quote\"}"));
    String formula = ReferenceRateValidationTest.FORMULA;
    tx(
        () ->
            f.drafts.save(
                1,
                policy(1, "REFERENCE_RATE").replace("}}", ",\"referenceRate\":" + formula + "}}")));
    f.submit(DAY.plusDays(1));
    ACTOR.set(2L);
    f.decide(2, true);
    ACTOR.set(1L);
    assertThrows(RuntimeException.class, () -> create(2, DAY.plusDays(1)));
    var fixing =
        tx(
            () ->
                rates.capture(
                    "CETES_TEST", ReferenceRateValidationTest.capture(0, DAY.plusDays(1), "8.25")));
    tx(() -> rates.submit("CETES_TEST", fixing.version(), "{\"expectedRevision\":0}"));
    ACTOR.set(2L);
    tx(() -> rates.decide("CETES_TEST", fixing.version(), true, "{\"expectedRevision\":1}"));
    ACTOR.set(1L);
    var ref = authorize(create(2, DAY.plusDays(1)));
    assertEquals(fixing.version(), ref.source().benchmarkFixing().version());
    assertEquals(0, new BigDecimal("9.75").compareTo(ref.source().nominalAnnualRatePreview()));
    assertFalse(ref.capabilities().referenceRateFinancialExecutionSupported());
    assertEquals(
        "INCOMPATIBLE_INTEREST_MODE",
        service.accounts(ref.id(), 0, 10).items().getFirst().eligibilityReason());
    assertEquals("PLANNING_COMPLETE", ref.completionMeaning());
  }
}
