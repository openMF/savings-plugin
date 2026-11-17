/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentRateData.*;
import static org.apache.fineract.investment.InvestmentRateFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;

class InvestmentRatePersistenceIntegrationTest extends InvestmentRateDatabaseSupport {
  @Test
  void immediateApprovalAndHistoricalSnapshotsProtectDraftsAndNativeProducts() {
    var nativeBefore = new InvestmentProductAccess(jdbc).product(product, false);
    rates.save(product, BASE);
    assertEquals(
        SelectionStatus.NO_APPLICABLE_RATE,
        rates.selection(product, "1000", 90, null, null).status());
    var first = approve(1);
    assertEquals("2026-11-17T12:00:00Z", first.effectiveInstant());
    assertEquals(2L, first.checkerId());
    assertEquals(1L, first.makerId());
    assertEquals(
        new BigDecimal("10"),
        rates.selection(product, "1000.00", 90, null, null).annualRatePercent());
    rates.save(product, request(1, null, "11.123456"));
    assertEquals(
        new BigDecimal("10"), rates.selection(product, "1000", 90, null, null).annualRatePercent());
    clock.at("2026-11-17T12:00:01Z");
    approve(2);
    assertEquals(
        new BigDecimal("10"),
        rates
            .selection(product, "1000", 90, "2026-11-17T12:00:00.999999Z", null)
            .annualRatePercent());
    assertEquals(
        new BigDecimal("11.123456"),
        rates.selection(product, "1000", 90, null, null).annualRatePercent());
    assertEquals(first.configuration(), rates.version(product, 1).configuration());
    assertEquals(Status.SUPERSEDED, rates.version(product, 1).status());
    assertEquals(4, rates.history(product, 0, 50).size());
    var change = rates.history(product, 0, 50).getFirst().cellChanges().getLast();
    assertEquals(new BigDecimal("10"), change.previousRate());
    assertEquals(new BigDecimal("11.123456"), change.newRate());
    assertEquals(nativeBefore, new InvestmentProductAccess(jdbc).product(product, false));
    assertFalse(first.capabilities().financialExecutionSupported());
  }

  @Test
  void schedulesSurviveReloadAndResolveBeforeAtAndAfterMidnightInTenantZone() {
    rateTenant("one", "Asia/Kolkata");
    rates.save(product, request(0, null, "8"));
    approve(1);
    rates.save(product, request(1, "2026-11-18", "9"));
    var scheduled = approve(2);
    assertEquals("2026-11-17T18:30:00Z", scheduled.effectiveInstant());
    assertEquals(Status.APPROVED_SCHEDULED, scheduled.status());
    assertEquals(1, rates.versions(product, true, 0, 50).size());
    rates = reload();
    for (String at :
        new String[] {"2026-11-17T18:29:59Z", "2026-11-17T18:30:00Z", "2026-11-17T18:30:01Z"}) {
      var selection = rates.selection(product, "5000", 90, at, null);
      assertEquals(new BigDecimal(at.contains("18:29") ? "8" : "9"), selection.annualRatePercent());
    }
    rates.save(product, request(2, "2026-11-19", "10"));
    approve(3);
    assertEquals(
        new BigDecimal("10"),
        rates.selection(product, "5000", 90, "2026-11-18T18:30:00Z", null).annualRatePercent());
    assertEquals(2, rates.versions(product, true, 0, 50).size());
  }

