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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.exchangerate.config.ExchangeRateProviderProperties;
import org.apache.fineract.exchangerate.data.ExchangeRateSynchronizationResult;
import org.apache.fineract.exchangerate.provider.ExchangeRateProvider;
import org.apache.fineract.exchangerate.provider.ExchangeRateProviderResult;
import org.apache.fineract.exchangerate.validation.ExchangeRateDataValidator;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Synchronizes provider-backed exchange rates into the existing dynamic exchange-rate table. */
@Service
@RequiredArgsConstructor
public class ExchangeRateSynchronizationServiceImpl implements ExchangeRateSynchronizationService {

  private static final String SYNC_EXCHANGE_RATE_PERMISSION =
      "hasAuthority('ALL_FUNCTIONS') or hasAuthority('SYNC_EXCHANGE_RATE')";

  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;
  private final ExchangeRateProviderProperties properties;
  private final List<ExchangeRateProvider> providers;
  private final ExchangeRateDataValidator validator;

  /** Validates and synchronizes provider exchange rates for an API-triggered request. */
  @Override
  @PreAuthorize(SYNC_EXCHANGE_RATE_PERMISSION)
  @Transactional(transactionManager = "jdbcTransactionManager", isolation = Isolation.SERIALIZABLE)
  public ExchangeRateSynchronizationResult synchronize(final String jsonBody) {
    final String baseCurrencyCode =
        this.validator.validateSynchronization(jsonBody, this.properties.getBaseCurrency());
    return synchronizeBaseCurrency(baseCurrencyCode);
  }

  @Override
  @Transactional(transactionManager = "jdbcTransactionManager", isolation = Isolation.SERIALIZABLE)
  public ExchangeRateSynchronizationResult synchronizeBaseCurrency(final String baseCurrencyCode) {
    if (!this.properties.isProviderEnabled()) {
      throw validationError(
          "validation.msg.exchangeRate.provider.disabled",
          "Exchange-rate provider synchronization is disabled.");
    }

    final String normalizedBaseCurrency = normalizeCurrencyCode(baseCurrencyCode);
    this.validator.validateSupportedCurrency("baseCurrency", normalizedBaseCurrency);

    final ExchangeRateProvider provider = resolveProvider();
    final ExchangeRateProviderResult providerResult =
        provider.fetchLatestRates(normalizedBaseCurrency);
    validateProviderResult(providerResult, normalizedBaseCurrency);

    int importedCount = 0;
    int skippedCount = 0;
    final LocalDateTime now = LocalDateTime.now();
    for (final var entry : providerResult.getRates().entrySet()) {
      final String targetCurrencyCode = normalizeCurrencyCode(entry.getKey());
      final BigDecimal exchangeRate = entry.getValue();
      if (normalizedBaseCurrency.equals(targetCurrencyCode)
          || exchangeRate == null
          || exchangeRate.compareTo(BigDecimal.ZERO) <= 0
          || !isSupportedCurrency(targetCurrencyCode)) {
        skippedCount++;
        continue;
      }

      if (saveProviderRateIfChanged(
          providerResult.getProvider(),
          normalizedBaseCurrency,
          targetCurrencyCode,
          exchangeRate,
          providerResult.getRateDate(),
          now)) {
        importedCount++;
      } else {
        skippedCount++;
      }
    }

    return ExchangeRateSynchronizationResult.instance(
        importedCount,
        skippedCount,
        now,
        providerResult.getProvider(),
        normalizedBaseCurrency,
        providerResult.getRateDate());
  }

  private ExchangeRateProvider resolveProvider() {
    final String providerName = this.properties.getProvider();
    return this.providers.stream()
        .filter(provider -> provider.providerName().equalsIgnoreCase(providerName))
        .findFirst()
        .orElseThrow(
            () ->
                validationError(
                    "validation.msg.exchangeRate.provider.unsupported",
                    "Exchange-rate provider " + providerName + " is not supported."));
  }

  private void validateProviderResult(
      final ExchangeRateProviderResult providerResult, final String baseCurrencyCode) {
    if (providerResult == null
        || providerResult.getProvider() == null
        || providerResult.getProvider().isBlank()
        || providerResult.getBaseCurrencyCode() == null
        || !baseCurrencyCode.equals(normalizeCurrencyCode(providerResult.getBaseCurrencyCode()))
        || providerResult.getRateDate() == null
        || providerResult.getRates() == null
        || providerResult.getRates().isEmpty()) {
      throw validationError(
          "validation.msg.exchangeRate.provider.response.invalid",
          "Exchange-rate provider returned a malformed response.");
    }
    providerResult
        .getRates()
        .forEach(
            (currencyCode, exchangeRate) -> {
              if (currencyCode == null
                  || currencyCode.isBlank()
                  || exchangeRate == null
                  || exchangeRate.compareTo(BigDecimal.ZERO) <= 0) {
                throw validationError(
                    "validation.msg.exchangeRate.provider.response.invalid",
                    "Exchange-rate provider returned a malformed response.");
              }
            });
  }

