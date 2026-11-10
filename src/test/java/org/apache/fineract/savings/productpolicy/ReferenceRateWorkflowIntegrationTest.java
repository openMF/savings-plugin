/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyWorkflowIntegrationTest.*;
import static org.apache.fineract.savings.productpolicy.ReferenceRateData.*;
import static org.apache.fineract.savings.productpolicy.ReferenceRateValidationTest.FORMULA;
import static org.apache.fineract.savings.productpolicy.ReferenceRateValidationTest.policy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * Real PostgreSQL tenant routing, Liquibase upgrade, constraints, locking, review and atomic
 * rollback.
 */
class ReferenceRateWorkflowIntegrationTest {
  ProductPolicyWorkflowIntegrationTest f;
  ReferenceRateService rates;
  ReferenceRateFormulaService formulas;
  static final String REF = "CETES_TEST";

  @BeforeAll
  static void database() throws Exception {
    ProductPolicyWorkflowIntegrationTest.database();
  }

  @AfterAll
  static void cleanup() {
    ProductPolicyWorkflowIntegrationTest.cleanup();
  }

  @BeforeEach
  void setup() {
    f = new ProductPolicyWorkflowIntegrationTest();
    f.setup();
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values())
      new JdbcTemplate(ds).execute("TRUNCATE m_savings_reference_rate CASCADE");
    rates = new ReferenceRateService(f.workflow, f.jdbc);
    formulas = new ReferenceRateFormulaService(f.drafts, f.workflow, rates);
    define();
  }

  @AfterEach
  void clear() {
    f.clear();
  }

  void define() {
    f.tx(
        () ->
            rates.define(
                "{\"referenceId\":\"CETES_TEST\",\"referenceType\":\"CETES\",\"tenor\":\"TEST_TENOR\",\"sourceDescription\":\"Unapproved"
                    + " illustrative test source\"}"));
  }

  Fixing capture(long revision, LocalDate date, String rate) {
    return f.tx(
        () -> rates.capture(REF, ReferenceRateValidationTest.capture(revision, date, rate)));
  }

  Fixing submit(long version) {
    return f.tx(() -> rates.submit(REF, version, "{\"expectedRevision\":0}"));
  }

  Fixing decide(long version, boolean approve) {
    return f.tx(
        () ->
            rates.decide(
                REF,
                version,
                approve,
                "{\"expectedRevision\":1,\"reason\":\"Independent review\"}"));
  }

  void approve(long version) {
    ACTOR.set(2L);
    decide(version, true);
    ACTOR.set(1L);
  }

  void approvedFixing() {
    capture(0, DAY, "8.25");
    submit(1);
    approve(1);
  }

  void approvedFormula() {
    f.tx(() -> f.drafts.save(1, policy(0, FORMULA)));
    f.submit(DAY);
    ACTOR.set(2L);
    f.decide(1, true);
    ACTOR.set(1L);
  }

  @Test
  void manualCaptureSubmissionIndependentApprovalAndAudit() {
    var draft = capture(0, DAY, "8.25");
    assertEquals(Status.DRAFT, draft.status());
    assertEquals(1, draft.makerId());
    assertNull(draft.checkerId());
    assertThrows(RuntimeException.class, () -> rates.resolve(REF, "TEST_TENOR", DAY));
    ACTOR.set(3L);
    var pending = submit(1);
    assertEquals(3L, pending.submitterId());
    for (long contributor : List.of(1L, 3L)) {
      ACTOR.set(contributor);
      assertTrue(rates.pending().isEmpty());
      assertThrows(AccessDeniedException.class, () -> decide(1, true));
    }
    ACTOR.set(2L);
    assertEquals(1, rates.pending().size());
    var approved = decide(1, true);
    assertEquals(Status.APPROVED, approved.status());
    assertEquals(2, approved.revision());
    assertEquals(2L, approved.checkerId());
    assertNotNull(approved.decidedAt());
    assertEquals(
        0,
        new BigDecimal("8.25")
            .compareTo(rates.resolve(REF, "TEST_TENOR", DAY).annualRatePercent()));
    assertEquals(
        List.of("DEFINE", "CAPTURE", "SUBMIT", "APPROVE"),
        rates.audit(REF).stream().map(Audit::action).toList());
    assertThrows(ProductPolicyService.Conflict.class, () -> decide(1, true));
  }

  @Test
  void rejectRetainsReasonAndNeverResolves() {
    capture(0, DAY, "8.25");
    submit(1);
    ACTOR.set(2L);
    var rejected = decide(1, false);
    assertEquals(Status.REJECTED, rejected.status());
    assertEquals("Independent review", rejected.reason());
    assertThrows(RuntimeException.class, () -> rates.resolve(REF, "TEST_TENOR", DAY));
    ACTOR.set(1L);
    assertEquals(2, capture(1, DAY, "8.30").version());
    assertEquals(2, rates.history(REF).size());
  }

  @Test
  void historicalFutureMissingAndStaleValuesRemainSeparate() {
    approvedFixing();
    capture(1, DAY.plusDays(1), "8.50");
    submit(2);
    assertThrows(RuntimeException.class, () -> rates.resolve(REF, "TEST_TENOR", DAY.plusDays(1)));
    approve(2);
    assertEquals(1L, rates.resolve(REF, "TEST_TENOR", DAY).version());
    assertEquals(2L, rates.resolve(REF, "TEST_TENOR", DAY.plusDays(1)).version());
    assertThrows(RuntimeException.class, () -> rates.resolve(REF, "TEST_TENOR", DAY.minusDays(1)));
    assertThrows(RuntimeException.class, () -> rates.resolve(REF, "TEST_TENOR", DAY.plusDays(2)));
    assertThrows(RuntimeException.class, () -> rates.resolve(REF, "OTHER_TENOR", DAY));
    setDay(DAY.plusDays(1));
    assertEquals(2, rates.resolve(REF, "TEST_TENOR", null).version());
    assertEquals(1L, rates.history(REF).getFirst().version());
    assertEquals(1L, rates.history(REF).get(1).baseVersion());
  }

  @Test
  void formulaAndFixingResolveTheirOwnVersionsWithoutFinancialActivation() {
    approvedFixing();
    approvedFormula();
    var first = f.tx(() -> formulas.preview(1, DAY));
    assertEquals(0, new BigDecimal("9.75").compareTo(first.calculatedAnnualRatePercent()));
    assertEquals(1, first.policyVersion());
    assertEquals(1, first.benchmarkVersion());
    assertFalse(first.financialOperational());
    String factor =
        FORMULA
            .replace("ADDITIVE_SPREAD", "MULTIPLICATIVE_FACTOR")
            .replace("spreadPercentagePoints", "multiplier")
            .replace("1.50", "1.20")
            .replace("DAILY", "END_OF_MONTH");
    f.tx(() -> formulas.save(1, "{\"expectedRevision\":1,\"formula\":" + factor + "}"));
    f.submit(DAY.plusDays(1));
    assertEquals(1, f.tx(() -> formulas.preview(1, DAY)).policyVersion());
    ACTOR.set(2L);
    f.decide(2, true);
    ACTOR.set(1L);
    capture(1, DAY.plusDays(1), "8.50");
    submit(2);
    approve(2);
    var second = f.tx(() -> formulas.preview(1, DAY.plusDays(1)));
    assertEquals(0, new BigDecimal("10.20").compareTo(second.calculatedAnnualRatePercent()));
    assertEquals(2, second.policyVersion());
    assertEquals(2, second.benchmarkVersion());
    assertEquals(ReviewFrequency.END_OF_MONTH, second.reviewFrequency());
    assertEquals(first, f.tx(() -> formulas.preview(1, DAY)));
    assertThrows(RuntimeException.class, () -> f.tx(() -> formulas.preview(1, DAY.plusDays(2))));
    assertThrows(RuntimeException.class, formulas::requireFinancialCapability);
    assertEquals(
        0,
        f.jdbc
            .queryForObject(
                "SELECT nominal_annual_interest_rate FROM m_savings_product WHERE id=1",
                BigDecimal.class)
            .signum());
    assertEquals(
        0, f.jdbc.queryForObject("SELECT COUNT(*) FROM m_savings_account_policy", Integer.class));
  }

  @Test
  void parentPutUnknownDefinitionAndWrongTenorFailClosed() {
    assertThrows(
        RuntimeException.class,
        () -> f.tx(() -> f.drafts.save(1, policy(0, FORMULA.replace("CETES_TEST", "UNKNOWN")))));
    assertThrows(
        RuntimeException.class,
        () -> f.tx(() -> f.drafts.save(1, policy(0, FORMULA.replace("TEST_TENOR", "OTHER")))));
    f.tx(() -> f.drafts.save(1, policy(0, null)));
    assertThrows(RuntimeException.class, () -> f.submit(DAY));
    assertThrows(RuntimeException.class, () -> capture(0, DAY, "-1"));
    assertThrows(
        RuntimeException.class,
        () ->
            f.tx(
                () ->
                    rates.capture(
                        REF,
                        ReferenceRateValidationTest.capture(0, DAY, "8.25")
                            .replace("TEST_TENOR", "OTHER"))));
    assertEquals(0, rates.history(REF).size());
  }

  @Test
  void formulaEditSharesDraftRevisionAndContributorExclusion() {
    f.tx(() -> f.drafts.save(1, policy(0, null)));
    ACTOR.set(3L);
    var updated =
        f.tx(() -> formulas.save(1, "{\"expectedRevision\":1,\"formula\":" + FORMULA + "}"));
    assertEquals(2L, updated.revision());
    ACTOR.set(1L);
    f.submit(DAY);
    ACTOR.set(3L);
    assertThrows(AccessDeniedException.class, () -> f.decide(2, true));
    ACTOR.set(2L);
    f.decide(2, true);
    assertNotNull(f.tx(() -> f.workflow.review(1, 2)).proposed().policy().referenceRate());
    ACTOR.set(1L);
    assertThrows(
        ProductPolicyService.Conflict.class,
        () -> f.tx(() -> formulas.save(1, "{\"expectedRevision\":1,\"formula\":" + FORMULA + "}")));
    assertEquals(
        REF,
        f.jdbc.queryForObject(
            "SELECT reference_rate_id FROM m_savings_product_policy_version WHERE"
                + " savings_product_id=1 AND version=2",
            String.class));
  }

  @Test
  void benchmarkApprovalDoesNotApproveProductAndProductApprovalDoesNotApproveBenchmark() {
    capture(0, DAY, "8.25");
    submit(1);
    approvedFormula();
    assertThrows(RuntimeException.class, () -> f.tx(() -> formulas.preview(1, DAY)));
    approve(1);
    assertNotNull(f.tx(() -> formulas.preview(1, DAY)));
    f.tx(() -> f.drafts.save(2, policy(0, FORMULA)));
    assertThrows(RuntimeException.class, () -> f.tx(() -> formulas.preview(2, DAY)));
  }

  @Test
  void effectiveDateElapsedAtSubmissionOrReviewIsRejected() {
    capture(0, DAY, "8.25");
    setDay(DAY.plusDays(1));
    assertThrows(RuntimeException.class, () -> submit(1));
    setDay(DAY);
    submit(1);
    setDay(DAY.plusDays(1));
    ACTOR.set(2L);
    assertThrows(RuntimeException.class, () -> decide(1, true));
    assertEquals(Status.PENDING_APPROVAL, rates.get(REF, 1).status());
    decide(1, false);
  }

  @Test
  void staleBaseVersionAndOutOfOrderApprovalsAreRejected() {
    capture(0, DAY, "8.25");
    capture(1, DAY.plusDays(1), "8.50");
    submit(1);
    submit(2);
    approve(2);
    ACTOR.set(2L);
    assertThrows(ProductPolicyService.Conflict.class, () -> decide(1, true));
    decide(1, false);
    ACTOR.set(1L);
    capture(2, DAY, "8.30");
    submit(3);
    ACTOR.set(2L);
    assertThrows(ProductPolicyService.Conflict.class, () -> decide(3, true));
    assertEquals(2L, rates.resolve(REF, "TEST_TENOR", DAY.plusDays(1)).version());
  }

  @Test
  void duplicateCaptureStaleActionsAndRealUniqueConstraints() {
    capture(0, DAY, "8.25");
    assertThrows(ProductPolicyService.Conflict.class, () -> capture(0, DAY.plusDays(1), "8.25"));
    assertThrows(ProductPolicyService.Conflict.class, () -> capture(1, DAY, "8.25"));
    assertThrows(
        RuntimeException.class, () -> f.tx(() -> rates.submit(REF, 1, "{\"expectedRevision\":1}")));
    submit(1);
    assertThrows(ProductPolicyService.Conflict.class, () -> submit(1));
    ACTOR.set(2L);
    assertThrows(
        ProductPolicyService.Conflict.class,
        () -> f.tx(() -> rates.decide(REF, 1, true, "{\"expectedRevision\":0}")));
    approve(1);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            f.jdbc.update(
                "INSERT INTO m_savings_reference_rate SELECT * FROM m_savings_reference_rate WHERE"
                    + " reference_id=?",
                REF));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            f.jdbc.update(
                "INSERT INTO m_savings_reference_fixing SELECT * FROM m_savings_reference_fixing"
                    + " WHERE reference_id=? AND version=1",
                REF));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            f.jdbc.update(
                "INSERT INTO"
                    + " m_savings_reference_fixing(reference_id,version,revision,status,annual_rate_percent,publication_date,effective_date,source_reference,note,base_version,maker_id,submitter_id,checker_id,captured_at,submitted_at,decided_at,reason,open_effective_date,approved_effective_date)"
                    + " SELECT"
                    + " reference_id,version+10,revision,status,annual_rate_percent,publication_date,effective_date,source_reference,note,base_version,maker_id,submitter_id,checker_id,captured_at,submitted_at,decided_at,reason,open_effective_date,approved_effective_date"
                    + " FROM m_savings_reference_fixing WHERE version=1"));
    assertEquals(1, rates.history(REF).size());
  }

  @Test
  void approvedRejectedObservationsDefinitionsAndAuditCannotBeMutatedBySql() {
    approvedFixing();
    for (String sql :
        List.of(
            "UPDATE m_savings_reference_fixing SET annual_rate_percent=10",
            "DELETE FROM m_savings_reference_fixing",
            "UPDATE m_savings_reference_audit SET reason='rewrite'",
            "DELETE FROM m_savings_reference_audit",
            "UPDATE m_savings_reference_rate SET tenor='OTHER'"))
      assertThrows(RuntimeException.class, () -> f.jdbc.update(sql));
    capture(1, DAY.plusDays(1), "8.30");
    submit(2);
    ACTOR.set(2L);
    decide(2, false);
    assertThrows(
        RuntimeException.class,
        () ->
            f.jdbc.update("UPDATE m_savings_reference_fixing SET note='changed' WHERE version=2"));
    assertEquals(0, new BigDecimal("8.25").compareTo(rates.get(REF, 1).annualRatePercent()));
  }

  @Test
  void failedAuditRollsBackCaptureAndApproval() {
    f.jdbc.execute(
        "ALTER TABLE m_savings_reference_audit ADD CONSTRAINT test_fail CHECK (action<>'CAPTURE')");
    try {
      assertThrows(RuntimeException.class, () -> capture(0, DAY, "8.25"));
    } finally {
      f.jdbc.execute("ALTER TABLE m_savings_reference_audit DROP CONSTRAINT test_fail");
    }
    assertEquals(0, rates.definition(REF).revision());
    assertTrue(rates.history(REF).isEmpty());
    capture(0, DAY, "8.25");
    submit(1);
    ACTOR.set(2L);
    f.jdbc.execute(
        "ALTER TABLE m_savings_reference_audit ADD CONSTRAINT test_fail CHECK (action<>'APPROVE')");
    try {
      assertThrows(RuntimeException.class, () -> decide(1, true));
    } finally {
      f.jdbc.execute("ALTER TABLE m_savings_reference_audit DROP CONSTRAINT test_fail");
    }
    assertEquals(Status.PENDING_APPROVAL, rates.get(REF, 1).status());
    assertThrows(RuntimeException.class, () -> rates.resolve(REF, "TEST_TENOR", DAY));
  }

  @Test
  void tenantRoutingAndEveryServicePermissionAreMandatory() {
    approvedFixing();
    approvedFormula();
    ProductPolicyPersistenceIntegrationTest.tenant("two");
    assertTrue(rates.definitions().isEmpty());
    assertThrows(ReferenceRateService.NotFound.class, () -> rates.history(REF));
    ACTOR.set(2L);
    assertThrows(ReferenceRateService.NotFound.class, () -> decide(1, true));
    assertThrows(RuntimeException.class, () -> f.tx(() -> formulas.preview(1, DAY)));
    ProductPolicyPersistenceIntegrationTest.tenant("one");
    ACTOR.set(1L);
    var user = f.security.authenticatedUser();
    for (String permission :
        List.of(
            ReferenceRateService.READ,
            ReferenceRateService.DEFINE,
            ReferenceRateService.CAPTURE,
            ReferenceRateService.SUBMIT,
            ReferenceRateService.CHECK,
            ProductPolicyService.WRITE,
            ProductPolicyService.READ))
      doThrow(new AccessDeniedException("Denied")).when(user).validateHasPermissionTo(permission);
    assertThrows(AccessDeniedException.class, rates::template);
    assertThrows(AccessDeniedException.class, rates::definitions);
    assertThrows(AccessDeniedException.class, () -> rates.definition(REF));
    assertThrows(AccessDeniedException.class, () -> rates.get(REF, 1));
    assertThrows(AccessDeniedException.class, () -> rates.audit(REF));
    assertThrows(AccessDeniedException.class, () -> rates.history(REF));
    assertThrows(AccessDeniedException.class, rates::pending);
    assertThrows(AccessDeniedException.class, () -> rates.resolve(REF, "TEST_TENOR", DAY));
    assertThrows(AccessDeniedException.class, this::define);
    assertThrows(AccessDeniedException.class, () -> capture(1, DAY.plusDays(1), "8.5"));
    assertThrows(AccessDeniedException.class, () -> submit(1));
    assertThrows(AccessDeniedException.class, () -> decide(1, true));
    assertThrows(AccessDeniedException.class, () -> formulas.save(1, "{}"));
    assertThrows(AccessDeniedException.class, () -> formulas.draft(1));
    assertThrows(AccessDeniedException.class, () -> formulas.preview(1, DAY));
    reset(user);
    when(user.getId()).thenReturn(1L);
    ThreadLocalContextUtil.clearTenant();
    assertThrows(AccessDeniedException.class, rates::definitions);
  }

  @Test
  void concurrentCaptureAndApprovalHaveExactlyOneWinner() throws Exception {
    race(
        () -> {
          capture(0, DAY, "8.25");
          return true;
        });
    assertEquals(1, rates.history(REF).size());
    submit(1);
    race(
        () -> {
          ACTOR.set(2L);
          decide(1, true);
          return true;
        });
    assertEquals(Status.APPROVED, rates.get(REF, 1).status());
    assertEquals(1, rates.audit(REF).stream().filter(a -> a.action().equals("APPROVE")).count());
  }

  void race(Callable<Boolean> operation) throws Exception {
    try (var workers = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<Boolean> task =
          () -> {
            ProductPolicyPersistenceIntegrationTest.tenant("one");
            setDay(DAY);
            ACTOR.set(1L);
            try {
              start.await();
              return operation.call();
            } catch (ProductPolicyService.Conflict e) {
              return false;
            } finally {
              ThreadLocalContextUtil.reset();
              ACTOR.remove();
            }
          };
      var a = workers.submit(task);
      var b = workers.submit(task);
      start.countDown();
      assertNotEquals(a.get(), b.get());
    }
  }

  @Test
  void staleRepeatableReadSnapshotCannotCaptureASecondRevision() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(2);
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
                    f.jdbc.queryForObject(
                        "SELECT revision FROM m_savings_reference_rate WHERE reference_id=?",
                        Long.class,
                        REF);
                    start.countDown();
                    try {
                      if (!start.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("Concurrent read timed out");
                    } catch (InterruptedException e) {
                      throw new RuntimeException(e);
                    }
                    capture(0, DAY, "8.25");
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
      var a = pool.submit(task);
      var b = pool.submit(task);
      assertNotEquals(a.get(), b.get());
    }
    assertEquals(1, rates.history(REF).size());
    assertEquals(1, rates.definition(REF).revision());
  }

  @Test
  void monthBoundaryDoesNotInventAnObservationOrCarryForwardPolicy() {
    approvedFixing();
    approvedFormula();
    for (var date : List.of(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 1)))
      assertThrows(RuntimeException.class, () -> f.tx(() -> formulas.preview(1, date)));
    assertFalse(rates.template().reviewSchedulingOperational());
  }

  @Test
  void referenceAccountOpeningStillFailsBeforeBinding() {
    approvedFormula();
    var core = mock(ProductPolicyCoreSnapshot.class);
    var snapshot = new ProductPolicyCoreSnapshot(f.jdbc).capture(1);
    when(core.capture(1)).thenReturn(snapshot);
    var guard =
        new ProductPolicyOpeningEnforcement(
            f.workflow,
            core,
            f.jdbc,
            new ProductPolicyValidation(),
            mock(MainAccountAuditService.class));
    var account = mock(org.apache.fineract.portfolio.savings.domain.SavingsAccount.class);
    when(account.productId()).thenReturn(1L);
    when(account.getId()).thenReturn(9L);
    var error =
        assertThrows(
            org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException
                .class,
            () ->
                f.tx(
                    () -> {
                      guard.validate(account, "CREATE_SAVINGSACCOUNT");
                      return null;
                    }));
    assertEquals("interestMode", error.getErrors().getFirst().getParameterName());
    assertEquals(
        0, f.jdbc.queryForObject("SELECT COUNT(*) FROM m_savings_account_policy", Integer.class));
  }

  @Test
  void tinyDecimalsAndZeroSurviveFormulaFacadeRoundTrips() {
    f.tx(() -> f.drafts.save(1, policy(0, null)));
    String tiny = FORMULA.replace("1.50", "0.000000000001");
    f.tx(() -> formulas.save(1, "{\"expectedRevision\":1,\"formula\":" + tiny + "}"));
    assertEquals(
        new BigDecimal("0.000000000001"), formulas.draft(1).formula().spreadPercentagePoints());
    assertTrue(
        f.jdbc
            .queryForObject(
                "SELECT policy_json FROM m_savings_product_policy WHERE savings_product_id=1",
                String.class)
            .contains("0.000000000001"));
    f.tx(
        () ->
            formulas.save(
                1,
                "{\"expectedRevision\":2,\"formula\":"
                    + FORMULA.replace("1.50", "0.000000000000")
                    + "}"));
    f.submit(DAY);
    ACTOR.set(2L);
    f.decide(3, true);
    ACTOR.set(1L);
    approvedFixing();
    assertEquals(
        0,
        new BigDecimal("8.25")
            .compareTo(f.tx(() -> formulas.preview(1, DAY)).calculatedAnnualRatePercent()));
  }

  @Test
  void legacyPendingReferencePolicyWithoutFormulaCannotBeApprovedAfterUpgrade() {
    f.tx(() -> f.drafts.save(1, policy(0, FORMULA)));
    f.submit(DAY);
    // Model a pending pre-BE-04 snapshot: it had a mode but no reference formula field.
    var legacy = ProductPolicyValidation.object(policy(0, FORMULA)).getAsJsonObject("policy");
    legacy.remove("referenceRate");
    f.jdbc.update(
        "UPDATE m_savings_product_policy_version SET policy_json=?,reference_rate_id=NULL WHERE"
            + " savings_product_id=1 AND version=1",
        legacy.toString());
    ACTOR.set(2L);
    assertThrows(RuntimeException.class, () -> f.decide(1, true));
    assertNull(f.tx(() -> f.workflow.applicable(1, DAY)));
    assertEquals("PENDING_APPROVAL", f.tx(() -> f.workflow.history(1)).getFirst().status());
  }
}
