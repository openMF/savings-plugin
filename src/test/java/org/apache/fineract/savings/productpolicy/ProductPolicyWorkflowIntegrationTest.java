/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyWorkflowData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/** Real two-tenant migrations, locking, snapshot immutability, and rollback verification. */
class ProductPolicyWorkflowIntegrationTest {
  static final LocalDate DAY = LocalDate.of(2026, 11, 1);
  static final ThreadLocal<Long> ACTOR = ThreadLocal.withInitial(() -> 1L);
  ProductPolicyPersistenceIntegrationTest fixture;
  ProductPolicyWorkflow workflow;
  ProductPolicyService drafts;
  JdbcTemplate jdbc;
  PlatformSecurityContext security;

  @BeforeAll
  static void database() throws Exception {
    ProductPolicyPersistenceIntegrationTest.database();
    for (var source : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(source);
      j.update("INSERT INTO m_appuser VALUES (2),(3),(4)");
      // Real core column names/defaults used by snapshot capture; the foundation fixture is
      // minimal.
      for (String column : ProductPolicyCoreSnapshot.COLUMNS.split(",")) {
        if (List.of(
                "currency_code",
                "currency_digits",
                "nominal_annual_interest_rate",
                "min_required_opening_balance")
            .contains(column)) continue;
        j.execute(
            "ALTER TABLE m_savings_product ADD COLUMN "
                + column
                + " "
                + (List.of(
                            "withdrawal_fee_for_transfer",
                            "allow_overdraft",
                            "enforce_min_required_balance",
                            "is_lien_allowed",
                            "withhold_tax")
                        .contains(column)
                    ? "boolean DEFAULT false"
                    : "numeric DEFAULT 0"));
      }
      j.execute("ALTER TABLE m_charge ADD COLUMN amount numeric DEFAULT 0");
      j.execute(
          "CREATE TABLE m_savings_product_charge(savings_product_id bigint,charge_id bigint)");
      j.execute(
          "CREATE TABLE acc_product_mapping(id bigint,product_id bigint,product_type integer)");
    }
  }

  @BeforeEach
  void setup() {
    fixture = new ProductPolicyPersistenceIntegrationTest();
    fixture.services();
    jdbc = fixture.jdbc;
    ACTOR.set(1L);
    setDay(DAY);
    security = mock(PlatformSecurityContext.class);
    var user = mock(AppUser.class);
    when(user.getId()).thenAnswer(i -> ACTOR.get());
    when(security.authenticatedUser()).thenReturn(user);
    var validation = new ProductPolicyValidation();
    drafts = new ProductPolicyService(security, jdbc, validation);
    workflow =
        new ProductPolicyWorkflow(security, jdbc, validation, new ProductPolicyCoreSnapshot(jdbc));
  }

