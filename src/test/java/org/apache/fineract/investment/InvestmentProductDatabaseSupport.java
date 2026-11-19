/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** Two actual disposable tenant databases; native tables here are configuration fixtures only. */
abstract class InvestmentProductDatabaseSupport {
  static PostgreSQLContainer<?> postgres;
  static DriverManagerDataSource admin;
  static Map<String, DataSource> sources;
  static String prefix;
  static final AtomicLong IDS = new AtomicLong(100);
  JdbcTemplate jdbc;
  InvestmentProductService service;
  PlatformSecurityContext security;
  AppUser actor;
  long product, other, ordinary, recurring;

  @BeforeAll
  static void databases() throws Exception {
    String url = System.getProperty("investment.it.jdbcUrl");
    if (url == null) {
      Assumptions.assumeTrue(
          DockerClientFactory.instance().isDockerAvailable(),
          "Docker unavailable; alternatively set investment.it.jdbcUrl to a disposable web1349_* PostgreSQL database.");
      postgres = new PostgreSQLContainer<>("postgres:16-alpine");
      postgres.start();
      admin =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    } else {
      if (!url.matches("jdbc:postgresql://[^/]+/web1349_[a-zA-Z0-9_]+"))
        throw new IllegalArgumentException("Only disposable web1349_* databases are supported.");
      admin =
          new DriverManagerDataSource(
              url,
              System.getProperty("investment.it.dbUser", System.getProperty("user.name")),
              System.getProperty("investment.it.dbPassword", ""));
    }
    prefix = "web1349_" + UUID.randomUUID().toString().replace("-", "");
    sources = Map.of("one", database("one"), "two", database("two"));
    for (var ds : sources.values()) {
      var j = new JdbcTemplate(ds);
      j.execute(
          "CREATE TABLE m_savings_product(id bigint PRIMARY KEY,deposit_type_enum integer NOT NULL,name varchar(100),currency_code varchar(3),currency_digits integer,currency_multiplesof integer,accounting_type integer,is_dormancy_tracking_active boolean,interest_posting_period_enum integer,interest_compounding_period_enum integer,withhold_tax boolean,tax_group_id bigint,interest_calculation_type_enum integer DEFAULT 1,interest_calculation_days_in_year_type_enum integer DEFAULT 365,min_balance_for_interest_calculation numeric(19,6))");
      j.execute(
          "CREATE TABLE m_deposit_product_term_and_preclosure(id bigint PRIMARY KEY,savings_product_id bigint UNIQUE REFERENCES m_savings_product(id),min_deposit_term integer,max_deposit_term integer,min_deposit_term_type_enum integer,max_deposit_term_type_enum integer,in_multiples_of_deposit_term integer,in_multiples_of_deposit_term_type_enum integer,min_deposit_amount numeric(19,6),max_deposit_amount numeric(19,6),deposit_amount numeric(19,6),pre_closure_penal_applicable boolean,pre_closure_penal_interest numeric(19,6),pre_closure_penal_interest_on_enum integer)");
      j.execute(
          "CREATE TABLE acc_gl_account(id bigint PRIMARY KEY,disabled boolean,classification_enum integer,account_usage integer)");
      j.execute(
          "CREATE TABLE acc_product_mapping(id bigint PRIMARY KEY,product_id bigint,product_type integer,financial_account_type integer,gl_account_id bigint REFERENCES acc_gl_account(id),payment_type bigint,charge_id bigint)");
      j.execute("CREATE TABLE m_appuser(id bigint PRIMARY KEY)");
      j.execute(
          "CREATE TABLE m_permission(id bigserial PRIMARY KEY,grouping varchar(100),code varchar(100) UNIQUE,entity_name varchar(100),action_name varchar(100),can_maker_checker boolean)");
      j.update("INSERT INTO m_appuser VALUES (1),(2)");
      migrate(ds);
      migrate(ds);
      assertEquals(2, j.queryForObject("SELECT count(*) FROM m_permission", Integer.class));
    }
  }

