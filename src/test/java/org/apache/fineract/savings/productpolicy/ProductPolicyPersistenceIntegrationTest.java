/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** Real Liquibase/JDBC/locking against two isolated tenant schemas, no mocked persistence. */
class ProductPolicyPersistenceIntegrationTest {
  static PostgreSQLContainer<?> postgres;
  static DriverManagerDataSource admin;
  static Map<String, DataSource> sources;
  static String prefix;
  JdbcTemplate jdbc;
  ProductPolicyService service;
  TransactionTemplate tx;

  @BeforeAll
  static void database() throws Exception {
    String url = System.getProperty("policy.it.jdbcUrl");
    if (url == null) {
      postgres = new PostgreSQLContainer<>("postgres:16-alpine");
      postgres.start();
      admin =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    } else {
      if (!url.matches("jdbc:postgresql://[^/]+/web1350_[a-zA-Z0-9_]+"))
        throw new IllegalArgumentException("Only disposable web1350_* databases are supported.");
      admin =
          new DriverManagerDataSource(
              // Match the existing query parameters in Testcontainers JDBC URLs locally, too.
              url + "?loggerLevel=OFF",
              System.getProperty("policy.it.dbUser", System.getProperty("user.name")),
              System.getProperty("policy.it.dbPassword", ""));
    }
    prefix = "policy_" + UUID.randomUUID().toString().replace("-", "");
    sources = Map.of("one", source("one"), "two", source("two"));
    for (DataSource ds : sources.values()) {
      var j = new JdbcTemplate(ds);
      j.execute(
          "CREATE TABLE m_savings_product(id bigint PRIMARY KEY,deposit_type_enum integer,currency_code varchar(3),currency_digits integer,min_required_opening_balance numeric(19,6),nominal_annual_interest_rate numeric(19,6))");
      j.execute(
          "CREATE TABLE m_charge(id bigint PRIMARY KEY,is_active boolean,is_deleted boolean,currency_code varchar(3),charge_applies_to_enum integer,charge_time_enum integer,charge_calculation_enum integer,is_penalty boolean)");
      j.execute("CREATE TABLE m_appuser(id bigint PRIMARY KEY)");
      j.execute(
          "CREATE TABLE m_permission(id bigserial PRIMARY KEY,grouping varchar(100),code varchar(100) UNIQUE,entity_name varchar(100),action_name varchar(100),can_maker_checker boolean)");
      j.update("INSERT INTO m_permission(code) VALUES ('EXISTING_PERMISSION')");
      j.update("INSERT INTO m_appuser VALUES(1)");
      j.update(
          "INSERT INTO m_savings_product VALUES(1,100,'MXN',2,0,0),(2,100,'MXN',2,0,0),(3,100,'USD',2,0,0),(4,200,'MXN',2,0,0)");
      j.update(
          "INSERT INTO m_charge VALUES(1,true,false,'MXN',2,2,1,false),(2,true,false,'USD',2,2,1,false),(3,true,false,'MXN',1,2,1,false),(4,false,false,'MXN',2,2,1,false),(5,true,false,'MXN',2,1,1,false),(6,true,false,'MXN',2,2,1,true)");
      migrate(ds);
      migrate(ds);
      assertEquals(3, j.queryForObject("SELECT count(*) FROM m_permission", Integer.class));
      assertEquals(
          0, j.queryForObject("SELECT count(*) FROM m_savings_product_policy", Integer.class));
    }
  }

  static DataSource source(String name) {
    String schema = prefix + "_" + name;
    new JdbcTemplate(admin).execute("CREATE SCHEMA " + schema);
    // Testcontainers already adds URL options (for example ?loggerLevel=OFF). Pass the
    // schema as a connection property so those options cannot swallow a second query delimiter.
    var connectionProperties = new Properties();
    connectionProperties.setProperty("currentSchema", schema);
    var source =
        new DriverManagerDataSource(admin.getUrl(), admin.getUsername(), admin.getPassword());
    source.setConnectionProperties(connectionProperties);
    assertEquals(
        schema, new JdbcTemplate(source).queryForObject("SELECT current_schema()", String.class));
    return source;
  }

  static void migrate(DataSource ds) throws Exception {
    try (var connection = ds.getConnection()) {
      var db =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try (var lb =
          new Liquibase(
              "db/changelog/tenant/module/savings/parts/080-create-savings-product-policy.xml",
              new ClassLoaderResourceAccessor(),
              db)) {
        lb.update(new Contexts(), new LabelExpression());
      }
    }
  }

  @BeforeEach
  void services() {
    var routing =
        new AbstractRoutingDataSource() {
          @Override
          protected Object determineCurrentLookupKey() {
            return ThreadLocalContextUtil.getTenant().getTenantIdentifier();
          }
        };
    routing.setTargetDataSources(Map.of("one", sources.get("one"), "two", sources.get("two")));
    routing.setLenientFallback(false);
    routing.afterPropertiesSet();
    jdbc = new JdbcTemplate(routing);
    var security = mock(PlatformSecurityContext.class);
    var actor = mock(AppUser.class);
    when(actor.getId()).thenReturn(1L);
    when(security.authenticatedUser()).thenReturn(actor);
    service = new ProductPolicyService(security, jdbc, new ProductPolicyValidation());
    tx = new TransactionTemplate(new DataSourceTransactionManager(routing));
    for (String name : sources.keySet())
      new JdbcTemplate(sources.get(name)).update("DELETE FROM m_savings_product_policy");
    tenant("one");
  }

