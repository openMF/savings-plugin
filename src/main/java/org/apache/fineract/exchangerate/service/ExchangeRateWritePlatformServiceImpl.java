/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.exchangerate.data.ExchangeRateCommand;
import org.apache.fineract.exchangerate.data.ExchangeRateData;
import org.apache.fineract.exchangerate.validation.ExchangeRateDataValidator;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** JDBC-backed write service for administrator-configured exchange rates. */
@Service
@RequiredArgsConstructor
@Transactional
public class ExchangeRateWritePlatformServiceImpl implements ExchangeRateWritePlatformService {

  private static final String RESOURCE_NAME_FOR_PERMISSIONS = "EXCHANGE_RATE";
  private static final String CREATE_EXCHANGE_RATE_PERMISSION =
      "hasAuthority('ALL_FUNCTIONS') or hasAuthority('CREATE_EXCHANGE_RATE')";
  private static final String UPDATE_EXCHANGE_RATE_PERMISSION =
      "hasAuthority('ALL_FUNCTIONS') or hasAuthority('UPDATE_EXCHANGE_RATE')";
  private static final String DELETE_EXCHANGE_RATE_PERMISSION =
      "hasAuthority('ALL_FUNCTIONS') or hasAuthority('DELETE_EXCHANGE_RATE')";

  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;
  private final PlatformSecurityContext context;
  private final ExchangeRateDataValidator validator;

  @Override
  @PreAuthorize(CREATE_EXCHANGE_RATE_PERMISSION)
  @Transactional(transactionManager = "jdbcTransactionManager", isolation = Isolation.SERIALIZABLE)
  public CommandProcessingResult createExchangeRate(final String jsonBody) {
    this.context.authenticatedUser().validateHasCreatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final ExchangeRateCommand command = this.validator.validateCreate(jsonBody);
    final boolean active = command.active() == null || command.active();

    validateDuplicate(
        null, command.sourceCurrencyCode(), command.targetCurrencyCode(), command.effectiveFrom());
    validateNoOverlap(
        null,
        command.sourceCurrencyCode(),
        command.targetCurrencyCode(),
        command.effectiveFrom(),
        command.effectiveTo(),
        active);

    final LocalDateTime now = LocalDateTime.now();
    final MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("sourceCurrencyCode", command.sourceCurrencyCode())
            .addValue("targetCurrencyCode", command.targetCurrencyCode())
            .addValue("exchangeRate", command.exchangeRate())
            .addValue("effectiveFrom", command.effectiveFrom())
            .addValue("effectiveTo", command.effectiveTo())
            .addValue("active", active)
            .addValue("createdDate", now)
            .addValue("lastModifiedDate", now);

    final KeyHolder keyHolder = new GeneratedKeyHolder();
    try {
      this.namedJdbcTemplate.update(
          "INSERT INTO m_exchange_rate"
              + " (source_currency_code, target_currency_code, exchange_rate, effective_from,"
              + " effective_to, active, created_date, last_modified_date)"
              + " VALUES (:sourceCurrencyCode, :targetCurrencyCode, :exchangeRate, :effectiveFrom,"
              + " :effectiveTo, :active, :createdDate, :lastModifiedDate)",
          params,
          keyHolder,
          new String[] {"id"});
    } catch (final DataIntegrityViolationException | ConcurrencyFailureException e) {
      throw duplicateOrInvalid(
          command.sourceCurrencyCode(), command.targetCurrencyCode(), command.effectiveFrom());
    }

    return new CommandProcessingResultBuilder()
        .withEntityId(keyHolder.getKey().longValue())
        .build();
  }

