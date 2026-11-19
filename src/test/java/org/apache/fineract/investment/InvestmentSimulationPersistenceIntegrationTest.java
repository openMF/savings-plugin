/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentSimulationData.*;
import static org.apache.fineract.investment.InvestmentSimulationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;

class InvestmentSimulationPersistenceIntegrationTest extends InvestmentSimulationDatabaseSupport {
  @Test
  void persistedEvidenceSurvivesPricingAndPolicyChangesAndRemainsFinanciallyReadOnly() {
    publish("7.123456");
    var before = financialSnapshot();
    var configBefore = jdbc.queryForList("SELECT * FROM m_savings_product ORDER BY id");
    var result = simulations.create(request(product, "persistent"));
    assertEquals(Status.COMPLETE_GROSS_PROJECTION, result.status());
    String original = InvestmentProductJson.JSON.toJson(result);
    assertEquals(
        original, InvestmentProductJson.JSON.toJson(simulations.get(result.simulationId())));
    rates.save(product, InvestmentRateFixtures.request(1, "2026-11-18", "20"));
    approve(2);
    service.save(
        product, InvestmentProductFixtures.request(1, Map.of("minimumInvestmentAmount", 200)));
    assertEquals(
        original, InvestmentProductJson.JSON.toJson(simulations.get(result.simulationId())));
    assertEquals(
        original,
        InvestmentProductJson.JSON.toJson(simulations.create(request(product, "persistent"))));
    assertEquals(1, result.cycles().getFirst().rateSelection().version());
    assertEquals(before, financialSnapshot());
    assertEquals(configBefore, jdbc.queryForList("SELECT * FROM m_savings_product ORDER BY id"));
    assertEquals(1, simulations.history(product, 0, 100).size());
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE m_investment_simulation SET status='CALCULATION_BLOCKED' WHERE id=?",
                result.simulationId()));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM m_investment_simulation WHERE id=?", result.simulationId()));
  }

  @Test
  void validationDoesNotWriteAuditAndMissingPricingNeverProducesZeroYield() {
    var draft = rates.save(product, InvestmentRateFixtures.request(0, null, "9"));
    long count = jdbc.queryForObject("SELECT count(*) FROM m_investment_simulation", Long.class);
    var missing = simulations.validate(request(product, null));
    assertNull(missing.simulationId());
    assertNull(missing.grossInterest());
    assertEquals(Status.NO_APPLICABLE_RATE, missing.status());
    assertNull(missing.cycles().getFirst().rateSelection());
    assertEquals(
        count, jdbc.queryForObject("SELECT count(*) FROM m_investment_simulation", Long.class));
    approve(draft.revision());
    assertEquals(
        Status.COMPLETE_GROSS_PROJECTION, simulations.validate(request(product, null)).status());
  }

  @Test
  void nativeDailyCompoundingAndMissingConventionsAreBlockedWithPricingEvidence() {
    publish("8");
    jdbc.update(
        "UPDATE m_savings_product SET interest_compounding_period_enum=1 WHERE id=?", product);
    var daily = simulations.create(request(product, "daily"));
    assertEquals(Status.CALCULATION_BLOCKED, daily.status());
    assertNull(daily.grossInterest());
    assertTrue(daily.blockers().contains("NATIVE_DAILY_COMPOUNDING_USES_FLOATING_POINT"));
    assertEquals(
        new BigDecimal("8"), daily.cycles().getFirst().rateSelection().annualRatePercent());
    jdbc.update(
        "UPDATE m_savings_product SET interest_compounding_period_enum=7,interest_calculation_days_in_year_type_enum=null WHERE id=?",
        product);
    var missing = simulations.create(request(product, "basis"));
    assertNull(missing.projectedMaturityValue());
    assertTrue(missing.blockers().contains("NATIVE_DAY_COUNT_MISSING_OR_UNSUPPORTED"));
    MoneyHelper.clearCacheForTenant("one");
    assertTrue(
        simulations
            .validate(request(product, null))
            .blockers()
            .contains("NATIVE_TENANT_ROUNDING_UNINITIALIZED"));
  }

  @Test
  void requestsAreScopedByTenantActorAndCanonicalInput() {
    publish("8");
    var result = simulations.create(request(product, "scoped"));
    var same = simulations.create(request(product, "scoped").replace("10000.00", "10000"));
    assertEquals(result.simulationId(), same.simulationId());
    assertThrows(
        InvestmentSimulationService.Conflict.class,
        () -> simulations.create(edit(product, "scoped", r -> r.addProperty("termDays", 91))));
    rateActor.set(2L);
    assertThrows(
        InvestmentSimulationService.NotFound.class, () -> simulations.get(result.simulationId()));
    assertTrue(simulations.history(product, 0, 100).isEmpty());
    var another = simulations.create(request(product, "scoped"));
    assertNotEquals(result.simulationId(), another.simulationId());
    rateTenant("two", "UTC");
    rateActor.set(1L);
    assertThrows(
        InvestmentSimulationService.NotFound.class, () -> simulations.get(result.simulationId()));
    assertTrue(simulations.history(product, 0, 100).isEmpty());
    assertEquals(
        Status.NO_APPLICABLE_RATE, simulations.create(request(product, "scoped")).status());
  }

  @Test
  void concurrentIdenticalSubmissionsReturnOneImmutableRecord() throws Exception {
    publish("8");
    var ready = new java.util.concurrent.CyclicBarrier(2);
    Callable<String> create =
        () -> {
          rateTenant("one", "UTC");
          rateActor.set(1L);
          try {
            ready.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return simulations.create(request(product, "concurrent")).simulationId();
          } finally {
            org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil.reset();
          }
        };
    try (var pool = Executors.newFixedThreadPool(2)) {
      var results = pool.invokeAll(List.of(create, create));
      assertEquals(results.getFirst().get(), results.getLast().get());
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_investment_simulation WHERE product_id=? AND request_key='concurrent'",
            Integer.class,
            product));
  }

  @Test
  void concurrentProjectionsReleaseSnapshotConnectionsBeforeAuditInABoundedPool() throws Exception {
    publish("8");
    var bothReading = new java.util.concurrent.CyclicBarrier(2);
    try (var pool = new com.zaxxer.hikari.HikariDataSource()) {
      pool.setDataSource(sources.get("one"));
      pool.setMaximumPoolSize(2);
      pool.setMinimumIdle(0);
      pool.setConnectionTimeout(2000);
      var pooledJdbc = new org.springframework.jdbc.core.JdbcTemplate(pool);
      var access =
          new InvestmentSimulationAccess(pooledJdbc, new InvestmentProductAccess(pooledJdbc)) {
            @Override
            InvestmentProductData.NativeProduct product(long id) {
              try {
                bothReading.await(10, java.util.concurrent.TimeUnit.SECONDS);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
              return super.product(id);
            }
          };
      var pricing =
          new InvestmentRateService(
              security,
              pooledJdbc,
              new InvestmentProductAccess(pooledJdbc),
              new InvestmentProductValidation(),
              new InvestmentRateValidation(),
              new InvestmentRateTime(clock));
      var audit = transactional(new InvestmentSimulationAuditService(pooledJdbc, security), pool);
      var service =
          transactional(
              new InvestmentSimulationService(
                  security,
                  access,
                  pricing,
                  new InvestmentRateTime(clock),
                  new InvestmentSimulationValidation(),
                  new InvestmentSimulationCalculator(),
                  audit,
                  new org.springframework.jdbc.datasource.DataSourceTransactionManager(pool)),
              pool);
      Callable<String> create =
          () -> {
            rateTenant("one", "UTC");
            rateActor.set(1L);
            try {
              return service.create(request(product, "bounded-pool")).simulationId();
            } finally {
              org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil.reset();
            }
          };
      try (var executor = Executors.newFixedThreadPool(2)) {
        var futures = executor.invokeAll(List.of(create, create));
        assertEquals(futures.getFirst().get(), futures.getLast().get());
      }
      assertEquals(
          1,
          pooledJdbc.queryForObject(
              "SELECT count(*) FROM m_investment_simulation WHERE product_id=? AND request_key='bounded-pool'",
              Integer.class,
              product));
    }
  }

  @Test
  void evidenceWriteFailureCannotReturnCompletedSimulationOrTouchFinancialRecords() {
    publish("8");
    var before = financialSnapshot();
    jdbc.execute(
        "CREATE FUNCTION reject_simulation_insert() RETURNS trigger AS $$ BEGIN IF NEW.request_key='fail-insert' THEN RAISE EXCEPTION 'synthetic audit failure'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql");
    jdbc.execute(
        "CREATE TRIGGER reject_simulation_insert BEFORE INSERT ON m_investment_simulation FOR EACH ROW EXECUTE FUNCTION reject_simulation_insert()");
    try {
      assertThrows(
          DataAccessException.class, () -> simulations.create(request(product, "fail-insert")));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM m_investment_simulation WHERE product_id=?",
              Integer.class,
              product));
      assertEquals(before, financialSnapshot());
    } finally {
      jdbc.execute("DROP TRIGGER reject_simulation_insert ON m_investment_simulation");
      jdbc.execute("DROP FUNCTION reject_simulation_insert()");
    }
  }

  @Test
  void futurePublishedRateChangesPerRenewalAndCapitalizationIsNeverPaidTwice() {
    renewalPolicy(true);
    publish("8");
    rates.save(product, InvestmentRateFixtures.request(1, "2027-02-15", "9"));
    approve(2);
    rates.save(product, InvestmentRateFixtures.request(2, "2027-05-16", "11"));
    approve(3);
    var before = financialSnapshot();
    var result = simulations.create(renewals(3, true));
    assertEquals(Status.COMPLETE_GROSS_PROJECTION, result.status());
    assertEquals(
        List.of(
            new BigDecimal("8"), new BigDecimal("9"), new BigDecimal("11"), new BigDecimal("11")),
        result.cycles().stream().map(c -> c.rateSelection().annualRatePercent()).toList());
    assertEquals(
        List.of("2027-02-15", "2027-05-16", "2027-08-14", "2027-11-12"),
        result.cycles().stream().map(Cycle::maturityDate).toList());
    for (int i = 0; i < 3; i++) {
      var cycle = result.cycles().get(i);
      assertEquals(0, cycle.interestPayoutTotal().signum());
      assertEquals(cycle.grossInterest(), cycle.capitalizedOnRenewal());
      assertEquals(
          cycle.openingPrincipal().add(cycle.grossInterest()),
          result.cycles().get(i + 1).openingPrincipal());
    }
    assertEquals(0, result.cycles().getLast().capitalizedOnRenewal().signum());
    assertEquals(
        result.cycles().getLast().grossInterest(), result.cycles().getLast().interestPayoutTotal());
    assertEquals(before, financialSnapshot());
  }

  @Test
  void futurePricingIsNotInferredFromCurrentRateAndUnpublishedSchedulesAreIgnored() {
    renewalPolicy(false);
    publish("8");
    rates.save(product, InvestmentRateFixtures.request(1, "2027-02-15", "9"));
    var partial = simulations.create(renewals(1, false));
    assertEquals(Status.PARTIAL_PROJECTION, partial.status());
    assertNull(partial.grossInterest());
    assertNull(partial.projectedMaturityValue());
    assertEquals(Status.COMPLETE_GROSS_PROJECTION, partial.cycles().getFirst().status());
    assertTrue(partial.blockers().contains("FUTURE_RENEWAL_RATE_NOT_EXPLICITLY_PUBLISHED"));
    approve(2);
    var complete = simulations.create(renewals(1, false));
    assertEquals(Status.COMPLETE_GROSS_PROJECTION, complete.status());
    assertEquals(
        complete.cycles().getFirst().openingPrincipal(),
        complete.cycles().getLast().openingPrincipal());
  }

  @Test
  void scheduledRateAndHistoricalSelectionRespectExactEffectiveInstant() {
    rates.save(product, InvestmentRateFixtures.request(0, "2026-11-18", "11"));
    approve(1);
    assertEquals(Status.NO_APPLICABLE_RATE, simulations.validate(request(product, null)).status());
    var future =
        simulations.validate(edit(product, null, r -> r.addProperty("startDate", "2026-11-18")));
    assertEquals(
        new BigDecimal("11"), future.cycles().getFirst().rateSelection().annualRatePercent());
    clock.at("2026-11-18T00:00:00Z");
    assertEquals(
        Status.COMPLETE_GROSS_PROJECTION,
        simulations
            .validate(edit(product, null, r -> r.addProperty("startDate", "2026-11-18")))
            .status());
    assertEquals(
        Status.NO_APPLICABLE_RATE,
        simulations
            .validate(edit(product, null, r -> r.addProperty("startDate", "2026-11-16")))
            .status());
  }

  @Test
  void cedePayoutAndFundingVerificationUnavailabilityDoNotRequireBalances() {
    jdbc.update(
        "UPDATE m_savings_product SET interest_compounding_period_enum=4,interest_posting_period_enum=4 WHERE id=?",
        product);
    service.save(
        product,
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
                30,
                "interestPayoutMode",
                "MONTHLY")));
    var root =
        com.google.gson.JsonParser.parseString(InvestmentRateFixtures.request(0, null, "10"))
            .getAsJsonObject();
    root.getAsJsonObject("matrix")
        .add(
            "termBands",
            InvestmentProductJson.JSON.toJsonTree(
                List.of(
                    new InvestmentRateData.TermBand(60, 90),
                    new InvestmentRateData.TermBand(120, 180))));
    rates.save(product, root.toString());
    approve(1);
    var before = financialSnapshot();
    var result =
        simulations.create(
            edit(
                product,
                null,
                r -> {
                  r.addProperty("interestPayoutMode", "MONTHLY");
                  r.add(
                      "fundingContext",
                      com.google.gson.JsonParser.parseString(
                          "{\"mode\":\"VERIFY_AVAILABLE_FUNDS\"}"));
                }));
    assertEquals(Status.COMPLETE_GROSS_PROJECTION, result.status());
    assertEquals(InvestmentProductData.InvestmentType.CEDE, result.investmentType());
    assertFalse(result.fundingAssessment().balanceVerified());
    assertEquals("VERIFICATION_UNAVAILABLE_QUOTE_PRESERVED", result.fundingAssessment().status());
    assertTrue(result.cycles().getFirst().paymentSchedule().size() > 1);
    assertEquals(before, financialSnapshot());
  }

  @Test
  void stalePolicyIsBlockedAndPaginationAndProductTypesRemainValidated() {
    publish("8");
    service.save(
        product, InvestmentProductFixtures.request(1, Map.of("minimumInvestmentAmount", 200)));
    var stale = simulations.validate(request(product, null));
    assertTrue(
        stale.blockers().contains("PUBLISHED_RATE_POLICY_REVISION_DIFFERS_FROM_CURRENT_POLICY"));
    assertNull(stale.grossInterest());
    assertThrows(PlatformApiDataValidationException.class, () -> simulations.history(null, 0, 101));
    assertThrows(PlatformApiDataValidationException.class, () -> simulations.history(null, -1, 10));
    assertThrows(
        InvestmentProductService.NotFound.class, () -> simulations.create(request(ordinary, null)));
    doThrow(new AccessDeniedException("denied"))
        .when(actor)
        .validateHasPermissionTo(InvestmentSimulationService.AUDIT);
    assertThrows(AccessDeniedException.class, () -> simulations.history(product, 0, 10));
  }

  @Test
  void matrixBoundariesAndNativeCurrencyPrecisionArePreservedByOrchestration() {
    rates.save(product, InvestmentRateFixtures.BASE);
    approve(1);
    for (var example :
        List.of(
            new String[] {"100", "1", "7.123456"},
            new String[] {"999.99", "30", "7.123456"},
            new String[] {"1000", "30", "9"},
            new String[] {"1000", "31", "10"},
            new String[] {"100000", "1825", "10"})) {
      var result =
          simulations.validate(
              edit(
                  product,
                  null,
                  r -> {
                    r.addProperty("investmentAmount", new BigDecimal(example[0]));
                    r.addProperty("termDays", Integer.parseInt(example[1]));
                  }));
      assertEquals(Status.COMPLETE_GROSS_PROJECTION, result.status());
      assertEquals(
          new BigDecimal(example[2]),
          result.cycles().getFirst().rateSelection().annualRatePercent());
    }
  }

  @Test
  void corruptedEvidenceFailsIntegrityCheckRatherThanBeingRecalculated() {
    publish("8");
    var created = simulations.create(request(product, null));
    String snapshot =
        jdbc.queryForObject(
            "SELECT result_snapshot_json FROM m_investment_simulation WHERE id=?",
            String.class,
            created.simulationId());
    jdbc.execute("ALTER TABLE m_investment_simulation DISABLE TRIGGER trg_isim_immutable");
    try {
      jdbc.update(
          "UPDATE m_investment_simulation SET result_snapshot_json='{}' WHERE id=?",
          created.simulationId());
      assertThrows(IllegalStateException.class, () -> simulations.get(created.simulationId()));
    } finally {
      jdbc.update(
          "UPDATE m_investment_simulation SET result_snapshot_json=? WHERE id=?",
          snapshot,
          created.simulationId());
      jdbc.execute("ALTER TABLE m_investment_simulation ENABLE TRIGGER trg_isim_immutable");
    }
    assertEquals(created.simulationId(), simulations.get(created.simulationId()).simulationId());
  }

  @Test
  void migrationRollsBackPermissionsGrantsTriggersAndStorageAndCanBeReapplied() throws Exception {
    var source = sources.get("two");
    var direct = new org.springframework.jdbc.core.JdbcTemplate(source);
    direct.update(
        "INSERT INTO m_role_permission SELECT 1,id FROM m_permission WHERE code='CREATE_INVESTMENT_SIMULATION'");
    try (var connection = source.getConnection()) {
      var db =
          liquibase.database.DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(
                  new liquibase.database.jvm.JdbcConnection(connection));
      try (var migration =
          new liquibase.Liquibase(
              MIGRATION, new liquibase.resource.ClassLoaderResourceAccessor(), db)) {
        try {
          migration.rollback(2, new liquibase.Contexts(), new liquibase.LabelExpression());
          assertNull(
              direct.queryForObject(
                  "SELECT to_regclass('m_investment_simulation')::text", String.class));
          assertEquals(
              0,
              direct.queryForObject(
                  "SELECT count(*) FROM m_permission WHERE code LIKE '%INVESTMENT_SIMULATION%'",
                  Integer.class));
          assertEquals(
              0, direct.queryForObject("SELECT count(*) FROM m_role_permission", Integer.class));
        } finally {
          migration.update(new liquibase.Contexts(), new liquibase.LabelExpression());
        }
      }
    }
    assertEquals(
        3,
        direct.queryForObject(
            "SELECT count(*) FROM m_permission WHERE code LIKE '%INVESTMENT_SIMULATION%'",
            Integer.class));
  }
}
