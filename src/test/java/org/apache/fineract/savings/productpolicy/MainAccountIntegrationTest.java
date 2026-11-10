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
import java.util.List;
import java.util.concurrent.*;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Real PostgreSQL migrations, tenant routing, service transactions and locking. No financial posts.
 */
class MainAccountIntegrationTest {
  ProductPolicyWorkflowIntegrationTest fixture;
  MainAccountService service;
  JdbcTemplate jdbc;
  static final LocalDate DAY = ProductPolicyWorkflowIntegrationTest.DAY;

  @BeforeAll
  static void database() throws Exception {
    ProductPolicyWorkflowIntegrationTest.database();
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      j.execute("CREATE TABLE m_office(id bigint PRIMARY KEY,hierarchy varchar(100))");
      j.update("INSERT INTO m_office VALUES(1,'.1.'),(2,'.2.')");
      j.execute("ALTER TABLE m_client ADD COLUMN office_id bigint DEFAULT 1");
      j.execute("ALTER TABLE m_client ADD COLUMN status_enum integer DEFAULT 300");
      j.update("INSERT INTO m_client(id,office_id) VALUES(1,1),(2,1),(3,2)");
      j.execute("ALTER TABLE m_savings_account ADD COLUMN product_id bigint DEFAULT 1");
      j.execute("ALTER TABLE m_savings_account ADD COLUMN client_id bigint DEFAULT 1");
      j.execute("ALTER TABLE m_savings_account ADD COLUMN status_enum integer DEFAULT 100");
      j.execute("ALTER TABLE m_savings_account ADD COLUMN group_id bigint");
      j.execute("ALTER TABLE m_savings_account ADD COLUMN deposit_type_enum integer DEFAULT 100");
      j.update("INSERT INTO m_savings_account(id) VALUES(901),(902),(903)");
      j.update("UPDATE m_savings_account SET client_id=2,product_id=2 WHERE id=903");
      for (int repeat = 0; repeat < 2; repeat++) {
        try (var connection = ds.getConnection()) {
          var db =
              DatabaseFactory.getInstance()
                  .findCorrectDatabaseImplementation(new JdbcConnection(connection));
          try (var lb =
              new Liquibase(
                  "db/changelog/tenant/module/savings/parts/087-main-account-relationships.xml",
                  new ClassLoaderResourceAccessor(),
                  db)) {
            lb.update(new Contexts(), new LabelExpression());
          }
        }
      }
      assertEquals(
          2,
          j.queryForObject(
              "SELECT COUNT(*) FROM m_permission WHERE code IN"
                  + " ('READ_SAVINGS_ACCOUNT_RELATIONSHIP','BIND_MAIN_SAVINGS_ACCOUNT')",
              Integer.class));
      assertEquals(
          0, j.queryForObject("SELECT COUNT(*) FROM m_savings_main_account_audit", Integer.class));
    }
  }

  @BeforeEach
  void setup() {
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values())
      new JdbcTemplate(ds).update("DELETE FROM m_savings_main_account_audit");
    fixture = new ProductPolicyWorkflowIntegrationTest();
    fixture.setup();
    jdbc = fixture.jdbc;
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      j.update("UPDATE m_client SET status_enum=300");
      j.update(
          "UPDATE m_savings_account SET product_id=1,client_id=1,status_enum=100,group_id=NULL,deposit_type_enum=100");
      j.update("UPDATE m_savings_account SET status_enum=600 WHERE id=902");
      j.update("UPDATE m_savings_account SET client_id=2,product_id=2 WHERE id=903");
    }
    var office = mock(Office.class);
    when(office.getHierarchy()).thenReturn(".1.");
    when(fixture.security.authenticatedUser().getOffice()).thenReturn(office);
    var accounts = mock(SavingsAccountRepositoryWrapper.class);
    when(accounts.findOneWithNotFoundDetection(anyLong()))
        .thenAnswer(
            call -> {
              long id = call.getArgument(0);
              var row = jdbc.queryForMap("SELECT * FROM m_savings_account WHERE id=?", id);
              var a = mock(SavingsAccount.class);
              when(a.getId()).thenReturn(id);
              when(a.clientId()).thenReturn(((Number) row.get("client_id")).longValue());
              when(a.groupId()).thenReturn(null);
              when(a.productId()).thenReturn(((Number) row.get("product_id")).longValue());
              when(a.getCurrency()).thenReturn(new MonetaryCurrency("MXN", 2, 1));
              return a;
            });
    var resolver =
        new ProductTransactionPolicyResolver(
            jdbc, fixture.workflow, new ProductPolicyCoreSnapshot(jdbc));
    var target =
        new MainAccountService(
            fixture.security,
            jdbc,
            fixture.workflow,
            resolver,
            accounts,
            new MainAccountAuditService(jdbc));
    var factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(jdbc.getDataSource()),
            new AnnotationTransactionAttributeSource()));
    service = (MainAccountService) factory.getProxy();
  }

  void approve(long product, String json) {
    fixture.tx(() -> fixture.drafts.save(product, json));
    fixture.tx(
        () -> fixture.workflow.submit(product, new ProductPolicyWorkflowData.Submit(1L, DAY)));
    ProductPolicyWorkflowIntegrationTest.ACTOR.set(2L);
    fixture.tx(
        () ->
            fixture.workflow.decide(
                product, 1, true, new ProductPolicyWorkflowData.Decision("Reviewed")));
    ProductPolicyWorkflowIntegrationTest.ACTOR.set(1L);
  }

  void bindPolicy(long account, long product, String purpose) {
    jdbc.update(
        "INSERT INTO m_savings_account_policy VALUES(?,?,1,?,CURRENT_TIMESTAMP)",
        account,
        product,
        purpose);
  }

  MainAccountData.Relationship bind(long account) {
    return service.bind(1, "{\"savingsAccountId\":" + account + "}");
  }

  void prepare() {
    approve(1, ProductPolicyValidationTest.JSON);
    bindPolicy(901, 1, "MAIN");
  }

  long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  @Test
  void identifiesApprovedMainAndBindsIdempotentlyWithoutChangingNativeAccount() {
    assertNull(service.get(1));
    prepare();
    var nativeBefore = jdbc.queryForList("SELECT * FROM m_savings_account ORDER BY id");
    var identified = service.get(1);
    assertEquals(901, identified.savingsAccountId());
    assertEquals(1, identified.policyVersion());
    assertFalse(identified.active());
    assertEquals(0, count("m_savings_policy_opening_slot"));
    assertEquals(identified, bind(901));
    assertEquals(identified, bind(901));
    assertEquals(1, count("m_savings_policy_opening_slot"));
    assertEquals(nativeBefore, jdbc.queryForList("SELECT * FROM m_savings_account ORDER BY id"));
    var audit = service.audit(1, 0, 100);
    assertEquals(1, audit.size());
    assertEquals("BIND", audit.getFirst().action());
    assertEquals(1, audit.getFirst().actorId());
    assertEquals("one", audit.getFirst().tenantIdentifier());
    assertNotNull(audit.getFirst().recordedAt());
    assertEquals(0, service.audit(1, audit.getFirst().id(), 100).size());
  }

  @Test
  void existingNativeOpeningReservationIsReused() {
    prepare();
    jdbc.update("INSERT INTO m_savings_policy_opening_slot VALUES(1,'MAIN',901)");
    bind(901);
    assertEquals(1, count("m_savings_policy_opening_slot"));
    assertEquals(1, count("m_savings_main_account_audit"));
  }

  @Test
  void permissionsTenantBusinessDateAndOfficeScopeFailClosed() {
    var actor = fixture.security.authenticatedUser();
    doThrow(new AccessDeniedException("Denied"))
        .when(actor)
        .validateHasPermissionTo(MainAccountService.BIND);
    assertThrows(AccessDeniedException.class, () -> bind(901));
    doThrow(new AccessDeniedException("Denied"))
        .when(actor)
        .validateHasPermissionTo(MainAccountService.READ);
    assertThrows(AccessDeniedException.class, () -> service.get(1));
    assertThrows(AccessDeniedException.class, () -> service.audit(1, 0, 100));
    assertThrows(AccessDeniedException.class, () -> service.template());
    reset(fixture.security.authenticatedUser());
    ThreadLocalContextUtil.clearTenant();
    assertThrows(AccessDeniedException.class, () -> service.template());
    ProductPolicyPersistenceIntegrationTest.tenant("one");
    ThreadLocalContextUtil.setBusinessDates(new java.util.HashMap<>());
    assertThrows(AccessDeniedException.class, () -> service.template());
    assertEquals(0, count("m_savings_main_account_audit"));
  }

  @Test
  void crossClientAndInvisibleClientCannotBindOrRead() {
    prepare();
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.bind(2, "{\"savingsAccountId\":901}"));
    assertThrows(ClientNotFoundException.class, () -> service.get(3));
    assertThrows(ClientNotFoundException.class, () -> service.audit(3, 0, 100));
    assertThrows(
        ClientNotFoundException.class, () -> service.bind(3, "{\"savingsAccountId\":901}"));
    assertEquals(0, count("m_savings_main_account_audit"));
    assertEquals(0, count("m_savings_policy_opening_slot"));
  }

  @Test
  void legacyUnboundAndInvalidPurposeAccountsCannotBeAdopted() {
    assertNull(service.get(1));
    assertThrows(PlatformApiDataValidationException.class, () -> bind(901));
    approve(
        1,
        ProductPolicyValidationTest.JSON
            .replace("\"MAIN\"", "\"GUARANTEED\"")
            .replace("\"liquidGuarantee\":false", "\"liquidGuarantee\":true"));
    bindPolicy(901, 1, "GUARANTEED");
    assertThrows(PlatformApiDataValidationException.class, () -> bind(901));
    assertNull(service.get(1));
    assertEquals(0, count("m_savings_main_account_audit"));
    assertEquals(0, count("m_savings_policy_opening_slot"));
  }

  @Test
  void configuredUnboundAndCoreDriftAreRejectedWithoutRelationshipSideEffects() {
    approve(1, ProductPolicyValidationTest.JSON);
    assertThrows(PlatformApiDataValidationException.class, () -> bind(901));
    bindPolicy(901, 1, "MAIN");
    jdbc.update("UPDATE m_savings_product SET currency_code='USD' WHERE id=1");
    try {
      assertThrows(PlatformApiDataValidationException.class, () -> bind(901));
    } finally {
      jdbc.update("UPDATE m_savings_product SET currency_code='MXN' WHERE id=1");
    }
    assertEquals(0, count("m_savings_main_account_audit"));
  }

  @Test
  void duplicateOpenAccountsAndPendingSlotsCannotBeReassigned() {
    prepare();
    bind(901);
    jdbc.update("UPDATE m_savings_account SET status_enum=100 WHERE id=902");
    bindPolicy(902, 1, "MAIN");
    assertThrows(PlatformApiDataValidationException.class, () -> service.get(1));
    assertThrows(PlatformApiDataValidationException.class, () -> bind(902));
    assertEquals(
        901,
        jdbc.queryForObject(
            "SELECT savings_account_id FROM m_savings_policy_opening_slot", Long.class));
    assertEquals(1, count("m_savings_main_account_audit"));
  }

  @Test
  void terminalNativeAccountCanBeReplacedWithPreservedHistoryButCannotBeReopenedOverSuccessor() {
    prepare();
    bind(901);
    jdbc.update("UPDATE m_savings_account SET status_enum=600 WHERE id=901");
    assertNull(service.get(1));
    assertThrows(PlatformApiDataValidationException.class, () -> bind(901));
    jdbc.update("UPDATE m_savings_account SET status_enum=200 WHERE id=902");
    bindPolicy(902, 1, "MAIN");
    assertEquals(902, bind(902).savingsAccountId());
    assertEquals(
        List.of("BIND", "RETIRE", "BIND"),
        service.audit(1, 0, 100).stream().map(MainAccountData.Audit::action).toList());
    jdbc.update("UPDATE m_savings_account SET status_enum=300 WHERE id=901");
    assertThrows(PlatformApiDataValidationException.class, () -> bind(901));
    assertEquals(
        902,
        jdbc.queryForObject(
            "SELECT savings_account_id FROM m_savings_policy_opening_slot", Long.class));
    jdbc.update("UPDATE m_client SET status_enum=600 WHERE id=1");
    assertEquals(3, service.audit(1, 0, 100).size());
    assertThrows(PlatformApiDataValidationException.class, () -> service.get(1));
  }

  @Test
  void invalidLifecycleGroupAndAccountTypeAreRejected() {
    prepare();
    for (int status : List.of(400, 500, 600, 700, 800)) {
      jdbc.update("UPDATE m_savings_account SET status_enum=? WHERE id=901", status);
      assertThrows(PlatformApiDataValidationException.class, () -> bind(901));
    }
    jdbc.update("UPDATE m_savings_account SET status_enum=100,group_id=42 WHERE id=901");
    assertThrows(PlatformApiDataValidationException.class, () -> bind(901));
    jdbc.update("UPDATE m_savings_account SET group_id=NULL,deposit_type_enum=200 WHERE id=901");
    assertThrows(PlatformApiDataValidationException.class, () -> bind(901));
    jdbc.update("UPDATE m_client SET status_enum=600 WHERE id=1");
    assertThrows(PlatformApiDataValidationException.class, () -> bind(901));
    assertEquals(0, count("m_savings_main_account_audit"));
  }

  @Test
  void concurrentIdenticalBindingsProduceOneReservationAndOneAudit() throws Exception {
    prepare();
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      Callable<MainAccountData.Relationship> writer =
          () -> {
            ProductPolicyPersistenceIntegrationTest.tenant("one");
            ProductPolicyWorkflowIntegrationTest.setDay(DAY);
            try {
              start.await();
              return bind(901);
            } finally {
              ThreadLocalContextUtil.reset();
            }
          };
      var a = workers.submit(writer);
      var b = workers.submit(writer);
      start.countDown();
      assertEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    }
    assertEquals(1, count("m_savings_policy_opening_slot"));
    assertEquals(1, count("m_savings_main_account_audit"));
  }

  @Test
  void establishedRelationshipCannotBeReassignedByChangingNativeClient() {
    prepare();
    bind(901);
    jdbc.update("UPDATE m_savings_account SET client_id=2 WHERE id=901");
    assertThrows(PlatformApiDataValidationException.class, () -> service.get(2));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.bind(2, "{\"savingsAccountId\":901}"));
    assertEquals(1, service.audit(1, 0, 100).size());
    assertEquals(0, service.audit(2, 0, 100).size());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT client_id FROM m_savings_policy_opening_slot WHERE savings_account_id=901",
            Long.class));
  }

  @Test
  void tenantIsolationWithSameIdentifiers() {
    prepare();
    bind(901);
    ProductPolicyPersistenceIntegrationTest.tenant("two");
    assertNull(service.get(1));
    assertEquals(List.of(), service.audit(1, 0, 100));
    prepare();
    bind(901);
    assertEquals("two", service.audit(1, 0, 100).getFirst().tenantIdentifier());
    ProductPolicyPersistenceIntegrationTest.tenant("one");
    assertEquals("one", service.audit(1, 0, 100).getFirst().tenantIdentifier());
  }

  @Test
  void auditFailureRollsBackReservationAndDatabaseConstraintsProtectEvidence() {
    prepare();
    ProductPolicyWorkflowIntegrationTest.ACTOR.set(999L);
    assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> bind(901));
    assertEquals(0, count("m_savings_policy_opening_slot"));
    assertEquals(0, count("m_savings_main_account_audit"));
    ProductPolicyWorkflowIntegrationTest.ACTOR.set(1L);
    bind(901);
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> jdbc.update("DELETE FROM m_savings_account_policy WHERE savings_account_id=901"));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE m_savings_main_account_audit SET action='RELEASE'"));
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