  static void setDay(LocalDate d) {
    ThreadLocalContextUtil.setBusinessDates(
        new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, d)));
  }

  <T> T tx(java.util.function.Supplier<T> action) {
    return fixture.tx.execute(s -> action.get());
  }

  void draft() {
    tx(() -> drafts.save(1, ProductPolicyValidationTest.JSON));
  }

  Version submit(LocalDate date) {
    return tx(() -> workflow.submit(1, new Submit(drafts.get(1).revision(), date)));
  }

  Version decide(long v, boolean approve) {
    return tx(
        () ->
            workflow.decide(
                1, v, approve, new Decision(approve ? "Reviewed" : "Invalid configuration")));
  }

  void edit(long revision) {
    tx(
        () ->
            drafts.save(
                1,
                ProductPolicyValidationTest.JSON.replace(
                    "\"expectedRevision\":0", "\"expectedRevision\":" + revision)));
  }

  @AfterEach
  void clear() {
    ACTOR.remove();
    ThreadLocalContextUtil.reset();
  }

  @AfterAll
  static void cleanup() {
    ProductPolicyPersistenceIntegrationTest.cleanup();
  }

  @Test
  void independentApprovalEvenForPrivilegedUsersAndImmutableSubmittedDraft() {
    draft();
    var pending = submit(DAY);
    assertEquals("PENDING_APPROVAL", pending.status());
    assertNull(tx(() -> workflow.applicable(1, null)));
    assertThrows(ProductPolicyService.Conflict.class, () -> edit(1));
    assertThrows(AccessDeniedException.class, () -> decide(1, true));
    assertThrows(AccessDeniedException.class, () -> decide(1, false));
    assertThrows(ProductPolicyService.Conflict.class, () -> submit(DAY));
    ACTOR.set(2L);
    assertEquals(1, workflow.pending().size());
    assertEquals("ACTIVE", decide(1, true).status());
    assertThrows(ProductPolicyService.Conflict.class, () -> decide(1, true));
    assertThrows(ProductPolicyService.Conflict.class, () -> decide(1, false));
    assertEquals(2L, tx(() -> workflow.history(1)).getFirst().checkerId());
    assertEquals(
        List.of("DRAFT_CREATE", "SUBMIT", "APPROVE", "ACTIVATE"),
        tx(() -> workflow.audit(1)).stream().map(Audit::action).toList());
  }

  @Test
  void futureApprovalPreservesCurrentPolicyAndActivatesIdempotentlyAcrossRestart() {
    draft();
    submit(DAY);
    ACTOR.set(2L);
    decide(1, true);
    ACTOR.set(1L);
    edit(1);
    submit(DAY.plusDays(2));
    ACTOR.set(2L);
    assertEquals("APPROVED", decide(2, true).status());
    assertEquals(1, tx(() -> workflow.applicable(1, null)).version());
    assertEquals(2, tx(() -> workflow.applicable(1, DAY.plusDays(2))).version());
    assertEquals(
        1,
        tx(() -> workflow.applicable(1, null))
            .version()); // hypothetical date does not activate early
    setDay(DAY.plusDays(2));
    assertEquals(2, tx(() -> workflow.applicable(1, null)).version());
    assertEquals("SUPERSEDED", tx(() -> workflow.history(1)).getFirst().status());
    long count = tx(() -> workflow.audit(1)).size();
    workflow =
        new ProductPolicyWorkflow(
            security, jdbc, new ProductPolicyValidation(), new ProductPolicyCoreSnapshot(jdbc));
    tx(() -> workflow.applicable(1, null));
    assertEquals(count, tx(() -> workflow.audit(1)).size());
    assertEquals("ACTIVE", tx(() -> workflow.applicable(1, DAY)).status());
  }

  @Test
  void rejectionAndNewDraftDoNotReplaceApprovedSnapshot() {
    draft();
    submit(DAY);
    ACTOR.set(2L);
    decide(1, true);
    String original =
        jdbc.queryForObject(
            "SELECT policy_json FROM m_savings_product_policy_version WHERE savings_product_id=1"
                + " AND version=1",
            String.class);
    ACTOR.set(1L);
    edit(1);
    submit(DAY.plusDays(1));
    ACTOR.set(2L);
    assertEquals("REJECTED", decide(2, false).status());
    assertThrows(ProductPolicyService.Conflict.class, () -> decide(2, true));
    ACTOR.set(1L);
    edit(2);
    assertEquals(1, tx(() -> workflow.applicable(1, null)).version());
    assertEquals(
        original,
        jdbc.queryForObject(
            "SELECT policy_json FROM m_savings_product_policy_version WHERE savings_product_id=1"
                + " AND version=1",
            String.class));
    assertNotNull(tx(() -> workflow.history(1)).get(1).reason());
  }

  @Test
  void retroactiveDatesAndElapsedApprovalAreRejected() {
    draft();
    assertThrows(RuntimeException.class, () -> submit(DAY.minusDays(1)));
    submit(DAY);
    ACTOR.set(2L);
    setDay(DAY.plusDays(1));
    assertThrows(
        org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException.class,
        () -> decide(1, true));
    assertNull(tx(() -> workflow.applicable(1, null)));
  }

  @Test
  void coreDriftAndConflictingSchedulesFailClosed() {
    draft();
    submit(DAY);
    jdbc.update("UPDATE m_savings_product SET nominal_annual_interest_rate=1 WHERE id=1");
    ACTOR.set(2L);
    assertThrows(RuntimeException.class, () -> decide(1, true));
    jdbc.update("UPDATE m_savings_product SET nominal_annual_interest_rate=0 WHERE id=1");
    decide(1, true);
    ACTOR.set(1L);
    edit(1);
    submit(DAY);
    ACTOR.set(2L);
    assertThrows(ProductPolicyService.Conflict.class, () -> decide(2, true));
    assertEquals(1, tx(() -> workflow.applicable(1, null)).version());
  }

  @Test
  void crossTenantAndMissingPermissionsAreEnforcedInsideServices() {
    draft();
    submit(DAY);
    ProductPolicyPersistenceIntegrationTest.tenant("two");
    assertTrue(tx(() -> workflow.history(1)).isEmpty());
    assertThrows(ProductPolicyService.NotFound.class, () -> decide(1, true));
    ProductPolicyPersistenceIntegrationTest.tenant("one");
    var user = security.authenticatedUser();
    doThrow(new AccessDeniedException("Denied"))
        .when(user)
        .validateHasPermissionTo(ProductPolicyWorkflow.CHECK);
    assertThrows(AccessDeniedException.class, () -> decide(1, true));
    assertThrows(AccessDeniedException.class, workflow::pending);
    doThrow(new AccessDeniedException("Denied"))
        .when(user)
        .validateHasPermissionTo(ProductPolicyService.READ);
    assertThrows(AccessDeniedException.class, () -> tx(() -> workflow.audit(1)));
    doThrow(new AccessDeniedException("Denied"))
        .when(user)
        .validateHasPermissionTo(ProductPolicyWorkflow.SUBMIT);
    assertThrows(AccessDeniedException.class, () -> submit(DAY));
  }

  @Test
  void approvalRejectionRaceExecutesExactlyOneDecision() throws Exception {
    draft();
    submit(DAY);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      java.util.function.Function<Boolean, Callable<Boolean>> task =
          approve ->
              () -> {
                ProductPolicyPersistenceIntegrationTest.tenant("one");
                setDay(DAY);
                ACTOR.set(approve ? 2L : 3L);
                try {
                  start.await();
                  decide(1, approve);
                  return true;
                } catch (ProductPolicyService.Conflict e) {
                  return false;
                } finally {
                  ThreadLocalContextUtil.reset();
                  ACTOR.remove();
                }
              };
      var a = workers.submit(task.apply(true));
      var b = workers.submit(task.apply(false));
      start.countDown();
      assertNotEquals(a.get(), b.get());
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_savings_product_policy_audit WHERE action IN"
                + " ('APPROVE','REJECT')",
            Integer.class));
  }

  @Test
  void concurrentSubmissionsAndDraftRevisionCheckPreventDuplicateVersions() throws Exception {
    draft();
    try (var workers = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<Boolean> task =
          () -> {
            ProductPolicyPersistenceIntegrationTest.tenant("one");
            setDay(DAY);
            try {
              start.await();
              submit(DAY);
              return true;
            } catch (ProductPolicyService.Conflict e) {
              return false;
            } finally {
              ThreadLocalContextUtil.reset();
            }
          };
      var a = workers.submit(task);
      var b = workers.submit(task);
      start.countDown();
      assertNotEquals(a.get(), b.get());
    }
    assertEquals(1, tx(() -> workflow.history(1)).size());
  }

  @Test
  void failedAuditInsertRollsBackApprovalAndSnapshotRemainsPending() {
    draft();
    submit(DAY);
    ACTOR.set(2L);
    jdbc.execute(
        "ALTER TABLE m_savings_product_policy_audit ADD CONSTRAINT test_fail_approve CHECK"
            + " (action<>'APPROVE')");
    try {
      assertThrows(RuntimeException.class, () -> decide(1, true));
    } finally {
      jdbc.execute("ALTER TABLE m_savings_product_policy_audit DROP CONSTRAINT test_fail_approve");
    }
    assertEquals("PENDING_APPROVAL", tx(() -> workflow.history(1)).getFirst().status());
    assertNull(tx(() -> workflow.applicable(1, null)));
  }

  @Test
  void intermediateContributorsCannotListOrApproveTheirProposal() {
    draft();
    ACTOR.set(2L);
    edit(1);
    ACTOR.set(3L);
    edit(2);
    submit(DAY);
    ACTOR.set(2L);
    assertTrue(workflow.pending().isEmpty());
    assertThrows(AccessDeniedException.class, () -> decide(3, true));
    ACTOR.set(4L);
    assertEquals(1, workflow.pending().size());
    assertEquals("ACTIVE", decide(3, true).status());
  }
}