  private boolean saveProviderRateIfChanged(
      final String provider,
      final String sourceCurrencyCode,
      final String targetCurrencyCode,
      final BigDecimal exchangeRate,
      final LocalDate effectiveFrom,
      final LocalDateTime now) {
    final Optional<ProviderExchangeRate> existingProviderRate =
        findProviderRate(provider, sourceCurrencyCode, targetCurrencyCode, effectiveFrom);
    if (existingProviderRate.isPresent()) {
      if (existingProviderRate.get().exchangeRate().compareTo(exchangeRate) == 0) {
        return false;
      }
      return updateProviderRate(existingProviderRate.get().id(), exchangeRate, now);
    }

    if (existsManualForEffectiveDate(sourceCurrencyCode, targetCurrencyCode, effectiveFrom)
        || hasManualActiveRateOn(sourceCurrencyCode, targetCurrencyCode, effectiveFrom)
        || hasFutureActiveRate(sourceCurrencyCode, targetCurrencyCode, effectiveFrom)) {
      return false;
    }

    final Optional<ActiveExchangeRate> activeRate =
        findActiveProviderRateOn(sourceCurrencyCode, targetCurrencyCode, effectiveFrom);
    if (activeRate.isPresent()
        && activeRate.get().exchangeRate().compareTo(exchangeRate) == 0
        && activeRate.get().effectiveTo() == null) {
      return false;
    }

    if (!insertProviderRate(
        provider, sourceCurrencyCode, targetCurrencyCode, exchangeRate, effectiveFrom, now)) {
      return false;
    }
    closePreviousActiveProviderRates(sourceCurrencyCode, targetCurrencyCode, effectiveFrom, now);
    return true;
  }

  private Optional<ProviderExchangeRate> findProviderRate(
      final String provider,
      final String sourceCurrencyCode,
      final String targetCurrencyCode,
      final LocalDate providerRateDate) {
    try {
      return Optional.ofNullable(
          this.jdbcTemplate.queryForObject(
              "SELECT id, exchange_rate FROM m_exchange_rate WHERE provider = ?"
                  + " AND provider_rate_date = ? AND source_currency_code = ?"
                  + " AND target_currency_code = ?",
              (rs, rowNum) ->
                  new ProviderExchangeRate(rs.getLong("id"), rs.getBigDecimal("exchange_rate")),
              provider,
              providerRateDate,
              sourceCurrencyCode,
              targetCurrencyCode));
    } catch (final EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private boolean updateProviderRate(
      final Long exchangeRateId, final BigDecimal exchangeRate, final LocalDateTime now) {
    final int affectedRows =
        this.jdbcTemplate.update(
            "UPDATE m_exchange_rate SET exchange_rate = ?, last_synced_at = ?,"
                + " last_modified_date = ? WHERE id = ?",
            exchangeRate,
            now,
            now,
            exchangeRateId);
    return affectedRows > 0;
  }

  private boolean existsManualForEffectiveDate(
      final String sourceCurrencyCode, final String targetCurrencyCode, final LocalDate date) {
    final Long count =
        this.jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_exchange_rate WHERE source_currency_code = ?"
                + " AND target_currency_code = ? AND effective_from = ? AND provider IS NULL",
            Long.class,
            sourceCurrencyCode,
            targetCurrencyCode,
            date);
    return count != null && count > 0;
  }

  private boolean hasManualActiveRateOn(
      final String sourceCurrencyCode, final String targetCurrencyCode, final LocalDate date) {
    final Long count =
        this.jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_exchange_rate WHERE source_currency_code = ?"
                + " AND target_currency_code = ? AND provider IS NULL"
                + " AND active = true AND effective_from <= ?"
                + " AND (effective_to IS NULL OR effective_to >= ?)",
            Long.class,
            sourceCurrencyCode,
            targetCurrencyCode,
            date,
            date);
    return count != null && count > 0;
  }