  @Override
  @PreAuthorize(UPDATE_EXCHANGE_RATE_PERMISSION)
  @Transactional(transactionManager = "jdbcTransactionManager", isolation = Isolation.SERIALIZABLE)
  public CommandProcessingResult updateExchangeRate(
      final Long exchangeRateId, final String jsonBody) {
    this.context.authenticatedUser().validateHasUpdatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    validateId(exchangeRateId);
    final ExchangeRateData existing = retrieveOneForUpdate(exchangeRateId);
    final ExchangeRateCommand command = this.validator.validateUpdate(jsonBody);

    final String sourceCurrencyCode =
        valueOrDefault(command.sourceCurrencyCode(), existing.getSourceCurrencyCode());
    final String targetCurrencyCode =
        valueOrDefault(command.targetCurrencyCode(), existing.getTargetCurrencyCode());
    final BigDecimal exchangeRate =
        valueOrDefault(command.exchangeRate(), existing.getExchangeRate());
    final LocalDate effectiveFrom =
        valueOrDefault(command.effectiveFrom(), existing.getEffectiveFrom());
    final LocalDate effectiveTo =
        command.effectiveToPresent() ? command.effectiveTo() : existing.getEffectiveTo();
    final boolean active = command.active() != null ? command.active() : existing.isActive();

    validateResolvedData(
        sourceCurrencyCode, targetCurrencyCode, exchangeRate, effectiveFrom, effectiveTo);
    this.validator.validateSupportedCurrency("sourceCurrency", sourceCurrencyCode);
    this.validator.validateSupportedCurrency("targetCurrency", targetCurrencyCode);
    validateDuplicate(exchangeRateId, sourceCurrencyCode, targetCurrencyCode, effectiveFrom);
    validateNoOverlap(
        exchangeRateId, sourceCurrencyCode, targetCurrencyCode, effectiveFrom, effectiveTo, active);

    final Map<String, Object> changes = new HashMap<>();
    addChange(changes, "sourceCurrencyCode", existing.getSourceCurrencyCode(), sourceCurrencyCode);
    addChange(changes, "targetCurrencyCode", existing.getTargetCurrencyCode(), targetCurrencyCode);
    addChange(changes, "exchangeRate", existing.getExchangeRate(), exchangeRate);
    addChange(changes, "effectiveFrom", existing.getEffectiveFrom(), effectiveFrom);
    addChange(changes, "effectiveTo", existing.getEffectiveTo(), effectiveTo);
    addChange(changes, "active", existing.isActive(), active);

    if (!changes.isEmpty()) {
      final MapSqlParameterSource params =
          new MapSqlParameterSource()
              .addValue("id", exchangeRateId)
              .addValue("sourceCurrencyCode", sourceCurrencyCode)
              .addValue("targetCurrencyCode", targetCurrencyCode)
              .addValue("exchangeRate", exchangeRate)
              .addValue("effectiveFrom", effectiveFrom)
              .addValue("effectiveTo", effectiveTo)
              .addValue("active", active)
              .addValue("lastModifiedDate", LocalDateTime.now());
      try {
        final int affectedRows =
            this.namedJdbcTemplate.update(
                "UPDATE m_exchange_rate SET source_currency_code = :sourceCurrencyCode,"
                    + " target_currency_code = :targetCurrencyCode, exchange_rate = :exchangeRate,"
                    + " effective_from = :effectiveFrom, effective_to = :effectiveTo,"
                    + " active = :active, last_modified_date = :lastModifiedDate WHERE id = :id",
                params);
        if (affectedRows == 0) {
          throw notFound(exchangeRateId);
        }
      } catch (final DataIntegrityViolationException | ConcurrencyFailureException e) {
        throw duplicateOrInvalid(sourceCurrencyCode, targetCurrencyCode, effectiveFrom);
      }
    }

    return new CommandProcessingResultBuilder().withEntityId(exchangeRateId).with(changes).build();
  }

  @Override
  @PreAuthorize(DELETE_EXCHANGE_RATE_PERMISSION)
  public CommandProcessingResult deleteExchangeRate(final Long exchangeRateId) {
    this.context.authenticatedUser().validateHasDeletePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    validateId(exchangeRateId);
    final int affectedRows =
        this.jdbcTemplate.update("DELETE FROM m_exchange_rate WHERE id = ?", exchangeRateId);
    if (affectedRows == 0) {
      throw notFound(exchangeRateId);
    }
    return new CommandProcessingResultBuilder().withEntityId(exchangeRateId).build();
  }

