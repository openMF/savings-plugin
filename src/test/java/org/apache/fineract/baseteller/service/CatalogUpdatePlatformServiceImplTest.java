/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.baseteller.config.CatalogUpdateConfiguration.CatalogUpdateProperties;
import org.apache.fineract.baseteller.data.CatalogUpdateCategory;
import org.apache.fineract.baseteller.data.CatalogUpdateData;
import org.apache.fineract.baseteller.data.CatalogUpdateStatus;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class CatalogUpdatePlatformServiceImplTest {

  private static final Instant NOW = Instant.parse("2026-09-28T05:00:00Z");

  @Test
  void getAllReturnsTruthfulInitialStateInStableCategoryOrder() {
    final Fixture fixture = fixture(false);

    final List<CatalogUpdateData> statuses = fixture.service.retrieveAll();

    assertEquals(
        List.of(
            CatalogUpdateCategory.GENERAL,
            CatalogUpdateCategory.ACCOUNTING,
            CatalogUpdateCategory.USERS),
        statuses.stream().map(CatalogUpdateData::category).toList());
    assertTrue(statuses.stream().allMatch(CatalogUpdateData::needsUpdate));
    assertTrue(
        statuses.stream()
            .allMatch(status -> status.status() == CatalogUpdateStatus.UPDATE_REQUIRED));
  }

  @ParameterizedTest
  @EnumSource(CatalogUpdateCategory.class)
  void successfulRefreshUpdatesEveryCategory(final CatalogUpdateCategory category) {
    final Fixture fixture = fixture(true);

    final CatalogUpdateData result = fixture.service.synchronize(category);

    assertEquals(category, result.category());
    assertEquals(CatalogUpdateStatus.CURRENT, result.status());
    assertFalse(result.needsUpdate());
    assertNotNull(result.lastUpdatedAt());
    assertNotNull(result.lastAttemptAt());
    verify(fixture.validator).validate(category, 1L, ".", 7L);
  }

  @Test
  void failedValidationPreservesPreviousSuccessAndAllowsRetry() {
    final Fixture fixture = fixture(true);
    fixture.lastSuccess.set(NOW.minusSeconds(60));
    final Instant previous = fixture.lastSuccess.get();
    doThrow(new IllegalStateException("source unavailable"))
        .doNothing()
        .when(fixture.validator)
        .validate(CatalogUpdateCategory.GENERAL, 1L, ".", 7L);

    final CatalogUpdateData failed =
        fixture.service.synchronize(CatalogUpdateCategory.GENERAL);
    final CatalogUpdateData retried =
        fixture.service.synchronize(CatalogUpdateCategory.GENERAL);

    assertEquals(CatalogUpdateStatus.FAILED, failed.status());
    assertTrue(failed.needsUpdate());
    assertEquals(previous.atOffset(ZoneOffset.UTC), failed.lastUpdatedAt());
    assertEquals("SOURCE_VALIDATION_FAILED", failed.failureCode());
    assertEquals(CatalogUpdateStatus.CURRENT, retried.status());
    assertNull(retried.failureCode());
  }

  @Test
  void repeatedRefreshRevalidatesWithoutDuplicatingState() {
    final Fixture fixture = fixture(true);

    fixture.service.synchronize(CatalogUpdateCategory.USERS);
    fixture.service.synchronize(CatalogUpdateCategory.USERS);

    verify(fixture.validator, times(2))
        .validate(CatalogUpdateCategory.USERS, 1L, ".", 7L);
    verify(fixture.jdbc, times(2))
        .update(contains("SET last_successful_sync_at"), any(MapSqlParameterSource.class));
  }

  @SuppressWarnings("unchecked")
  private static Fixture fixture(final boolean persistedState) {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final CatalogCacheRefresher cacheRefresher = mock(CatalogCacheRefresher.class);
    final CatalogSourceValidator validator = mock(CatalogSourceValidator.class);
    final AppUser user = mock(AppUser.class);
    final Office office = mock(Office.class);
    final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    final AtomicReference<Instant> lastAttempt = new AtomicReference<>();
    final AtomicReference<CatalogUpdateStatus> status =
        new AtomicReference<>(CatalogUpdateStatus.UPDATE_REQUIRED);
    final AtomicReference<String> failureCode = new AtomicReference<>();
    final AtomicReference<String> attemptToken = new AtomicReference<>();

    when(context.authenticatedUser()).thenReturn(user);
    when(user.getId()).thenReturn(7L);
    when(user.getOffice()).thenReturn(office);
    when(office.getId()).thenReturn(1L);
    when(office.getHierarchy()).thenReturn(".");
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
    when(jdbc.queryForObject(
            contains("SELECT id FROM m_office"),
            any(MapSqlParameterSource.class),
            eq(Long.class)))
        .thenReturn(1L);
    when(jdbc.queryForObject(
            contains("SELECT COUNT(*) FROM m_base_teller_catalog_update"),
            any(MapSqlParameterSource.class),
            eq(Long.class)))
        .thenReturn(persistedState ? 1L : 0L);

    when(jdbc.queryForObject(
            contains("FROM m_base_teller_catalog_update"),
            any(MapSqlParameterSource.class),
            any(RowMapper.class)))
        .thenAnswer(
            invocation ->
                invocation
                    .getArgument(2, RowMapper.class)
                    .mapRow(
                        resultSet(
                            lastSuccess.get(),
                            lastAttempt.get(),
                            status.get(),
                            failureCode.get(),
                            attemptToken.get()),
                        0));
    when(jdbc.query(
            contains("FROM m_base_teller_catalog_update"),
            any(MapSqlParameterSource.class),
            any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              if (!persistedState && lastAttempt.get() == null) {
                return List.of();
              }
              return List.of(
                  invocation
                      .getArgument(2, RowMapper.class)
                      .mapRow(
                          resultSet(
                              lastSuccess.get(),
                              lastAttempt.get(),
                              status.get(),
                              failureCode.get(),
                              attemptToken.get()),
                          0));
            });
    when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
        .thenAnswer(
            invocation -> {
              final String sql = invocation.getArgument(0);
              final MapSqlParameterSource parameters = invocation.getArgument(1);
              if (sql.contains("status='UPDATING'")) {
                status.set(CatalogUpdateStatus.UPDATING);
                lastAttempt.set(NOW);
                attemptToken.set((String) parameters.getValue("token"));
                failureCode.set(null);
              } else if (sql.contains("last_successful_sync_at=:completedAt")) {
                status.set(CatalogUpdateStatus.CURRENT);
                lastSuccess.set(NOW);
                attemptToken.set(null);
                failureCode.set(null);
              } else if (sql.contains("status='FAILED'")) {
                status.set(CatalogUpdateStatus.FAILED);
                attemptToken.set(null);
                failureCode.set((String) parameters.getValue("failureCode"));
              }
              return 1;
            });

    final CatalogUpdatePlatformServiceImpl service =
        new CatalogUpdatePlatformServiceImpl(
            jdbc,
            context,
            cacheRefresher,
            validator,
            new CatalogFreshnessPolicy(),
            new CatalogUpdateProperties(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            transactionManager);
    return new Fixture(jdbc, validator, service, lastSuccess);
  }

  private static ResultSet resultSet(
      final Instant lastSuccess,
      final Instant lastAttempt,
      final CatalogUpdateStatus status,
      final String failureCode,
      final String attemptToken)
      throws Exception {
    final ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getTimestamp("last_successful_sync_at"))
        .thenReturn(lastSuccess == null ? null : Timestamp.from(lastSuccess));
    when(resultSet.getTimestamp("last_attempt_at"))
        .thenReturn(lastAttempt == null ? null : Timestamp.from(lastAttempt));
    when(resultSet.getString("status")).thenReturn(status.name());
    when(resultSet.getString("failure_code")).thenReturn(failureCode);
    when(resultSet.getString("current_attempt_token")).thenReturn(attemptToken);
    return resultSet;
  }

  private record Fixture(
      NamedParameterJdbcTemplate jdbc,
      CatalogSourceValidator validator,
      CatalogUpdatePlatformServiceImpl service,
      AtomicReference<Instant> lastSuccess) {}
}