  @Test
  void nativePolicyTermsCurrencyAndProductTenantIsolationAreEnforced() {
    assertThrows(InvestmentProductService.NotFound.class, () -> rates.save(ordinary, BASE));
    assertThrows(InvestmentProductService.NotFound.class, () -> rates.save(recurring, BASE));
    assertThrows(InvestmentProductService.NotFound.class, () -> rates.template(9999999));
    rates.save(product, BASE);
    approve(1);
    assertEquals(
        SelectionStatus.NO_APPLICABLE_RATE,
        rates.selection(other, "1000", 90, null, null).status());
    rateTenant("two", "UTC");
    assertEquals(
        SelectionStatus.NO_APPLICABLE_RATE,
        rates.selection(product, "1000", 90, null, null).status());
    assertTrue(rates.history(product, 0, 50).isEmpty());
    rateTenant("one", "UTC");
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> rates.selection(product, "1000.001", 90, null, null));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> rates.selection(product, "-1", 90, null, null));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> rates.selection(product, "1000", 1826, null, null));
    service.save(
        other,
        InvestmentProductFixtures.request(
            1,
            Map.of(
                "investmentType",
                "CEDE",
                "minimumTermDays",
                60,
                "maximumTermDays",
                180,
                "termMultipleDays",
                30)));
    assertThrows(PlatformApiDataValidationException.class, () -> rates.save(other, BASE));
    String cede =
        edit(
            root -> {
              var terms = root.getAsJsonObject("matrix").getAsJsonArray("termBands");
              terms.get(0).getAsJsonObject().addProperty("minimumDays", 60);
              terms.get(0).getAsJsonObject().addProperty("maximumDays", 90);
              terms.get(1).getAsJsonObject().addProperty("minimumDays", 120);
              terms.get(1).getAsJsonObject().addProperty("maximumDays", 180);
            });
    rates.save(other, cede);
    rateActor.set(2L);
    rates.publish(other, "{\"expectedRevision\":1}");
    rateActor.set(1L);
    assertEquals(
        new BigDecimal("9"), rates.selection(other, "1000", 60, null, null).annualRatePercent());
    assertEquals(
        new BigDecimal("10"), rates.selection(product, "1000", 60, null, null).annualRatePercent());
  }

  @Test
  void exactAmountBoundariesNeverFallback() {
    rates.save(product, BASE);
    approve(1);
    for (var e :
        Map.of("100", "7.123456", "999.99", "7.123456", "1000.00", "9", "100000", "9").entrySet())
      assertEquals(
          new BigDecimal(e.getValue()),
          rates.selection(product, e.getKey(), 1, null, null).annualRatePercent());
    assertEquals(
        new BigDecimal("10"),
        rates.selection(product, "1000", 1825, null, null).annualRatePercent());
    assertEquals(
        SelectionStatus.NO_APPLICABLE_RATE,
        rates.selection(product, "100000.01", 90, null, null).status());
    assertEquals(
        SelectionStatus.NO_APPLICABLE_RATE,
        rates.selection(product, "99.99", 90, null, null).status());
  }

  @Test
  void historicalPrecisionAndPolicyAreTakenFromApprovedSnapshots() {
    rates.save(product, BASE);
    approve(1);
    jdbc.update("UPDATE m_savings_product SET currency_digits=0 WHERE id=?", product);
    assertEquals(
        new BigDecimal("7.123456"),
        rates.selection(product, "999.99", 1, "2026-11-17T12:00:00Z", null).annualRatePercent());
    // PR 01 changes after publication do not rewrite the pricing eligibility snapshot either.
    jdbc.update("UPDATE m_savings_product SET currency_digits=2 WHERE id=?", product);
    service.save(
        product,
        InvestmentProductFixtures.request(
            1, Map.of("minimumTermDays", 30, "maximumTermDays", 1800)));
    assertEquals(
        new BigDecimal("7.123456"),
        rates.selection(product, "999.99", 1, "2026-11-17T12:00:00Z", null).annualRatePercent());
  }

  @Test
  void staleDuplicateAndConflictingPublicationAreAtomic() {
    rates.save(product, BASE);
    assertThrows(InvestmentRateService.Conflict.class, () -> rates.save(product, BASE));
    assertThrows(
        AccessDeniedException.class, () -> rates.publish(product, "{\"expectedRevision\":1}"));
    assertThrows(InvestmentRateService.Conflict.class, () -> approve(2));
    approve(1);
    assertThrows(InvestmentRateService.Conflict.class, () -> approve(1));
    rates.save(product, request(1, "2026-11-19", "9"));
    approve(2);
    rates.save(product, request(2, "2026-11-19", "10"));
    assertThrows(InvestmentRateService.Conflict.class, () -> approve(3));
    rates.save(product, request(3, "2026-11-18", "10"));
    assertThrows(InvestmentRateService.Conflict.class, () -> approve(4));
    assertEquals(2, rates.versions(product, false, 0, 50).size());
    assertEquals(6, rates.history(product, 0, 50).size());
  }

  @Test
  void everyContributorIsExcludedFromReviewAndNativeOrPolicyDriftBlocksApproval() {
    rates.save(product, BASE);
    rateActor.set(2L);
    rates.save(product, request(1, null, "9"));
    rateActor.set(1L);
    assertThrows(AccessDeniedException.class, () -> approve(2));
    assertThrows(
        AccessDeniedException.class, () -> rates.publish(product, "{\"expectedRevision\":2}"));
    // A new product with one maker: PR 01 policy drift requires another rate draft and review.
    rates.save(other, BASE);
    service.save(
        other, InvestmentProductFixtures.request(1, Map.of("capitalizationEligible", true)));
    rateActor.set(2L);
    assertThrows(
        InvestmentRateService.Conflict.class,
        () -> rates.publish(other, "{\"expectedRevision\":1}"));
    rateActor.set(1L);
    rates.save(other, request(1, null, "9"));
    jdbc.update("UPDATE m_savings_product SET currency_digits=3 WHERE id=?", other);
    rateActor.set(2L);
    assertThrows(
        InvestmentRateService.Conflict.class,
        () -> rates.publish(other, "{\"expectedRevision\":2}"));
  }

  @Test
  void failedAuditRollsBackDraftAndPublicationAndDatabaseEnforcesImmutabilityAndKeys() {
    rates.save(product, BASE);
    jdbc.execute(
        "ALTER TABLE m_investment_rate_audit ADD CONSTRAINT test_reject_publish_"
            + product
            + " CHECK (product_id<>"
            + product
            + " OR action<>'PUBLISH')");
    assertThrows(DataAccessException.class, () -> approve(1));
    assertTrue(rates.versions(product, false, 0, 50).isEmpty());
    assertEquals(1, rates.history(product, 0, 50).size());
    jdbc.execute(
        "ALTER TABLE m_investment_rate_audit DROP CONSTRAINT test_reject_publish_" + product);
    approve(1);
    for (String table : new String[] {"revision", "publication", "audit"}) {
      assertThrows(
          DataAccessException.class,
          () ->
              jdbc.update(
                  "DELETE FROM m_investment_rate_" + table + " WHERE product_id=?", product));
      assertThrows(
          DataAccessException.class,
          () ->
              jdbc.update(
                  "UPDATE m_investment_rate_"
                      + table
                      + " SET product_id=product_id WHERE product_id=?",
                  product));
    }
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO m_investment_rate_publication SELECT * FROM m_investment_rate_publication WHERE product_id=?",
                product));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO m_investment_rate_publication VALUES (?,99,'APPROVED',1,'2026-11-18','UTC',2,'2026-11-17T12:00:00Z',null)",
                other));
    jdbc.execute(
        "ALTER TABLE m_investment_rate_audit ADD CONSTRAINT test_reject_draft_"
            + product
            + " CHECK (product_id<>"
            + product
            + " OR action<>'UPDATE_DRAFT')");
    assertThrows(DataAccessException.class, () -> rates.save(product, request(1, null, "9")));
    assertEquals(1, rates.draft(product).revision());
    jdbc.execute(
        "ALTER TABLE m_investment_rate_audit DROP CONSTRAINT test_reject_draft_" + product);
  }

  @Test
  void publicationBlockersPreserveCampaignDraftsAndRejectBackdatesAndClockMismatch() {
    var campaign =
        edit(
            root ->
                root.add(
                    "campaignMetadata",
                    com.google.gson.JsonParser.parseString(
                        "{\"campaignId\":\"synthetic\",\"name\":\"Draft campaign\",\"type\":\"FINAL_RENEWAL_PERIOD\",\"startDate\":\"2026-11-18\",\"endDate\":null}")));
    assertFalse(rates.validate(product, campaign).publishable());
    rates.save(product, campaign);
    assertThrows(InvestmentRateService.PublicationBlocked.class, () -> approve(1));
    assertNotNull(rates.draft(product).configuration().campaignMetadata());
    rates.save(product, request(1, "2026-11-16", "9"));
    assertThrows(InvestmentRateService.PublicationBlocked.class, () -> approve(2));
    rates.save(product, request(2, null, "9"));
    clock.at("2026-11-18T12:00:00Z");
    assertThrows(InvestmentRateService.PublicationBlocked.class, () -> approve(3));
    assertTrue(rates.versions(product, false, 0, 50).isEmpty());
  }

  @Test
  void boundedPaginationAndMaximumMatrixPersistWithoutRounding() {
    var request =
        new WriteRequest(0L, largest(), new EffectiveValidity(ValidityMode.IMMEDIATE, null), null);
    rates.save(product, InvestmentProductJson.JSON.toJson(request));
    approve(1);
    assertEquals(2500, rates.version(product, 1).configuration().matrix().cells().size());
    assertEquals(
        new BigDecimal("8.123456"),
        rates.selection(product, "100000.00", 1825, null, null).annualRatePercent());
    assertThrows(
        PlatformApiDataValidationException.class, () -> rates.versions(product, false, 0, 201));
    assertThrows(PlatformApiDataValidationException.class, () -> rates.history(product, -1, 50));
    assertTrue(rates.history(product, 200, 50).isEmpty());
  }

  @Test
  void concurrentDraftsAndConcurrentCheckersCannotDoubleWriteOrPublish() throws Exception {
    assertEquals(1, race(() -> rates.save(product, BASE), 1L));
    assertEquals(1, race(() -> rates.publish(product, "{\"expectedRevision\":1}"), 2L));
    assertEquals(1, rates.versions(product, false, 0, 50).size());
    assertEquals(2, rates.history(product, 0, 50).size());
    rates.save(product, request(1, "2026-11-18", "9"));
    assertEquals(1, race(() -> rates.publish(product, "{\"expectedRevision\":2}"), 2L));
    assertEquals(2, rates.versions(product, false, 0, 50).size());
    assertEquals(4, rates.history(product, 0, 50).size());
  }

  int race(Callable<?> action, long user) throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> task =
          () -> {
            rateTenant("one", "UTC");
            rateActor.set(user);
            try {
              barrier.await();
              action.call();
              return true;
            } catch (InvestmentRateService.Conflict
                | org.springframework.dao.ConcurrencyFailureException
                | org.springframework.dao.DuplicateKeyException e) {
              return false;
            } finally {
              rateActor.remove();
              ThreadLocalContextUtil.reset();
            }
          };
      var a = executor.submit(task);
      var b = executor.submit(task);
      return (a.get() ? 1 : 0) + (b.get() ? 1 : 0);
    }
  }
}
