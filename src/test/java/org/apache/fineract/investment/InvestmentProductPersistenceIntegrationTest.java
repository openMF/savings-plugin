/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentProductFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

class InvestmentProductPersistenceIntegrationTest extends InvestmentProductDatabaseSupport {
  @Test
  void migrationCreationUpdateNewSessionHistoryAndNativeIntegrity() {
    String before = coreSnapshot();
    assertEquals(InvestmentProductData.Status.UNCONFIGURED, service.get(product).status());
    assertEquals(1, service.save(product, JSON).revision());
    var read = service.get(product);
    assertTrue(read.validation().policyValidated());
    assertTrue(read.validation().accountingMappingValidated());
    assertFalse(read.capabilities().financialExecutionSupported());
    service.save(product, request(1, Map.of("capitalizationEligible", true)));
    assertEquals(2, service.get(product).revision());
    var history = service.history(product, 0, 50);
    assertEquals(2, history.size());
    assertEquals(List.of("capitalizationEligible"), history.getFirst().changedFields());
    assertEquals(1, history.getFirst().previousRevision());
    assertEquals(2, history.getFirst().revision());
    assertEquals(1, history.getFirst().actorId());
    assertEquals("2026-11-17", history.getFirst().businessDate());
    assertTrue(history.getFirst().recordedAtUtc().endsWith("Z"));
    assertFalse(history.getFirst().previousConfiguration().capitalizationEligible());
    assertTrue(history.getFirst().newConfiguration().capitalizationEligible());
    assertEquals(before, coreSnapshot());
    assertEquals(0, service.get(other).revision());
  }