  private boolean hasFutureActiveRate(
      final String sourceCurrencyCode, final String targetCurrencyCode, final LocalDate date) {
    final Long count =
        this.jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_exchange_rate WHERE source_currency_code = ?"
                + " AND target_currency_code = ? AND active = true AND effective_from > ?",
            Long.class,
            sourceCurrencyCode,
            targetCurrencyCode,
            date);
    return count != null && count > 0;
  }

  private Optional<ActiveExchangeRate> findActiveProviderRateOn(
      final String sourceCurrencyCode, final String targetCurrencyCode, final LocalDate date) {
    try {
      return Optional.ofNullable(
          this.jdbcTemplate.queryForObject(
              "SELECT id, exchange_rate, effective_from, effective_to FROM m_exchange_rate"
                  + " WHERE source_currency_code = ? AND target_currency_code = ?"
                  + " AND provider IS NOT NULL"
                  + " AND active = true AND effective_from <= ?"
                  + " AND (effective_to IS NULL OR effective_to >= ?)"
                  + " ORDER BY effective_from DESC, id DESC LIMIT 1",
              new ActiveExchangeRateMapper(),
              sourceCurrencyCode,
              targetCurrencyCode,
              date,
              date));
    } catch (final EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private void closePreviousActiveProviderRates(
      final String sourceCurrencyCode,
      final String targetCurrencyCode,
      final LocalDate effectiveFrom,
      final LocalDateTime now) {
    final MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("sourceCurrencyCode", sourceCurrencyCode)
            .addValue("targetCurrencyCode", targetCurrencyCode)
            .addValue("effectiveFrom", effectiveFrom)
            .addValue("effectiveTo", effectiveFrom.minusDays(1))
            .addValue("lastModifiedDate", now);
    this.namedJdbcTemplate.update(
        "UPDATE m_exchange_rate SET effective_to = :effectiveTo,"
            + " last_modified_date = :lastModifiedDate"
            + " WHERE source_currency_code = :sourceCurrencyCode"
            + " AND target_currency_code = :targetCurrencyCode"
            + " AND provider IS NOT NULL AND active = true AND effective_from < :effectiveFrom"
            + " AND (effective_to IS NULL OR effective_to >= :effectiveFrom)",
        params);
  }

  private boolean insertProviderRate(
      final String provider,
      final String sourceCurrencyCode,
      final String targetCurrencyCode,
      final BigDecimal exchangeRate,
      final LocalDate effectiveFrom,
      final LocalDateTime now) {
    final MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("sourceCurrencyCode", sourceCurrencyCode)
            .addValue("targetCurrencyCode", targetCurrencyCode)
            .addValue("exchangeRate", exchangeRate)
            .addValue("effectiveFrom", effectiveFrom)
            .addValue("provider", provider)
            .addValue("providerRateDate", effectiveFrom)
            .addValue("lastSyncedAt", now)
            .addValue("createdDate", now)
            .addValue("lastModifiedDate", now);

    final int affectedRows =
        this.namedJdbcTemplate.update(
            "INSERT INTO m_exchange_rate"
                + " (source_currency_code, target_currency_code, exchange_rate, effective_from,"
                + " effective_to, active, provider, provider_rate_date, last_synced_at,"
                + " created_date, last_modified_date)"
                + " VALUES (:sourceCurrencyCode, :targetCurrencyCode, :exchangeRate,"
                + " :effectiveFrom, null, true, :provider, :providerRateDate, :lastSyncedAt,"
                + " :createdDate, :lastModifiedDate)"
                + " ON CONFLICT (source_currency_code, target_currency_code, effective_from)"
                + " DO NOTHING",
            params);
    return affectedRows > 0;
  }

  private boolean isSupportedCurrency(final String currencyCode) {
    try {
      this.validator.validateSupportedCurrency("targetCurrency", currencyCode);
      return true;
    } catch (final PlatformApiDataValidationException e) {
      return false;
    }
  }

  private String normalizeCurrencyCode(final String currencyCode) {
    return currencyCode == null ? null : currencyCode.trim().toUpperCase(Locale.ROOT);
  }

  private PlatformApiDataValidationException validationError(
      final String code, final String message) {
    return new PlatformApiDataValidationException(
        List.of(ApiParameterError.generalError(code, message)));
  }

  private record ActiveExchangeRate(
      Long id, BigDecimal exchangeRate, LocalDate effectiveFrom, LocalDate effectiveTo) {}

  private record ProviderExchangeRate(Long id, BigDecimal exchangeRate) {}

  private static final class ActiveExchangeRateMapper implements RowMapper<ActiveExchangeRate> {

    @Override
    public ActiveExchangeRate mapRow(final ResultSet rs, final int rowNum) throws SQLException {
      return new ActiveExchangeRate(
          rs.getLong("id"),
          rs.getBigDecimal("exchange_rate"),
          rs.getDate("effective_from").toLocalDate(),
          rs.getDate("effective_to") != null ? rs.getDate("effective_to").toLocalDate() : null);
    }
  }
}