  private void validateDuplicate(
      final Long excludedId,
      final String sourceCurrencyCode,
      final String targetCurrencyCode,
      final LocalDate effectiveFrom) {
    final long count;
    if (excludedId == null) {
      count =
          this.jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM m_exchange_rate WHERE source_currency_code = ?"
                  + " AND target_currency_code = ? AND effective_from = ?",
              Long.class,
              sourceCurrencyCode,
              targetCurrencyCode,
              effectiveFrom);
    } else {
      count =
          this.jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM m_exchange_rate WHERE source_currency_code = ?"
                  + " AND target_currency_code = ? AND effective_from = ? AND id <> ?",
              Long.class,
              sourceCurrencyCode,
              targetCurrencyCode,
              effectiveFrom,
              excludedId);
    }
    if (count > 0) {
      throw duplicateOrInvalid(sourceCurrencyCode, targetCurrencyCode, effectiveFrom);
    }
  }

  private ExchangeRateData retrieveOneForUpdate(final Long exchangeRateId) {
    try {
      return this.jdbcTemplate.queryForObject(
          "SELECT id, source_currency_code, target_currency_code, exchange_rate,"
              + " effective_from, effective_to, active, created_date, last_modified_date"
              + " FROM m_exchange_rate WHERE id = ?",
          this::mapExchangeRateData,
          exchangeRateId);
    } catch (final EmptyResultDataAccessException e) {
      throw notFound(exchangeRateId);
    }
  }

  private ExchangeRateData mapExchangeRateData(final ResultSet rs, final int rowNum)
      throws SQLException {
    return ExchangeRateData.instance(
        rs.getLong("id"),
        rs.getString("source_currency_code"),
        rs.getString("target_currency_code"),
        rs.getBigDecimal("exchange_rate"),
        rs.getDate("effective_from").toLocalDate(),
        rs.getDate("effective_to") != null ? rs.getDate("effective_to").toLocalDate() : null,
        rs.getBoolean("active"),
        rs.getTimestamp("created_date") != null
            ? rs.getTimestamp("created_date").toLocalDateTime()
            : null,
        rs.getTimestamp("last_modified_date") != null
            ? rs.getTimestamp("last_modified_date").toLocalDateTime()
            : null);
  }

  private void validateResolvedData(
      final String sourceCurrencyCode,
      final String targetCurrencyCode,
      final BigDecimal exchangeRate,
      final LocalDate effectiveFrom,
      final LocalDate effectiveTo) {
    if (sourceCurrencyCode.equals(targetCurrencyCode)) {
      throwValidation(
          "targetCurrency",
          "validation.msg.exchangeRate.same.currency",
          "Source and target currencies must differ.",
          targetCurrencyCode);
    }
    if (exchangeRate.compareTo(BigDecimal.ZERO) <= 0) {
      throwValidation(
          "exchangeRate",
          "validation.msg.exchangeRate.exchangeRate.must.be.positive",
          "Exchange rate must be greater than zero.",
          exchangeRate);
    }
    if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
      throwValidation(
          "effectiveTo",
          "validation.msg.exchangeRate.effectiveTo.before.effectiveFrom",
          "Effective to date must be on or after effective from date.",
          effectiveTo);
    }
  }

  private void validateId(final Long exchangeRateId) {
    if (exchangeRateId == null || exchangeRateId <= 0) {
      throwValidation(
          "exchangeRateId",
          "validation.msg.exchangeRate.id.invalid",
          "Exchange rate id must be greater than zero.",
          exchangeRateId);
    }
  }

  private void validateNoOverlap(
      final Long excludedId,
      final String sourceCurrencyCode,
      final String targetCurrencyCode,
      final LocalDate effectiveFrom,
      final LocalDate effectiveTo,
      final boolean active) {
    if (!active) {
      return;
    }

    final StringBuilder sql =
        new StringBuilder(
            "SELECT COUNT(*) FROM m_exchange_rate WHERE source_currency_code = :sourceCurrencyCode"
                + " AND target_currency_code = :targetCurrencyCode AND active = true"
                + " AND (effective_to IS NULL OR effective_to >= :effectiveFrom)");
    final MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("sourceCurrencyCode", sourceCurrencyCode)
            .addValue("targetCurrencyCode", targetCurrencyCode)
            .addValue("effectiveFrom", effectiveFrom);
    if (effectiveTo != null) {
      sql.append(" AND effective_from <= :effectiveTo");
      params.addValue("effectiveTo", effectiveTo);
    }
    if (excludedId != null) {
      sql.append(" AND id <> :excludedId");
      params.addValue("excludedId", excludedId);
    }

    final Long overlappingCount =
        this.namedJdbcTemplate.queryForObject(sql.toString(), params, Long.class);
    if (overlappingCount != null && overlappingCount > 0) {
      throw new PlatformDataIntegrityException(
          "error.msg.exchangeRate.active.rate.overlap",
          "An active exchange rate already exists for "
              + sourceCurrencyCode
              + " to "
              + targetCurrencyCode
              + " in the effective date range.",
          "effectiveFrom",
          effectiveFrom);
    }
  }

  private <T> T valueOrDefault(final T value, final T defaultValue) {
    return value != null ? value : defaultValue;
  }

  private void addChange(
      final Map<String, Object> changes,
      final String key,
      final Object oldValue,
      final Object newValue) {
    if (oldValue == null ? newValue != null : !oldValue.equals(newValue)) {
      changes.put(key, newValue);
    }
  }

  private String valueOrDefault(final String value, final String defaultValue) {
    return value != null ? value.trim().toUpperCase(Locale.ROOT) : defaultValue;
  }

  private PlatformDataIntegrityException duplicateOrInvalid(
      final String sourceCurrencyCode,
      final String targetCurrencyCode,
      final LocalDate effectiveFrom) {
    return new PlatformDataIntegrityException(
        "error.msg.exchangeRate.duplicate.or.invalid",
        "Duplicate or invalid exchange rate for "
            + sourceCurrencyCode
            + " to "
            + targetCurrencyCode
            + " from "
            + effectiveFrom
            + ".",
        "effectiveFrom",
        effectiveFrom);
  }

  private void throwValidation(
      final String parameterName,
      final String code,
      final String defaultMessage,
      final Object value) {
    throw new PlatformApiDataValidationException(
        List.of(ApiParameterError.parameterError(code, defaultMessage, parameterName, value)));
  }

  private PlatformDataIntegrityException notFound(final Long exchangeRateId) {
    return new PlatformDataIntegrityException(
        "error.msg.exchangeRate.not.found",
        "Exchange rate with id " + exchangeRateId + " was not found.",
        "exchangeRateId",
        exchangeRateId);
  }
}
