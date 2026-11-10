/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.InterestMatrixValidationTest.*;
import static org.apache.fineract.savings.productpolicy.ProductPolicyWorkflowIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.*;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.access.AccessDeniedException;

/** Real tenant-routed PostgreSQL persistence and existing independent authorization engine. */
class InterestMatrixWorkflowIntegrationTest {
  ProductPolicyWorkflowIntegrationTest f;
  InterestMatrixService service;

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
    service =
        new InterestMatrixService(
            f.drafts,
            f.workflow,
            new ProductPolicyCoreSnapshot(f.jdbc),
            new ProductPolicyValidation());
  }

  @AfterEach
  void clear() {
    f.clear();
  }

  static String request(long revision, String matrix) {
    return "{\"expectedRevision\":" + revision + ",\"matrix\":" + matrix + "}";
  }

  void draft() {
    f.tx(() -> f.drafts.save(1, policy(0, MATRIX)));
  }

  void approve(long version) {
    ACTOR.set(2L);
    f.decide(version, true);
    ACTOR.set(1L);
  }

  @Test
  void coherentSnapshotAndMatrixContributorCannotApprove() {
    f.tx(() -> f.drafts.save(1, policy(0, null)));
    assertThrows(RuntimeException.class, () -> f.submit(DAY));
    ACTOR.set(3L);
    var updated = f.tx(() -> service.save(1, request(1, MATRIX)));
    assertEquals(2L, updated.revision());
    assertFalse(updated.financialOperational());
    assertEquals(
        "VALIDATED_METADATA", f.tx(() -> service.validate(1, request(2, MATRIX))).status());
    assertEquals(2, service.draft(1).revision()); // validation never saves
    ACTOR.set(1L);
    f.submit(DAY);
    ACTOR.set(3L);
    assertThrows(AccessDeniedException.class, () -> f.decide(2, true));
    ACTOR.set(2L);
    f.decide(2, true);
    assertEquals(
        matrix(MATRIX), f.tx(() -> f.workflow.review(1, 2)).proposed().policy().interestMatrix());
    assertEquals(
        List.of("DRAFT_CREATE", "DRAFT_EDIT", "SUBMIT", "APPROVE", "ACTIVATE"),
        f.tx(() -> f.workflow.audit(1)).stream()
            .map(ProductPolicyWorkflowData.Audit::action)
            .toList());
  }

  @ParameterizedTest
  @CsvSource({"0,0", "0.01,0", "999.99,0", "1000,0", "1000.01,1", "9999.99,1", "10000,1"})
  void exactBoundaryReviewUsesApprovedVersion(String balance, int index) {
    draft();
    f.submit(DAY);
    approve(1);
    var match = f.tx(() -> service.selectForReview(1, d(balance), DAY));
    assertEquals(index, match.bracketIndex());
    assertEquals(1, match.policyVersion());
    assertEquals("MXN", match.currencyCode());
    assertEquals(2, match.currencyDigits());
    assertFalse(match.financialOperational());
    assertEquals("CONFIGURATION_ONLY_UNAPPROVED_SEMANTICS", match.calculationStatus());
  }

  @Test
  void futureRejectedAndDraftMatricesNeverReplaceHistoricalApprovals() {
    draft();
    f.submit(DAY);
    approve(1);
    f.tx(() -> service.save(1, request(1, MATRIX.replace(":6}", ":7}"))));
    f.submit(DAY.plusDays(2));
    approve(2);
    assertEquals(
        0,
        d("6")
            .compareTo(
                f.tx(() -> service.selectForReview(1, d("2000"), null))
                    .bracket()
                    .annualRatePercent()));
    assertEquals(
        0,
        d("7")
            .compareTo(
                f.tx(() -> service.selectForReview(1, d("2000"), DAY.plusDays(2)))
                    .bracket()
                    .annualRatePercent()));
    f.tx(() -> service.save(1, request(2, MATRIX.replace(":6}", ":8}"))));
    f.submit(DAY.plusDays(3));
    ACTOR.set(2L);
    f.decide(3, false);
    ACTOR.set(1L);
    assertEquals(
        0,
        d("7")
            .compareTo(
                f.tx(() -> service.selectForReview(1, d("2000"), DAY.plusDays(4)))
                    .bracket()
                    .annualRatePercent()));
    setDay(DAY.plusDays(2));
    long events = f.tx(() -> f.workflow.audit(1)).size();
    service =
        new InterestMatrixService(
            f.drafts,
            f.workflow,
            new ProductPolicyCoreSnapshot(f.jdbc),
            new ProductPolicyValidation());
    assertEquals(
        0,
        d("6")
            .compareTo(
                f.tx(() -> service.selectForReview(1, d("2000"), DAY))
                    .bracket()
                    .annualRatePercent()));
    assertEquals(events, f.tx(() -> f.workflow.audit(1)).size());
    assertTrue(
        f.tx(() -> f.workflow.review(1, 3)).changedFields().contains("policy.interestMatrix"));
  }

  @Test
  void pendingImmutableStaleAndCoverageFailClosed() {
    draft();
    f.submit(DAY);
    assertThrows(
        ProductPolicyService.Conflict.class, () -> f.tx(() -> service.save(1, request(1, MATRIX))));
    assertThrows(
        RuntimeException.class, () -> f.tx(() -> service.selectForReview(1, d("2000"), DAY)));
    approve(1);
    assertThrows(
        ProductPolicyService.Conflict.class, () -> f.tx(() -> service.save(1, request(0, MATRIX))));
    for (String value : List.of("-0.01", "10000.01", "0.001"))
      assertThrows(
          RuntimeException.class, () -> f.tx(() -> service.selectForReview(1, d(value), DAY)));
    assertThrows(RuntimeException.class, service::requireFinancialCapability);
    var original = f.tx(() -> f.workflow.review(1, 1)).proposed().policy().interestMatrix();
    f.tx(() -> service.save(1, request(1, MATRIX.replace(":6}", ":7}"))));
    assertEquals(
        original, f.tx(() -> f.workflow.review(1, 1)).proposed().policy().interestMatrix());
  }

  @Test
  void tenantAndServicePermissionsCannotBeBypassed() {
    draft();
    f.submit(DAY);
    approve(1);
    ProductPolicyPersistenceIntegrationTest.tenant("two");
    assertNull(service.draft(1).matrix());
    assertThrows(
        RuntimeException.class, () -> f.tx(() -> service.selectForReview(1, d("2000"), DAY)));
    ProductPolicyPersistenceIntegrationTest.tenant("one");
    var user = f.security.authenticatedUser();
    doThrow(new AccessDeniedException("Denied"))
        .when(user)
        .validateHasPermissionTo(ProductPolicyService.WRITE);
    assertThrows(
        AccessDeniedException.class, () -> f.tx(() -> service.save(1, request(1, MATRIX))));
    assertThrows(
        AccessDeniedException.class, () -> f.tx(() -> service.validate(1, request(1, MATRIX))));
    doThrow(new AccessDeniedException("Denied"))
        .when(user)
        .validateHasPermissionTo(ProductPolicyService.READ);
    assertThrows(AccessDeniedException.class, () -> service.draft(1));
    assertThrows(
        AccessDeniedException.class, () -> f.tx(() -> service.selectForReview(1, d("2000"), DAY)));
  }

  @Test
  void approvedMatrixStillCannotOpenTieredAccounts() {
    draft();
    f.submit(DAY);
    approve(1);
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
    var failure =
        assertThrows(
            org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException
                .class,
            () ->
                f.tx(
                    () -> {
                      guard.validate(account, "CREATE_SAVINGSACCOUNT");
                      return null;
                    }));
    assertEquals("interestMode", failure.getErrors().getFirst().getParameterName());
    assertTrue(
        failure
            .getErrors()
            .getFirst()
            .getDefaultUserMessage()
            .contains("interest calculation engine"),
        failure.toString());
    assertEquals(
        0, f.jdbc.queryForObject("SELECT COUNT(*) FROM m_savings_account_policy", Integer.class));
  }

  @Test
  void parentPutCannotBypassValidationAndAuditFailureRollsBack() {
    draft();
    assertThrows(
        RuntimeException.class,
        () -> f.tx(() -> f.drafts.save(1, policy(1, MATRIX.replace("1000.01", "1000")))));
    assertEquals(1L, service.draft(1).revision());
    f.jdbc.execute(
        "ALTER TABLE m_savings_product_policy_audit ADD CONSTRAINT fail_matrix_edit CHECK"
            + " (action<>'DRAFT_EDIT')");
    try {
      assertThrows(RuntimeException.class, () -> f.tx(() -> service.save(1, request(1, MATRIX))));
    } finally {
      f.jdbc.execute("ALTER TABLE m_savings_product_policy_audit DROP CONSTRAINT fail_matrix_edit");
    }
    assertEquals(1L, service.draft(1).revision());
  }

  @Test
  void concurrentMatrixCreationHasOneWinnerAndOneSharedRevision() throws Exception {
    f.tx(() -> f.drafts.save(1, policy(0, null)));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<Boolean> task =
          () -> {
            ProductPolicyPersistenceIntegrationTest.tenant("one");
            setDay(DAY);
            ACTOR.set(1L);
            try {
              start.await();
              f.tx(() -> service.save(1, request(1, MATRIX)));
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
      assertNotEquals(a.get(), b.get());
    }
    assertEquals(2L, service.draft(1).revision());
    assertEquals(
        1,
        f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_product_policy_audit WHERE action='DRAFT_EDIT'",
            Integer.class));
  }
}
