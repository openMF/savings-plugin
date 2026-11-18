/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Same tenant-routing, native configuration fixtures and transaction boundaries as PR 01 tests. */
abstract class InvestmentRateDatabaseSupport extends InvestmentProductDatabaseSupport {
  InvestmentRateService rates;
  MutableClock clock = new MutableClock();
  final ThreadLocal<Long> rateActor = ThreadLocal.withInitial(() -> 1L);

  @BeforeAll
  static void migrateRates() throws Exception {
    for (var source : sources.values())
      for (int i = 0; i < 2; i++)
        try (var connection = source.getConnection()) {
          var db =
              DatabaseFactory.getInstance()
                  .findCorrectDatabaseImplementation(new JdbcConnection(connection));
          try (var lb =
              new Liquibase(
                  "db/changelog/tenant/module/savings/parts/095-investment-rate-matrix.xml",
                  new ClassLoaderResourceAccessor(),
                  db)) {
            lb.update(new Contexts(), new LabelExpression());
          }
        }
  }

  @BeforeEach
  void rateServices() {
    rateActor.set(1L);
    when(actor.getId()).thenAnswer(i -> rateActor.get());
    for (String name : new String[] {"one", "two"}) {
      rateTenant(name, "UTC");
      service.save(product, InvestmentProductFixtures.JSON);
      service.save(other, InvestmentProductFixtures.JSON);
    }
    rateTenant("one", "UTC");
    rates = reload();
  }

  InvestmentRateService reload() {
    var target =
        new InvestmentRateService(
            security,
            jdbc,
            new InvestmentProductAccess(jdbc),
            new InvestmentProductValidation(),
            new InvestmentRateValidation(),
            new InvestmentRateTime(clock));
    var factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    var manager = new DataSourceTransactionManager(jdbc.getDataSource());
    var advice = new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource());
    advice.setBeanFactory(
        new org.springframework.beans.factory.support.StaticListableBeanFactory(
            java.util.Map.of("jdbcTransactionManager", manager)));
    factory.addAdvice(advice);
    return (InvestmentRateService) factory.getProxy();
  }

  static void rateTenant(String name, String zone) {
    tenant(name);
    when(ThreadLocalContextUtil.getTenant().getTimezoneId()).thenReturn(zone);
  }

  InvestmentRateData.Configuration approve(long revision) {
    rateActor.set(2L);
    try {
      return rates.publish(product, "{\"expectedRevision\":" + revision + "}");
    } finally {
      rateActor.set(1L);
    }
  }

  static final class MutableClock extends Clock {
    final AtomicReference<Instant> instant =
        new AtomicReference<>(Instant.parse("2026-11-17T12:00:00Z"));

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
      return instant.get();
    }

    void at(String value) {
      instant.set(Instant.parse(value));
    }
  }
}
