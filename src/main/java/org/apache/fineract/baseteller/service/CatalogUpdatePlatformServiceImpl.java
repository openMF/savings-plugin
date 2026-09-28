/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.apache.fineract.baseteller.config.CatalogUpdateConfiguration.CatalogUpdateProperties;
import org.apache.fineract.baseteller.data.CatalogUpdateCategory;
import org.apache.fineract.baseteller.data.CatalogUpdateData;
import org.apache.fineract.baseteller.data.CatalogUpdateStatus;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CatalogUpdatePlatformServiceImpl implements CatalogUpdatePlatformService {

  static final String SOURCE_VALIDATION_FAILED = "SOURCE_VALIDATION_FAILED";
  static final String REFRESH_ALREADY_RUNNING = "REFRESH_ALREADY_RUNNING";
  static final String INTERRUPTED_REFRESH = "INTERRUPTED_REFRESH";

  private static final Logger LOG =
      LoggerFactory.getLogger(CatalogUpdatePlatformServiceImpl.class);
  private static final String STATUS_SQL =
      "SELECT last_successful_sync_at,last_attempt_at,status,failure_code,current_attempt_token"
          + " FROM m_base_teller_catalog_update"
          + " WHERE office_id=:officeId AND category=:category";

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final PlatformSecurityContext context;
  private final CatalogCacheRefresher cacheRefresher;
  private final CatalogSourceValidator sourceValidator;
  private final CatalogFreshnessPolicy freshnessPolicy;
  private final CatalogUpdateProperties properties;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public CatalogUpdatePlatformServiceImpl(
      final NamedParameterJdbcTemplate jdbcTemplate,
      final PlatformSecurityContext context,
      final CatalogCacheRefresher cacheRefresher,
      final CatalogSourceValidator sourceValidator,
      final CatalogFreshnessPolicy freshnessPolicy,
      final CatalogUpdateProperties properties,
      final Clock clock,
      @Qualifier("jdbcTransactionManager")
          final PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.context = context;
    this.cacheRefresher = cacheRefresher;
    this.sourceValidator = sourceValidator;
    this.freshnessPolicy = freshnessPolicy;
    this.properties = properties;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @Override
  public List<CatalogUpdateData> retrieveAll() {
    final AppUser user = context.authenticatedUser();
    final Long officeId = user.getOffice().getId();
    final Instant now = clock.instant();
    return Arrays.stream(CatalogUpdateCategory.values())
        .map(category -> retrieve(officeId, category, now))
        .toList();
  }

  @Override
  public CatalogUpdateData retrieve(final CatalogUpdateCategory category) {
    final AppUser user = context.authenticatedUser();
    return retrieve(user.getOffice().getId(), category, clock.instant());
  }

  @Override
  public CatalogUpdateData synchronize(final CatalogUpdateCategory category) {
    final AppUser user = context.authenticatedUser();
    final Office office = user.getOffice();
    final Instant attemptedAt = clock.instant();
    final Claim claim =
        transactionTemplate.execute(
            ignored -> claim(office.getId(), user.getId(), category, attemptedAt));
    if (claim == null || !claim.accepted()) {
      return retrieve(office.getId(), category, attemptedAt);
    }

    try {
      // These are live authoritative reads. No catalog snapshot is downloaded or persisted.
      cacheRefresher.refresh(category, office.getId(), office.getHierarchy());
      sourceValidator.validate(category, office.getId(), office.getHierarchy(), user.getId());
      final Instant completedAt = clock.instant();
      transactionTemplate.executeWithoutResult(
          ignored -> completeSuccess(office.getId(), category, claim.token(), completedAt));
      return retrieve(office.getId(), category, completedAt);
    } catch (final RuntimeException exception) {
      final Instant completedAt = clock.instant();
      transactionTemplate.executeWithoutResult(
          ignored ->
              completeFailure(
                  office.getId(), category, claim.token(), completedAt, SOURCE_VALIDATION_FAILED));
      LOG.warn(
          "Base Teller catalog refresh failed for category {} and office {}; cause type {}",
          category,
          office.getId(),
          exception.getClass().getSimpleName());
      return retrieve(office.getId(), category, completedAt);
    }
  }

  private Claim claim(
      final Long officeId,
      final Long userId,
      final CatalogUpdateCategory category,
      final Instant attemptedAt) {
    final MapSqlParameterSource parameters = parameters(officeId, category);
    parameters.addValue("attemptedAt", timestamp(attemptedAt));
    parameters.addValue("userId", userId);

    // The office row is the cross-instance creation lock for office/category state rows.
    jdbcTemplate.queryForObject(
        "SELECT id FROM m_office WHERE id=:officeId FOR UPDATE", parameters, Long.class);
    final Long existing =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_base_teller_catalog_update"
                + " WHERE office_id=:officeId AND category=:category",
            parameters,
            Long.class);
    if (existing == null || existing == 0) {
      jdbcTemplate.update(
          "INSERT INTO m_base_teller_catalog_update"
              + " (office_id,category,status,created_on_utc,updated_on_utc)"
              + " VALUES (:officeId,:category,'UPDATE_REQUIRED',:attemptedAt,:attemptedAt)",
          parameters);
    }

    final StoredState current = storedState(officeId, category);
    final boolean activeRefresh =
        current.status() == CatalogUpdateStatus.UPDATING
            && current.lastAttemptAt() != null
            && attemptedAt.isBefore(
                current.lastAttemptAt().plus(properties.getUpdatingTimeout()));
    final String token = UUID.randomUUID().toString();
    parameters.addValue("token", token);
    parameters.addValue("previousSuccessfulAt", timestamp(current.lastSuccessfulAt()));
    if (activeRefresh) {
      parameters.addValue("completedAt", timestamp(attemptedAt));
      parameters.addValue("failureCode", REFRESH_ALREADY_RUNNING);
      insertAudit(parameters, "REJECTED", true);
      return new Claim(false, token);
    }

    final String interruptedCode =
        current.status() == CatalogUpdateStatus.UPDATING ? INTERRUPTED_REFRESH : null;
    if (interruptedCode != null) {
      parameters.addValue("oldToken", current.currentAttemptToken());
      jdbcTemplate.update(
          "UPDATE m_base_teller_catalog_update_audit"
              + " SET completed_on_utc=:attemptedAt,outcome='FAILED',failure_code=:interruptedCode"
              + " WHERE attempt_token=:oldToken AND completed_on_utc IS NULL",
          parameters.addValue("interruptedCode", interruptedCode));
    }
    jdbcTemplate.update(
        "UPDATE m_base_teller_catalog_update"
            + " SET last_attempt_at=:attemptedAt,status='UPDATING',performed_by=:userId,"
            + " failure_code=NULL,current_attempt_token=:token,updated_on_utc=:attemptedAt"
            + " WHERE office_id=:officeId AND category=:category",
        parameters);
    insertAudit(parameters, "UPDATING", false);
    return new Claim(true, token);
  }

  private void insertAudit(
      final MapSqlParameterSource parameters, final String outcome, final boolean completed) {
    parameters.addValue("outcome", outcome);
    jdbcTemplate.update(
        "INSERT INTO m_base_teller_catalog_update_audit"
            + " (attempt_token,office_id,category,performed_by,attempted_on_utc,completed_on_utc,"
            + " outcome,previous_successful_sync_at,failure_code)"
            + " VALUES (:token,:officeId,:category,:userId,:attemptedAt,"
            + (completed ? ":completedAt" : "NULL")
            + ",:outcome,:previousSuccessfulAt,"
            + (completed ? ":failureCode" : "NULL")
            + ")",
        parameters);
  }

  private void completeSuccess(
      final Long officeId,
      final CatalogUpdateCategory category,
      final String token,
      final Instant completedAt) {
    final MapSqlParameterSource parameters = parameters(officeId, category);
    parameters.addValue("token", token);
    parameters.addValue("completedAt", timestamp(completedAt));
    jdbcTemplate.update(
        "UPDATE m_base_teller_catalog_update SET last_successful_sync_at=:completedAt,"
            + " status='CURRENT',failure_code=NULL,current_attempt_token=NULL,"
            + " updated_on_utc=:completedAt"
            + " WHERE office_id=:officeId AND category=:category AND current_attempt_token=:token",
        parameters);
    jdbcTemplate.update(
        "UPDATE m_base_teller_catalog_update_audit"
            + " SET completed_on_utc=:completedAt,outcome='SUCCESS'"
            + " WHERE attempt_token=:token",
        parameters);
  }

  private void completeFailure(
      final Long officeId,
      final CatalogUpdateCategory category,
      final String token,
      final Instant completedAt,
      final String failureCode) {
    final MapSqlParameterSource parameters = parameters(officeId, category);
    parameters.addValue("token", token);
    parameters.addValue("completedAt", timestamp(completedAt));
    parameters.addValue("failureCode", failureCode);
    jdbcTemplate.update(
        "UPDATE m_base_teller_catalog_update SET status='FAILED',failure_code=:failureCode,"
            + " current_attempt_token=NULL,updated_on_utc=:completedAt"
            + " WHERE office_id=:officeId AND category=:category AND current_attempt_token=:token",
        parameters);
    jdbcTemplate.update(
        "UPDATE m_base_teller_catalog_update_audit"
            + " SET completed_on_utc=:completedAt,outcome='FAILED',failure_code=:failureCode"
            + " WHERE attempt_token=:token",
        parameters);
  }

  private CatalogUpdateData retrieve(
      final Long officeId, final CatalogUpdateCategory category, final Instant now) {
    final List<StoredState> states =
        jdbcTemplate.query(
            STATUS_SQL,
            parameters(officeId, category),
            (resultSet, rowNumber) -> mapState(resultSet));
    if (states.isEmpty()) {
      return new CatalogUpdateData(
          category, null, null, true, CatalogUpdateStatus.UPDATE_REQUIRED, null);
    }
    final StoredState stored = states.getFirst();
    final Duration freshness = properties.freshness(category);
    final boolean stale =
        freshnessPolicy.needsUpdate(stored.lastSuccessfulAt(), now, freshness);
    final boolean interrupted =
        stored.status() == CatalogUpdateStatus.UPDATING
            && stored.lastAttemptAt() != null
            && !now.isBefore(stored.lastAttemptAt().plus(properties.getUpdatingTimeout()));
    final CatalogUpdateStatus effectiveStatus;
    final String failureCode;
    final boolean needsUpdate;
    if (interrupted) {
      effectiveStatus = CatalogUpdateStatus.FAILED;
      failureCode = INTERRUPTED_REFRESH;
      needsUpdate = true;
    } else if (stored.status() == CatalogUpdateStatus.UPDATING) {
      effectiveStatus = CatalogUpdateStatus.UPDATING;
      failureCode = null;
      needsUpdate = false;
    } else if (stored.status() == CatalogUpdateStatus.FAILED) {
      effectiveStatus = CatalogUpdateStatus.FAILED;
      failureCode = stored.failureCode();
      needsUpdate = true;
    } else {
      effectiveStatus = stale ? CatalogUpdateStatus.UPDATE_REQUIRED : CatalogUpdateStatus.CURRENT;
      failureCode = null;
      needsUpdate = stale;
    }
    return new CatalogUpdateData(
        category,
        offset(stored.lastSuccessfulAt()),
        offset(stored.lastAttemptAt()),
        needsUpdate,
        effectiveStatus,
        failureCode);
  }

  private StoredState storedState(
      final Long officeId, final CatalogUpdateCategory category) {
    return jdbcTemplate.queryForObject(
        STATUS_SQL + " FOR UPDATE",
        parameters(officeId, category),
        (resultSet, rowNumber) -> mapState(resultSet));
  }

  private static StoredState mapState(final ResultSet resultSet) throws SQLException {
    return new StoredState(
        instant(resultSet.getTimestamp("last_successful_sync_at")),
        instant(resultSet.getTimestamp("last_attempt_at")),
        CatalogUpdateStatus.valueOf(resultSet.getString("status")),
        resultSet.getString("failure_code"),
        resultSet.getString("current_attempt_token"));
  }

  private static MapSqlParameterSource parameters(
      final Long officeId, final CatalogUpdateCategory category) {
    return new MapSqlParameterSource()
        .addValue("officeId", officeId)
        .addValue("category", category.name());
  }

  private static Timestamp timestamp(final Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(final Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static OffsetDateTime offset(final Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  private record StoredState(
      Instant lastSuccessfulAt,
      Instant lastAttemptAt,
      CatalogUpdateStatus status,
      String failureCode,
      String currentAttemptToken) {}

  private record Claim(boolean accepted, String token) {}
}
