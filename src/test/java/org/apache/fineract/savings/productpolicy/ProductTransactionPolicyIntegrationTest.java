/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductTransactionPolicyData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Policy/audit persistence only. Native financial rollback is covered separately by HTTP tests. */
class ProductTransactionPolicyIntegrationTest {
  ProductPolicyWorkflowIntegrationTest fixture;
  JdbcTemplate jdbc;
  ProductTransactionPolicyResolver resolver;
  ProductTransactionEnforcement enforcement;
  static final LocalDate DAY = ProductPolicyWorkflowIntegrationTest.DAY;

  @BeforeAll
  static void database() throws Exception {
    ProductPolicyWorkflowIntegrationTest.database();
    for (var ds : ProductPolicyPersistenceIntegrationTest.sources.values()) {
      var j = new JdbcTemplate(ds);
      j.execute("ALTER TABLE m_savings_account ADD COLUMN product_id bigint");
      j.execute("INSERT INTO m_savings_account VALUES (901,1),(902,2)");
      try (var connection = ds.getConnection()) {
        var db =
            DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        try (var lb =
            new Liquibase(
                "db/changelog/tenant/module/savings/parts/085-transaction-policy-audit.xml",
                new ClassLoaderResourceAccessor(),
                db)) {
          lb.update(new Contexts(), new LabelExpression());
          lb.update(new Contexts(), new LabelExpression());
        }
      }
    }
  }

  @BeforeEach
  void setup() {
    fixture = new ProductPolicyWorkflowIntegrationTest();
    fixture.setup();
    jdbc = fixture.jdbc;
    jdbc.update("DELETE FROM m_savings_transaction_policy_audit");
    jdbc.update("UPDATE m_savings_account SET product_id=1 WHERE id=901");
    resolver =
        proxy(
            new ProductTransactionPolicyResolver(
                jdbc, fixture.workflow, new ProductPolicyCoreSnapshot(jdbc)));
    enforcement =
        proxy(
            new ProductTransactionEnforcement(
                resolver,
                new ProductTransactionPolicyEvaluator(),
                proxy(new ProductTransactionAuditService(jdbc)),
                fixture.security));
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

  static SavingsAccount account(long id, long product) {
    var a = mock(SavingsAccount.class);
    when(a.getId()).thenReturn(id);
    when(a.productId()).thenReturn(product);
    when(a.getCurrency()).thenReturn(new MonetaryCurrency("MXN", 2, 1));
    return a;
  }

  static SavingsAccountTransaction transaction(long account, long product) {
    var a = account(account, product);
    org.apache.fineract.organisation.monetary.domain.MoneyHelper.initializeTenantRoundingMode(
        "one", java.math.RoundingMode.HALF_EVEN.ordinal());
    var t =
        SavingsAccountTransaction.withdrawal(
            a,
            null,
            null,
            DAY,
            org.apache.fineract.organisation.monetary.domain.Money.of(
                a.getCurrency(), new BigDecimal("1.01")),
            "be06-fixture");
    t.setId(500L);
    return t;
  }

  void approveAndBind(String json, LocalDate day) {
    fixture.tx(() -> fixture.drafts.save(1, json));
    fixture.submit(day);
    ProductPolicyWorkflowIntegrationTest.ACTOR.set(2L);
    fixture.decide(1, true);
    jdbc.update("INSERT INTO m_savings_account_policy VALUES(901,1,1,'MAIN',CURRENT_TIMESTAMP)");
  }

  @Test
  void legacyIsDistinctFromConfiguredButUnboundDraftPendingAndFuture() {
    assertNull(fixture.tx(() -> resolver.resolve(account(901, 1))).policy());
    fixture.draft();
    assertEquals(
        "account.binding.missing",
        fixture.tx(() -> resolver.resolve(account(901, 1))).decision().code());
    fixture.submit(DAY.plusDays(1));
    jdbc.update("INSERT INTO m_savings_account_policy VALUES(901,1,1,'MAIN',CURRENT_TIMESTAMP)");
    assertEquals(
        "policy.not.effective",
        fixture.tx(() -> resolver.resolve(account(901, 1))).decision().code());
    ProductPolicyWorkflowIntegrationTest.ACTOR.set(2L);
    fixture.decide(1, true);
    assertEquals(
        "policy.not.effective",
        fixture.tx(() -> resolver.resolve(account(901, 1))).decision().code());
    ProductPolicyWorkflowIntegrationTest.setDay(DAY.plusDays(1));
    assertEquals(1L, fixture.tx(() -> resolver.resolve(account(901, 1))).version());
  }

  @Test
  void activeBoundSnapshotIgnoresMutableDraftButNeverStaleBindingOrCoreDrift() {
    approveAndBind(ProductPolicyValidationTest.JSON, DAY);
    assertTrue(fixture.tx(() -> resolver.resolve(account(901, 1))).decision().allowed());
    ProductPolicyWorkflowIntegrationTest.ACTOR.set(1L);
    fixture.edit(1);
    assertTrue(fixture.tx(() -> resolver.resolve(account(901, 1))).decision().allowed());
    fixture.submit(DAY.plusDays(1));
    ProductPolicyWorkflowIntegrationTest.ACTOR.set(2L);
    fixture.decide(2, true);
    ProductPolicyWorkflowIntegrationTest.setDay(DAY.plusDays(1));
    assertEquals(
        "policy.transition.unsupported",
        fixture.tx(() -> resolver.resolve(account(901, 1))).decision().code());
    jdbc.update("UPDATE m_savings_account_policy SET version=2 WHERE savings_account_id=901");
    jdbc.update("UPDATE m_savings_product SET nominal_annual_interest_rate=1 WHERE id=1");
    try {
      assertEquals(
          "core.snapshot.changed",
          fixture.tx(() -> resolver.resolve(account(901, 1))).decision().code());
    } finally {
      jdbc.update("UPDATE m_savings_product SET nominal_annual_interest_rate=0 WHERE id=1");
    }
  }

  @Test
  void tenantAndBindingMismatchFailClosedWithoutFallback() {
    approveAndBind(ProductPolicyValidationTest.JSON, DAY);
    jdbc.update("UPDATE m_savings_account SET product_id=2 WHERE id=901");
    assertEquals(
        "account.binding.invalid",
        fixture.tx(() -> resolver.resolve(account(901, 2))).decision().code());
    ProductPolicyPersistenceIntegrationTest.tenant("two");
    assertNull(fixture.tx(() -> resolver.resolve(account(901, 1))).policy());
    ThreadLocalContextUtil.clearTenant();
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () ->
            new ProductTransactionPolicyResolver(
                    jdbc, fixture.workflow, new ProductPolicyCoreSnapshot(jdbc))
                .resolve(account(901, 1)));
  }