  static void tenant(String name) {
    var tenant = mock(FineractPlatformTenant.class);
    when(tenant.getTenantIdentifier()).thenReturn(name);
    ThreadLocalContextUtil.setTenant(tenant);
  }

  @AfterEach
  void clear() {
    ThreadLocalContextUtil.clearTenant();
  }

  ProductPolicyData.Configuration save(long id, String json) {
    return tx.execute(status -> service.save(id, json));
  }

  @Test
  void createRetrieveUpdateLegacyAndDuplicateAssociation() {
    assertEquals(ProductPolicyData.Status.UNCONFIGURED, service.get(1).status());
    assertNull(service.get(1).policy());
    var created = save(1, ProductPolicyValidationTest.JSON);
    assertEquals(1, created.revision());
    assertFalse(created.operational());
    assertEquals(created, service.get(1));
    assertThrows(
        ProductPolicyService.Conflict.class, () -> save(1, ProductPolicyValidationTest.JSON));
    assertEquals(
        2,
        save(
                1,
                ProductPolicyValidationTest.JSON.replace(
                    "\"expectedRevision\":0", "\"expectedRevision\":1"))
            .revision());
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM m_savings_product_policy", Integer.class));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO m_savings_product_policy SELECT * FROM m_savings_product_policy"));
    assertEquals(
        0,
        jdbc.queryForObject(
                "SELECT nominal_annual_interest_rate FROM m_savings_product WHERE id=1",
                BigDecimal.class)
            .signum());
    assertEquals(ProductPolicyData.Status.UNCONFIGURED, service.get(2).status());
  }

  @Test
  void tenantRoutingPreventsCrossTenantDraftAccessEvenForSameProductId() {
    save(1, ProductPolicyValidationTest.JSON);
    tenant("two");
    assertEquals(ProductPolicyData.Status.UNCONFIGURED, service.get(1).status());
    assertEquals(
        1,
        save(1, ProductPolicyValidationTest.JSON.replace("Main Account", "Secondary Account"))
            .revision());
    tenant("one");
    assertEquals("Main Account", service.get(1).policy().accountDesignation());
  }

  @Test
  void coreEligibilityChargeSupportAndForeignKeysAreEnforced() {
    assertThrows(ProductPolicyService.NotFound.class, () -> service.get(999));
    assertThrows(
        ProductPolicyService.NotFound.class, () -> save(4, ProductPolicyValidationTest.JSON));
    assertThrows(
        org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException.class,
        () -> save(3, ProductPolicyValidationTest.JSON));
    for (int charge : List.of(2, 3, 4, 5, 6, 999)) {
      String json =
          ProductPolicyValidationTest.JSON.replace(
              "\"feeApplicable\":false", "\"feeApplicable\":true,\"feeChargeId\":" + charge);
      assertThrows(
          org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException
              .class,
          () -> save(1, json));
    }
    save(
        1,
        ProductPolicyValidationTest.JSON.replace(
            "\"feeApplicable\":false", "\"feeApplicable\":true,\"feeChargeId\":1"));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("DELETE FROM m_charge WHERE id=1"));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("DELETE FROM m_savings_product WHERE id=1"));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE m_savings_product_policy SET status='ACTIVE'"));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE m_savings_product_policy SET revision=0"));
  }

  @Test
  void failedWriteRollsBackAndLeavesCoreAndPreviousRevisionIntact() {
    save(1, ProductPolicyValidationTest.JSON);
    var security = mock(PlatformSecurityContext.class);
    var missingActor = mock(AppUser.class);
    when(missingActor.getId()).thenReturn(999L);
    when(security.authenticatedUser()).thenReturn(missingActor);
    var failing = new ProductPolicyService(security, jdbc, new ProductPolicyValidation());
    String update =
        ProductPolicyValidationTest.JSON.replace(
            "\"expectedRevision\":0", "\"expectedRevision\":1");
    assertThrows(
        org.apache.fineract.infrastructure.core.exception.PlatformInternalServerException.class,
        () -> tx.execute(status -> failing.save(1, update)));
    assertEquals(1, service.get(1).revision());
    assertEquals(
        0,
        jdbc.queryForObject(
                "SELECT nominal_annual_interest_rate FROM m_savings_product WHERE id=1",
                BigDecimal.class)
            .signum());
  }

  @Test
  void concurrentWritersCannotLoseChanges() throws Exception {
    save(1, ProductPolicyValidationTest.JSON);
    String update =
        ProductPolicyValidationTest.JSON.replace(
            "\"expectedRevision\":0", "\"expectedRevision\":1");
    CountDownLatch start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Boolean> writer =
          () -> {
            tenant("one");
            try {
              start.await();
              save(1, update);
              return true;
            } catch (ProductPolicyService.Conflict e) {
              return false;
            } finally {
              ThreadLocalContextUtil.clearTenant();
            }
          };
      var a = workers.submit(writer);
      var b = workers.submit(writer);
      start.countDown();
      assertNotEquals(a.get(), b.get());
    }
    assertEquals(2, service.get(1).revision());
  }

  @AfterAll
  static void cleanup() {
    if (admin != null && prefix != null) {
      for (String name : List.of("one", "two"))
        new JdbcTemplate(admin)
            .execute("DROP SCHEMA IF EXISTS " + prefix + "_" + name + " CASCADE");
    }
    if (postgres != null) postgres.stop();
  }
}
