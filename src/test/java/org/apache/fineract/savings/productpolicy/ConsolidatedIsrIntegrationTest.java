/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ConsolidatedIsrData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Real PostgreSQL migration, source reads, snapshots, rollback, two tenants and concurrent writes.
 */
class ConsolidatedIsrIntegrationTest {
  static final LocalDate DAY = ProductPolicyWorkflowIntegrationTest.DAY;
  static final Period PERIOD = new Period(DAY, DAY);
  MainAccountIntegrationTest base;
  JdbcTemplate jdbc;
  ConsolidatedIsrService service;
  ConsolidatedIsrRuleResolver rules;
  ConsolidatedIsrAccess access;

  @BeforeAll
  static void database() throws Exception {
    MainAccountIntegrationTest.database();
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      j.execute("ALTER TABLE m_client ADD COLUMN legal_form_enum integer DEFAULT 1");
      j.execute("CREATE TABLE m_tax_group(id bigint PRIMARY KEY)");
      j.update("INSERT INTO m_tax_group VALUES(1)");
      for (String column : List.of("sub_status_enum", "currency_digits"))
        j.execute("ALTER TABLE m_savings_account ADD COLUMN " + column + " integer");
      for (String column : List.of("activatedon_date", "closedon_date"))
        j.execute("ALTER TABLE m_savings_account ADD COLUMN " + column + " date");
      j.execute("ALTER TABLE m_savings_account ADD COLUMN currency_code varchar(3)");
      j.execute("ALTER TABLE m_savings_account ADD COLUMN withhold_tax boolean");
      j.execute("ALTER TABLE m_savings_account ADD COLUMN tax_group_id bigint");
      j.execute("ALTER TABLE m_savings_account ADD COLUMN account_balance_derived numeric(19,6)");
      j.execute(
          "CREATE TABLE m_savings_pocket_relationship(savings_account_id bigint PRIMARY KEY,main_savings_account_id bigint,status varchar(10))");
      j.execute(
          "CREATE TABLE m_savings_account_transaction(id bigint PRIMARY KEY,savings_account_id bigint,transaction_type_enum integer,transaction_date date,amount numeric(19,6),is_reversed boolean,is_reversal boolean,original_transaction_id bigint,running_balance_derived numeric(19,6))");
      j.execute(
          "CREATE TABLE m_savings_account_transaction_tax_details(id bigint PRIMARY KEY,savings_transaction_id bigint,tax_component_id bigint,amount numeric(19,6))");
      j.execute("CREATE TABLE acc_gl_journal_entry(id bigint PRIMARY KEY,amount numeric(19,6))");
      for (int i = 0; i < 2; i++) {
        try (var connection = ds.getConnection()) {
          var db =
              DatabaseFactory.getInstance()
                  .findCorrectDatabaseImplementation(new JdbcConnection(connection));
          try (var lb =
              new Liquibase(
                  "db/changelog/tenant/module/savings/parts/090-consolidated-isr.xml",
                  new ClassLoaderResourceAccessor(),
                  db)) {
            lb.validate();
            lb.update(new Contexts(), new LabelExpression());
          }
        }
      }
      assertEquals(
          6,
          j.queryForObject(
              "SELECT COUNT(*) FROM m_permission WHERE code LIKE '%CONSOLIDATED_ISR%'",
              Integer.class));
      assertEquals(
          0,
          j.queryForObject(
              "SELECT COUNT(*) FROM m_permission WHERE code IN ('EXECUTE_CONSOLIDATED_ISR','APPROVE_CONSOLIDATED_ISR_RULE')",
              Integer.class));
    }
  }

  @BeforeEach
  void setup() {
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      j.update("DELETE FROM m_savings_isr_audit");
      j.update("DELETE FROM m_savings_isr_request");
      j.execute("DELETE FROM m_savings_isr_calculation");
      j.execute("DELETE FROM m_savings_isr_rule_version");
      j.update("DELETE FROM m_savings_isr_rule");
      j.update("DELETE FROM m_savings_pocket_relationship");
      j.update("DELETE FROM m_savings_account_transaction_tax_details");
      j.update("DELETE FROM m_savings_account_transaction");
      j.update(
          "UPDATE m_savings_product SET currency_code='MXN',currency_digits=2,withhold_tax=true,tax_group_id=1,accounting_type=2");
      j.update("UPDATE m_client SET office_id=1,legal_form_enum=1");
      j.update("UPDATE m_client SET office_id=2 WHERE id=3");
    }
    base = new MainAccountIntegrationTest();
    base.setup();
    jdbc = base.jdbc;
    jdbc.update(
        "UPDATE m_savings_account SET status_enum=300,sub_status_enum=0,currency_code='MXN',currency_digits=2,withhold_tax=true,tax_group_id=1,account_balance_derived=10,activatedon_date=?,closedon_date=NULL",
        DAY);
    base.approve(1, ProductPolicyValidationTest.JSON.replace("\"SIGHT\"", "\"SAVINGS\""));
    base.bindPolicy(901, 1, "MAIN");
    base.bindPolicy(902, 1, "MAIN");
    access = new ConsolidatedIsrAccess(base.fixture.security, jdbc);
    var audit = new ConsolidatedIsrAuditService(jdbc, access);
    rules = proxy(new ConsolidatedIsrRuleResolver(jdbc, access, audit));
    var eligibility =
        new ConsolidatedIsrEligibilityService(
            jdbc, access, base.fixture.workflow, new ProductPolicyCoreSnapshot(jdbc));
    service =
        proxy(
            new ConsolidatedIsrService(
                jdbc,
                access,
                new ConsolidatedIsrBalanceService(jdbc, access, eligibility),
                rules,
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

  String request(String key, String previous) {
    return RatePropagationJson.JSON.toJson(
        new Save(
            key, PERIOD, previous, previous == null ? null : "Synthetic historical correction"));
  }

  Calculation save() {
    return service.save(1, request(UUID.randomUUID().toString(), null));
  }

  @Test
  void discoversSupportedAccountsButDoesNotInventTaxEligibilityOrProfile() {
    var result = service.preview(1, PERIOD);
    assertEquals(2, result.sources().accounts().size());
    assertTrue(result.sources().accounts().stream().allMatch(Account::structurallySupported));
    assertTrue(result.sources().accounts().stream().noneMatch(Account::eligible));
    assertNull(result.taxableBase());
    assertNull(result.calculatedIsr());
    assertNull(result.profile().taxTreatment());
    assertTrue(result.blockers().containsAll(BLOCKERS));
    assertEquals(
        0,
        result
            .sources()
            .observations()
            .getFirst()
            .observedLedgerBalance()
            .compareTo(new java.math.BigDecimal("20")));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM m_savings_isr_audit", Integer.class));
    assertFalse(
        RatePropagationJson.JSON
            .toJson(service.eligibility(1, PERIOD))
            .contains("observedLedgerBalance"));
  }

  @Test
  void structuralEligibilityReportsStatusClassificationPolicyAndTaxConfiguration() {
    jdbc.update("UPDATE m_savings_account SET status_enum=600,tax_group_id=NULL WHERE id=901");
    jdbc.update("UPDATE m_savings_account SET deposit_type_enum=200,group_id=4 WHERE id=902");
    var rows = service.preview(1, PERIOD).sources().accounts();
    assertTrue(
        rows.getFirst()
            .reasons()
            .containsAll(
                List.of(
                    Reason.ACCOUNT_STATUS_INELIGIBLE,
                    Reason.TAX_GROUP_UNAVAILABLE,
                    Reason.TAX_CONFIGURATION_MISMATCH)));
    assertTrue(
        rows.getLast()
            .reasons()
            .containsAll(
                List.of(
                    Reason.INVESTMENT_TREATMENT_UNAPPROVED,
                    Reason.ACCOUNT_CLASSIFICATION_UNSUPPORTED,
                    Reason.ACCOUNT_OWNERSHIP_UNSUPPORTED)));
    assertTrue(rows.stream().noneMatch(Account::structurallySupported));
  }

  @Test
  void crossCurrencyPopulationIsObservedSeparatelyAndCannotBeConsolidated() {
    jdbc.update("UPDATE m_savings_account SET currency_code='USD' WHERE id=902");
    var result = service.preview(1, PERIOD);
    assertEquals(2, result.sources().observations().size());
    assertTrue(result.blockers().contains(Reason.CURRENCY_AGGREGATION_UNAPPROVED));
    assertNull(result.profile().currency());
    assertNull(result.calculatedIsr());
  }

  @Test
  void immutableHistoryReplaysOriginalEvidenceAfterNativeCorrections() {
    jdbc.update(
        "INSERT INTO m_savings_account_transaction VALUES(1,901,3,?,2,false,false,NULL,12),(2,901,18,?,0.2,false,false,NULL,11.8)",
        DAY,
        DAY);
    jdbc.update("INSERT INTO m_savings_account_transaction_tax_details VALUES(1,2,1,0.2)");
    var first = save();
    String original = RatePropagationJson.JSON.toJson(first);
    assertEquals("UNCHANGED_BLOCKED", service.reconciliation(1, first.calculationId()).status());
    jdbc.update("UPDATE m_savings_account_transaction SET is_reversed=true WHERE id IN(1,2)");
    assertEquals(first, service.get(1, first.calculationId()));
    assertEquals(first, service.save(1, request(first.calculationId(), null)));
    assertEquals(
        "RECONCILIATION_REQUIRED", service.reconciliation(1, first.calculationId()).status());
    String key = UUID.randomUUID().toString();
    var reconciliation = service.recordReconciliation(1, first.calculationId(), key);
    assertEquals(reconciliation, service.recordReconciliation(1, first.calculationId(), key));
    var next = service.save(1, request(UUID.randomUUID().toString(), first.calculationId()));
    assertEquals(first.calculationId(), next.supersedes());
    assertEquals(original, RatePropagationJson.JSON.toJson(service.get(1, first.calculationId())));
    assertThrows(
        ConsolidatedIsrAuditService.Conflict.class,
        () -> service.save(1, request(UUID.randomUUID().toString(), first.calculationId())));
    assertEquals(2, service.history(1, 0, 100).size());
    long cursor = service.history(1, 0, 1).getFirst().sequence();
    assertEquals(1, service.history(1, cursor, 100).size());
    assertEquals(2, ((List<?>) service.audit(1, first.calculationId(), 0, 100)).size());
  }

  @Test
  void differentPayloadConflictAndFailedRequestsRollbackAllPluginEvidence() {
    String key = UUID.randomUUID().toString();
    var first = service.save(1, request(key, null));
    assertThrows(
        ConsolidatedIsrAuditService.Conflict.class,
        () ->
            service.save(
                1,
                RatePropagationJson.JSON.toJson(new Save(key, PERIOD, null, "Different payload"))));
    assertEquals(first, service.save(1, request(key, null)));
    String invalid =
        RatePropagationJson.JSON.toJson(
            new Save(UUID.randomUUID().toString(), new Period(DAY, DAY.plusDays(1)), null, null));
    assertThrows(PlatformApiDataValidationException.class, () -> service.save(1, invalid));
    assertEquals(
        1, jdbc.queryForObject("SELECT COUNT(*) FROM m_savings_isr_request", Integer.class));
  }

  @Test
  void tenantCustomerOfficeAndPermissionsAreCheckedBeforeReturningEvidence() {
    var first = save();
    assertThrows(
        ConsolidatedIsrService.NotFound.class, () -> service.get(2, first.calculationId()));
    assertThrows(ClientNotFoundException.class, () -> service.preview(3, PERIOD));
    ProductPolicyPersistenceIntegrationTest.tenant("two");
    assertThrows(
        ConsolidatedIsrService.NotFound.class, () -> service.get(1, first.calculationId()));
    ProductPolicyPersistenceIntegrationTest.tenant("one");
    jdbc.update("UPDATE m_client SET office_id=2 WHERE id=1");
    assertThrows(ClientNotFoundException.class, () -> service.get(1, first.calculationId()));
    assertThrows(ClientNotFoundException.class, () -> service.preview(1, PERIOD));
    jdbc.update("UPDATE m_client SET office_id=1 WHERE id=1");
    var principal = base.fixture.security.authenticatedUser();
    doThrow(new AccessDeniedException("denied"))
        .when(principal)
        .validateHasPermissionTo(ConsolidatedIsrAccess.PREVIEW);
    assertThrows(AccessDeniedException.class, () -> service.preview(1, PERIOD));
    ThreadLocalContextUtil.clearTenant();
    assertThrows(
        AccessDeniedException.class, () -> access.authorize(ConsolidatedIsrAccess.ELIGIBILITY));
  }

  @Test
  void draftRuleTimelineIsImmutableVersionedIdempotentAndNeverUsableForTax() {
    String id = UUID.randomUUID().toString(), key = UUID.randomUUID().toString();
    var d =
        new RuleDefinition(
            "UNCONFIRMED",
            "UNCONFIRMED",
            "MXN",
            DAY,
            DAY,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    String proposal =
        RatePropagationJson.JSON.toJson(new RuleProposal(key, 0L, d, "Synthetic proposal"));
    var first = rules.propose(id, proposal);
    assertEquals("DRAFT", first.status());
    assertNull(first.approverId());
    assertEquals(first, rules.propose(id, proposal));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            rules.propose(
                id,
                RatePropagationJson.JSON.toJson(
                    new RuleProposal(UUID.randomUUID().toString(), 1L, d, "Overlap"))));
    var later =
        new RuleDefinition(
            "UNCONFIRMED",
            "UNCONFIRMED",
            "MXN",
            DAY.plusDays(1),
            DAY.plusDays(2),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    var second =
        rules.propose(
            id,
            RatePropagationJson.JSON.toJson(
                new RuleProposal(UUID.randomUUID().toString(), 1L, later, "Next period")));
    assertEquals(1L, second.previousVersion());
    assertEquals(2, rules.versions(id, 0, 100).size());
    assertEquals(first, rules.versions(id, 0, 100).getFirst());
    assertNull(save().result().profile().ruleVersion());
    assertFalse(service.template().approvedTaxRules());
    var principal = base.fixture.security.authenticatedUser();
    doThrow(new AccessDeniedException("denied"))
        .when(principal)
        .validateHasPermissionTo(ConsolidatedIsrAccess.RULES);
    assertThrows(
        AccessDeniedException.class, () -> rules.propose(UUID.randomUUID().toString(), proposal));
  }

  @Test
  void concurrentDuplicateAssessmentsAndCorrectionsHaveOneDurableSuccessor() throws Exception {
    String key = UUID.randomUUID().toString();
    race(() -> service.save(1, request(key, null)));
    var first = service.get(1, key);
    assertEquals(
        1, jdbc.queryForObject("SELECT COUNT(*) FROM m_savings_isr_calculation", Integer.class));
    race(() -> service.save(1, request(UUID.randomUUID().toString(), first.calculationId())));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_isr_calculation WHERE supersedes_id=?",
            Integer.class,
            key));
  }

  void race(Callable<?> action) throws Exception {
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> work =
          () -> {
            ProductPolicyPersistenceIntegrationTest.tenant("one");
            ProductPolicyWorkflowIntegrationTest.setDay(DAY);
            try {
              start.await();
              action.call();
              return true;
            } catch (ConsolidatedIsrAuditService.Conflict
                | ConcurrencyFailureException
                | org.springframework.dao.DuplicateKeyException e) {
              return false;
            } finally {
              ThreadLocalContextUtil.reset();
            }
          };
      var a = pool.submit(work);
      var b = pool.submit(work);
      start.countDown();
      assertTrue(a.get(30, TimeUnit.SECONDS) | b.get(30, TimeUnit.SECONDS));
    }
  }

  @Test
  void databaseConstraintsProtectLineageAndReadsNeverMutateFinancialTables() {
    var before = financial();
    var result = save();
    service.preview(1, PERIOD);
    service.get(1, result.calculationId());
    service.reconciliation(1, result.calculationId());
    assertEquals(before, financial());
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE m_savings_isr_calculation SET supersedes_id=id,correction_reason='bad' WHERE id=?",
                result.calculationId()));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE m_savings_isr_calculation SET period_from=period_through+1 WHERE id=?",
                result.calculationId()));
  }

  String financial() {
    return List.of(
            "m_savings_account",
            "m_savings_account_transaction",
            "m_savings_account_transaction_tax_details",
            "m_savings_product",
            "acc_gl_journal_entry")
        .stream()
        .map(
            t ->
                jdbc.queryForList(
                    "SELECT row_to_json(t)::text AS data FROM " + t + " t ORDER BY id"))
        .toList()
        .toString();
  }

  @Test
  void classificationAndPolicyDriftNeverTurnAConfigurationIntoTaxAuthority() {
    jdbc.update("UPDATE m_savings_account SET product_id=2 WHERE id=902");
    jdbc.update("DELETE FROM m_savings_account_policy WHERE savings_account_id=902");
    base.approve(
        2,
        ProductPolicyValidationTest.JSON
            .replace("\"SIGHT\"", "\"INTEREST_BEARING_SIGHT\"")
            .replace("\"interestMode\":\"NONE\"", "\"interestMode\":\"FIXED\""));
    base.bindPolicy(902, 2, "MAIN");
    var result = service.preview(1, PERIOD);
    assertEquals("INTEREST_BEARING_SIGHT", result.sources().accounts().getLast().classification());
    assertTrue(result.sources().accounts().getLast().structurallySupported());
    jdbc.update("UPDATE m_savings_product SET accounting_type=3 WHERE id=2");
    assertTrue(
        service
            .preview(1, PERIOD)
            .sources()
            .accounts()
            .getLast()
            .reasons()
            .contains(Reason.PRODUCT_CORE_CHANGED));
    jdbc.update("UPDATE m_client SET legal_form_enum=2 WHERE id=1");
    var corporate = service.preview(1, PERIOD);
    assertEquals(2, corporate.profile().legalForm());
    assertNull(corporate.profile().taxTreatment());
    assertTrue(corporate.profile().eligibleAccounts().isEmpty());
    assertEquals(
        List.of(903L),
        service.preview(2, PERIOD).sources().accounts().stream().map(Account::accountId).toList());
  }

  @Test
  void missingNativeSettingsAndMalformedPolicyReturnBlockedDiagnostics() {
    jdbc.update("UPDATE m_savings_product SET withhold_tax=NULL WHERE id=1");
    assertTrue(
        service
            .preview(1, PERIOD)
            .sources()
            .accounts()
            .getFirst()
            .reasons()
            .contains(Reason.SOURCE_DATA_UNAVAILABLE));
    jdbc.update(
        "UPDATE m_savings_product_policy_version SET policy_json='not json' WHERE savings_product_id=1");
    var result = service.preview(1, PERIOD);
    assertNull(result.calculatedIsr());
    assertTrue(
        result
            .sources()
            .accounts()
            .getFirst()
            .reasons()
            .contains(Reason.PRODUCT_POLICY_UNAVAILABLE));
  }

  @Test
  void sourceLimitsBlockTheWholeAggregateInsteadOfPublishingPartialTotals() {
    jdbc.update(
        "INSERT INTO m_savings_account(id,product_id,client_id,status_enum,deposit_type_enum,sub_status_enum,currency_code,currency_digits,withhold_tax,tax_group_id,account_balance_derived,activatedon_date)"
            + " SELECT g,1,1,300,100,0,'MXN',2,true,1,1,? FROM generate_series(1000,1200) g",
        DAY);
    try {
      var result = service.preview(1, PERIOD);
      assertFalse(result.sources().complete());
      assertTrue(result.sources().observations().isEmpty());
      assertTrue(result.blockers().contains(Reason.ACCOUNT_POPULATION_LIMIT_EXCEEDED));
      assertNull(result.calculatedIsr());
    } finally {
      jdbc.update("DELETE FROM m_savings_account WHERE id>=1000");
    }
    jdbc.update(
        "INSERT INTO m_savings_account_transaction(id,savings_account_id,transaction_type_enum,transaction_date,amount,is_reversed,is_reversal)"
            + " SELECT g,901,1,?,1,false,false FROM generate_series(1,20001) g",
        DAY);
    var result = service.preview(1, PERIOD);
    assertFalse(result.sources().complete());
    assertTrue(result.sources().observations().isEmpty());
    assertTrue(result.blockers().contains(Reason.SOURCE_TRANSACTION_LIMIT_EXCEEDED));
  }

  @Test
  void historicalReadsDoNotUseCurrentBalancesToReplaceSavedEvidence() {
    var first = save();
    ProductPolicyWorkflowIntegrationTest.setDay(DAY.plusDays(1));
    assertEquals("UNCHANGED_BLOCKED", service.reconciliation(1, first.calculationId()).status());
    jdbc.update(
        "UPDATE m_savings_account SET account_balance_derived=11,closedon_date=?,status_enum=600 WHERE id=901",
        DAY);
    assertEquals(
        "RECONCILIATION_REQUIRED", service.reconciliation(1, first.calculationId()).status());
    assertEquals(first, service.get(1, first.calculationId()));
    assertTrue(
        service
            .preview(1, PERIOD)
            .sources()
            .accounts()
            .getFirst()
            .reasons()
            .contains(Reason.ACCOUNT_STATUS_INELIGIBLE));
  }

  @Test
  void concurrentRuleProposalsCannotReuseTheSameExpectedRevision() throws Exception {
    String family = UUID.randomUUID().toString();
    var definition =
        new RuleDefinition(
            "UNCONFIRMED",
            "UNCONFIRMED",
            "MXN",
            DAY,
            DAY,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    race(
        () ->
            rules.propose(
                family,
                RatePropagationJson.JSON.toJson(
                    new RuleProposal(
                        UUID.randomUUID().toString(), 0L, definition, "Concurrent proposal"))));
    assertEquals(1, rules.versions(family, 0, 100).size());
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO m_savings_isr_rule_version(rule_id,version,tenant_identifier,effective_from,effective_through,author_id,created_at,change_reason,definition_json,snapshot_json)"
                    + " SELECT rule_id,2,tenant_identifier,effective_from,effective_through,author_id,created_at,change_reason,definition_json,snapshot_json FROM m_savings_isr_rule_version WHERE rule_id=?",
                family));
  }

  @AfterEach
  void clear() {
    ThreadLocalContextUtil.reset();
  }

  @AfterAll
  static void cleanup() {
    ProductPolicyPersistenceIntegrationTest.cleanup();
  }
}