  private static DataSource database(String tenant) {
    String name = prefix + "_" + tenant;
    new JdbcTemplate(admin).execute("CREATE DATABASE " + name);
    String base = admin.getUrl().substring(0, admin.getUrl().lastIndexOf('/') + 1);
    return new DriverManagerDataSource(base + name, admin.getUsername(), admin.getPassword());
  }

  static void migrate(DataSource ds) throws Exception {
    try (var connection = ds.getConnection()) {
      var db =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try (var lb =
          new Liquibase(
              "db/changelog/tenant/module/savings/parts/094-investment-product-policy.xml",
              new ClassLoaderResourceAccessor(),
              db)) {
        lb.update(new Contexts(), new LabelExpression());
      }
    }
  }

  @BeforeEach
  void setupServicesAndSyntheticProducts() {
    var routing =
        new AbstractRoutingDataSource() {
          @Override
          protected Object determineCurrentLookupKey() {
            var tenant = ThreadLocalContextUtil.getTenant();
            if (tenant == null) throw new IllegalStateException("No tenant routing fallback");
            return tenant.getTenantIdentifier();
          }
        };
    routing.setTargetDataSources(Map.of("one", sources.get("one"), "two", sources.get("two")));
    routing.setLenientFallback(false);
    routing.afterPropertiesSet();
    jdbc = new JdbcTemplate(routing);
    security = mock(PlatformSecurityContext.class);
    actor = mock(AppUser.class);
    when(actor.getId()).thenReturn(1L);
    when(security.authenticatedUser()).thenReturn(actor);
    var target =
        new InvestmentProductService(
            security, jdbc, new InvestmentProductAccess(jdbc), new InvestmentProductValidation());
    var factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(routing), new AnnotationTransactionAttributeSource()));
    service = (InvestmentProductService) factory.getProxy();
    product = IDS.getAndAdd(10);
    other = product + 1;
    ordinary = product + 2;
    recurring = product + 3;
    for (var ds : sources.values()) {
      var j = new JdbcTemplate(ds);
      for (long id : new long[] {product, other, ordinary, recurring}) {
        j.update(
            "INSERT INTO m_savings_product(id,deposit_type_enum,name,currency_code,currency_digits,currency_multiplesof,accounting_type,is_dormancy_tracking_active,interest_posting_period_enum,interest_compounding_period_enum,withhold_tax,tax_group_id) VALUES (?,?,?,'MXN',2,1,2,false,4,1,false,null)",
            id,
            id == ordinary ? 100 : id == recurring ? 300 : 200,
            "Synthetic " + id);
        j.update(
            "INSERT INTO m_deposit_product_term_and_preclosure VALUES (?,?,1,1825,0,0,null,null,100,100000,1000,false,null,null)",
            id,
            id);
        for (var m : InvestmentProductFixtures.mappings()) {
          long mapping = id * 100 + m.mappingId();
          j.update("INSERT INTO acc_gl_account VALUES (?,false,?,1)", mapping, m.classification());
          j.update(
              "INSERT INTO acc_product_mapping VALUES (?,?,2,?,?,null,null)",
              mapping,
              id,
              m.financialAccountType(),
              mapping);
        }
      }
    }
    tenant("one");
  }

  static void tenant(String name) {
    var tenant = mock(FineractPlatformTenant.class);
    when(tenant.getTenantIdentifier()).thenReturn(name);
    ThreadLocalContextUtil.setTenant(tenant);
    ThreadLocalContextUtil.setBusinessDates(
        new java.util.HashMap<>(
            Map.of(
                org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType
                    .BUSINESS_DATE,
                java.time.LocalDate.of(2026, 11, 17))));
  }

  @AfterEach
  void clearTenant() {
    ThreadLocalContextUtil.reset();
  }

  @AfterAll
  static void cleanupDatabases() {
    if (admin != null && prefix != null)
      for (String name : new String[] {prefix + "_one", prefix + "_two"})
        new JdbcTemplate(admin).execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
    if (postgres != null) {
      postgres.stop();
      postgres = null;
    }
  }
}
