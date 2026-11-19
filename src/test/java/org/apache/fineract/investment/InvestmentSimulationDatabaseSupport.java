/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** PostgreSQL tenant routing, real immutable migrations and transaction boundaries. */
abstract class InvestmentSimulationDatabaseSupport extends InvestmentRateDatabaseSupport {
  static final String MIGRATION =
      "db/changelog/tenant/module/savings/parts/096-investment-simulation.xml";
  static final List<String> FINANCIAL_TABLES =
      List.of(
          "m_savings_account",
          "m_savings_account_transaction",
          "m_account_transfer_details",
          "m_account_transfer_transaction",
          "acc_gl_journal_entry",
          "m_savings_account_transaction_tax_details",
          "m_savings_account_on_hold_transaction",
          "m_deposit_account_term_and_preclosure");
  InvestmentSimulationService simulations;
  InvestmentSimulationAuditService evidence;

  @BeforeAll
  static void simulationSchema() throws Exception {
    for (var source : sources.values()) {
      var jdbc = new JdbcTemplate(source);
      jdbc.execute(
          "CREATE TABLE c_configuration(name varchar(100) PRIMARY KEY,enabled boolean NOT NULL,value bigint)");
      jdbc.execute(
          "CREATE TABLE m_role_permission(role_id bigint,permission_id bigint REFERENCES m_permission(id))");
      jdbc.update(
          "INSERT INTO c_configuration VALUES ('financial-year-beginning-month',true,1),('savings-interest-posting-current-period-end',false,null)");
      jdbc.execute(
          "CREATE FUNCTION reject_financial_write() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'Financial writes forbidden in simulation tests'; END; $$ LANGUAGE plpgsql");
      for (var name : FINANCIAL_TABLES) {
        jdbc.execute("CREATE TABLE " + name + "(id bigint PRIMARY KEY,balance numeric(19,6))");
        jdbc.update("INSERT INTO " + name + " VALUES (1,1234.56)");
        jdbc.execute(
            "CREATE TRIGGER reject_write BEFORE INSERT OR UPDATE OR DELETE ON "
                + name
                + " FOR EACH ROW EXECUTE FUNCTION reject_financial_write()");
      }
      for (int repeat = 0; repeat < 2; repeat++)
        try (var connection = source.getConnection()) {
          var db =
              DatabaseFactory.getInstance()
                  .findCorrectDatabaseImplementation(new JdbcConnection(connection));
          try (var lb = new Liquibase(MIGRATION, new ClassLoaderResourceAccessor(), db)) {
            lb.update(new Contexts(), new LabelExpression());
          }
        }
    }
  }

  @BeforeEach
  void simulationServices() {
    for (var tenant : List.of("one", "two")) {
      rateTenant(tenant, "UTC");
      MoneyHelper.initializeTenantRoundingMode(tenant, RoundingMode.HALF_EVEN.ordinal());
      jdbc.update(
          "UPDATE m_savings_product SET interest_compounding_period_enum=7,interest_posting_period_enum=7 WHERE id IN (?,?)",
          product,
          other);
    }
    rateTenant("one", "UTC");
    evidence = transactional(new InvestmentSimulationAuditService(jdbc, security));
    simulations =
        transactional(
            new InvestmentSimulationService(
                security,
                new InvestmentSimulationAccess(jdbc, new InvestmentProductAccess(jdbc)),
                rates,
                new InvestmentRateTime(clock),
                new InvestmentSimulationValidation(),
                new InvestmentSimulationCalculator(),
                evidence,
                new DataSourceTransactionManager(jdbc.getDataSource())));
  }

  <T> T transactional(T target) {
    return transactional(target, jdbc.getDataSource());
  }

  <T> T transactional(T target, javax.sql.DataSource source) {
    var factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    var manager = new DataSourceTransactionManager(source);
    var advice = new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource());
    advice.setBeanFactory(
        new org.springframework.beans.factory.support.StaticListableBeanFactory(
            Map.of("jdbcTransactionManager", manager)));
    factory.addAdvice(advice);
    return (T) factory.getProxy();
  }

  void publish(String rate) {
    rates.save(product, InvestmentRateFixtures.request(0, null, rate));
    approve(1);
  }

  Map<String, Object> financialSnapshot() {
    var snapshot = new LinkedHashMap<String, Object>();
    for (var name : FINANCIAL_TABLES)
      snapshot.put(
          name,
          jdbc.queryForObject(
              "SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::text FROM " + name + " t", String.class));
    return snapshot;
  }

  String renewals(int count, boolean capitalize) {
    return InvestmentSimulationFixtures.edit(
        product,
        null,
        r -> {
          r.addProperty("automaticReinvestment", true);
          r.addProperty("requestedReinvestmentCount", count);
          r.addProperty("capitalizationEnabled", capitalize);
          if (capitalize) r.addProperty("interestPayoutMode", "CAPITALIZE_ON_RENEWAL");
        });
  }

  void renewalPolicy(boolean capitalize) {
    service.save(
        product,
        InvestmentProductFixtures.request(
            1,
            Map.of(
                "automaticReinvestmentEnabled",
                true,
                "maximumReinvestments",
                3,
                "renewalRatePolicy",
                "RATE_EFFECTIVE_AT_RENEWAL",
                "capitalizationEligible",
                capitalize,
                "capitalizationEnabled",
                capitalize,
                "interestPayoutMode",
                capitalize ? "CAPITALIZE_ON_RENEWAL" : "AT_MATURITY")));
  }
}