  @Test
  void staleAndInvalidChangesNeverOverwriteOrAudit() {
    service.save(product, JSON);
    assertThrows(InvestmentProductService.Conflict.class, () -> service.save(product, JSON));
    assertThrows(InvestmentProductService.Conflict.class, () -> service.validate(product, JSON));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.save(product, request(1, Map.of("maximumTermDays", 1826))));
    assertEquals(1, service.get(product).revision());
    assertEquals(1, service.history(product, 0, 50).size());
    assertTrue(service.validate(product, request(1, Map.of())).validation().policyValidated());
    assertEquals(1, service.history(product, 0, 50).size());
  }

  @Test
  void nativeClassificationExistenceAndTenantBoundaries() {
    for (long id : new long[] {ordinary, recurring, 999999999}) {
      assertThrows(InvestmentProductService.NotFound.class, () -> service.save(id, JSON));
      assertThrows(InvestmentProductService.NotFound.class, () -> service.get(id));
    }
    assertThrows(PlatformApiDataValidationException.class, () -> service.get(0));
    service.save(product, JSON);
    service.save(other, request(0, Map.of("capitalizationEligible", true)));
    tenant("two");
    assertEquals(0, service.get(product).revision());
    service.save(
        product,
        request(
            0,
            Map.of(
                "investmentType",
                "CEDE",
                "minimumTermDays",
                60,
                "maximumTermDays",
                180,
                "termMultipleDays",
                30)));
    assertEquals(
        InvestmentProductData.InvestmentType.CEDE, service.get(product).policy().investmentType());
    tenant("one");
    assertEquals(
        InvestmentProductData.InvestmentType.PROMISSORY_NOTE,
        service.get(product).policy().investmentType());
    assertFalse(service.get(product).policy().capitalizationEligible());
    assertTrue(service.get(other).policy().capitalizationEligible());
  }

  @Test
  void failedAuditInsertRollsBackPolicyAtomically() {
    service.save(product, JSON);
    // A transient test constraint forces the audit insert to fail after the policy update.
    jdbc.execute(
        "ALTER TABLE m_investment_product_policy_audit ADD CONSTRAINT test_reject_revision CHECK (product_id<>"
            + product
            + " OR revision=1)");
    try {
      assertThrows(
          DataIntegrityViolationException.class,
          () -> service.save(product, request(1, Map.of("capitalizationEligible", true))));
      assertEquals(1, service.get(product).revision());
      assertFalse(service.get(product).policy().capitalizationEligible());
      assertEquals(1, service.history(product, 0, 50).size());
    } finally {
      jdbc.execute(
          "ALTER TABLE m_investment_product_policy_audit DROP CONSTRAINT test_reject_revision");
    }
  }

  @Test
  void auditIsAppendOnlyAndDatabaseConstraintsFailClosed() {
    service.save(product, JSON);
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE m_investment_product_policy_audit SET operation='UPDATE_DRAFT' WHERE product_id=?",
                product));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "DELETE FROM m_investment_product_policy_audit WHERE product_id=?", product));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE m_investment_product_policy SET revision=0 WHERE product_id=?", product));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE m_investment_product_policy SET status='ACTIVE' WHERE product_id=?",
                product));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE m_investment_product_policy SET investment_type='UNKNOWN' WHERE product_id=?",
                product));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("DELETE FROM m_appuser WHERE id=1"));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO m_investment_product_policy SELECT * FROM m_investment_product_policy WHERE product_id=?",
                product));
  }

  @Test
  void concurrentCreationAndEditsHaveExactlyOneWinner() throws Exception {
    race(JSON);
    assertEquals(1, service.get(product).revision());
    race(request(1, Map.of("capitalizationEligible", true)));
    assertEquals(2, service.get(product).revision());
    assertEquals(2, service.history(product, 0, 50).size());
  }

  void race(String json) throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      java.util.concurrent.Callable<Integer> writer =
          () -> {
            tenant("one");
            try {
              start.await();
              service.save(product, json);
              return 200;
            } catch (InvestmentProductService.Conflict e) {
              return 409;
            } finally {
              org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil.reset();
            }
          };
      var first = pool.submit(writer);
      var second = pool.submit(writer);
      start.countDown();
      assertEquals(Set.of(200, 409), Set.of(first.get(), second.get()));
    }
  }

  @Test
  void readReportsNativeDriftAndMissingAccountingWithoutClaimingReadiness() {
    service.save(product, JSON);
    jdbc.update(
        "DELETE FROM acc_product_mapping WHERE product_id=? AND financial_account_type=3", product);
    var result = service.get(product);
    assertFalse(result.validation().accountingMappingValidated());
    assertTrue(result.validation().nativeConfigurationChanged());
    assertTrue(result.validation().blockers().contains("NATIVE_GL_MAPPING_MISSING_OR_DUPLICATE:3"));
    jdbc.update(
        "UPDATE m_deposit_product_term_and_preclosure SET min_deposit_term=60 WHERE savings_product_id=?",
        product);
    assertFalse(service.get(product).validation().policyValidated());
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.save(product, request(1, Map.of())));
    assertEquals(1, service.get(product).revision());
  }

  @Test
  void missingMappingsCanBeSavedOnlyAsBlockedDraftAndPermissionsAreEnforced() {
    jdbc.update("DELETE FROM acc_product_mapping WHERE product_id=?", product);
    var saved = service.save(product, JSON);
    assertEquals(InvestmentProductData.Status.DRAFT, saved.status());
    assertFalse(saved.validation().accountingMappingValidated());
    assertFalse(saved.capabilities().activationSupported());
    doThrow(new AccessDeniedException("Forbidden"))
        .when(actor)
        .validateHasPermissionTo(InvestmentProductService.MANAGE);
    assertThrows(AccessDeniedException.class, () -> service.save(product, request(1, Map.of())));
    assertEquals(1, service.history(product, 0, 50).size());
  }

  String coreSnapshot() {
    var builder = new StringBuilder();
    for (String table :
        List.of(
            "m_savings_product",
            "m_deposit_product_term_and_preclosure",
            "acc_product_mapping",
            "acc_gl_account"))
      builder.append(
          jdbc.queryForList("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY id"));
    return builder.toString();
  }
}