  @Test
  void deniedAuditSurvivesRollbackAndCaughtExceptionStillPoisonsTheOuterTransaction() {
    approveAndBind(
        ProductPolicyValidationTest.JSON.replace(
            "\"withdrawalsAllowed\":true", "\"withdrawalsAllowed\":false"),
        DAY);
    assertThrows(
        UnexpectedRollbackException.class,
        () ->
            fixture.tx(
                () -> {
                  try {
                    enforcement.validate(transaction(901, 1), Operation.WITHDRAWAL);
                  } catch (
                      org.apache.fineract.infrastructure.core.exception
                              .PlatformApiDataValidationException
                          expected) {
                  }
                  return null;
                }));
    assertEquals(
        "POLICY_DENIED",
        jdbc.queryForObject(
            "SELECT outcome FROM m_savings_transaction_policy_audit", String.class));
    assertNull(
        jdbc.queryForObject(
            "SELECT native_transaction_id FROM m_savings_transaction_policy_audit", Long.class));
    assertEquals(
        2L,
        jdbc.queryForObject("SELECT actor_id FROM m_savings_transaction_policy_audit", Long.class));
    ProductPolicyPersistenceIntegrationTest.tenant("two");
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_savings_transaction_policy_audit", Integer.class));
  }

  @Test
  void eligibleEvidenceIsAtomicWithOuterCommandAndAuditFailureFailsClosed() {
    approveAndBind(ProductPolicyValidationTest.JSON, DAY);
    assertThrows(
        IllegalStateException.class,
        () ->
            fixture.tx(
                () -> {
                  enforcement.validate(transaction(901, 1), Operation.WITHDRAWAL);
                  throw new IllegalStateException("later transfer leg fails");
                }));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_savings_transaction_policy_audit", Integer.class));
    fixture.tx(
        () -> {
          enforcement.validate(transaction(901, 1), Operation.WITHDRAWAL);
          return null;
        });
    assertEquals(
        500L,
        jdbc.queryForObject(
            "SELECT native_transaction_id FROM m_savings_transaction_policy_audit", Long.class));
    var denied =
        new Evidence(
            UUID.randomUUID().toString(),
            "one",
            2L,
            901,
            1,
            1L,
            Operation.DEPOSIT,
            "POLICY_DENIED",
            "deposits.disabled",
            DAY,
            null);
    var audit = proxy(new ProductTransactionAuditService(jdbc));
    audit.record(denied);
    assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> audit.record(denied));
  }

  @Test
  void simultaneousCreditsCannotAcquireAnExecutableUnderCapDecision() throws Exception {
    approveAndBind(ProductPolicyValidationTest.JSON, DAY);
    try (var workers = Executors.newFixedThreadPool(2)) {
      Callable<String> credit =
          () -> {
            ProductPolicyPersistenceIntegrationTest.tenant("one");
            ProductPolicyWorkflowIntegrationTest.setDay(DAY);
            try {
              fixture.tx(
                  () -> {
                    enforcement.validate(transaction(901, 1), Operation.DEPOSIT);
                    return null;
                  });
              return "unexpected allow";
            } catch (
                org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException
                    e) {
              return "blocked";
            } finally {
              ThreadLocalContextUtil.reset();
            }
          };
      var a = workers.submit(credit);
      var b = workers.submit(credit);
      assertEquals("blocked", a.get(20, TimeUnit.SECONDS));
      assertEquals("blocked", b.get(20, TimeUnit.SECONDS));
    }
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM m_savings_transaction_policy_audit", Integer.class));
  }

  @AfterEach
  void clear() {
    fixture.clear();
  }

  @AfterAll
  static void cleanup() {
    org.apache.fineract.organisation.monetary.domain.MoneyHelper.clearCacheForTenant("one");
    ProductPolicyPersistenceIntegrationTest.cleanup();
  }
}
