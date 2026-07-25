/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.exchangerate.config.ExchangeRateProviderProperties;
import org.apache.fineract.exchangerate.data.CurrencyConversionCommand;
import org.apache.fineract.exchangerate.data.CurrencyConversionData;
import org.apache.fineract.exchangerate.data.ExchangeRateData;
import org.apache.fineract.exchangerate.validation.ExchangeRateDataValidator;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** JDBC-backed read service for dynamic exchange rates. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExchangeRateReadPlatformServiceImpl implements ExchangeRateReadPlatformService {

  private static final MathContext MONEY_CONTEXT = new MathContext(19, RoundingMode.HALF_UP);
  private static final String RATE_SOURCE_DIRECT = "DIRECT";
  private static final String RATE_SOURCE_PROVIDER = "PROVIDER";
  private static final String RATE_SOURCE_CROSS_RATE = "CROSS_RATE";
  private static final String RESOURCE_NAME_FOR_PERMISSIONS = "EXCHANGE_RATE";
  private static final String CONVERT_PERMISSION = "CONVERT_CURRENCY";
  private static final String READ_EXCHANGE_RATE_PERMISSION =
      "hasAuthority('ALL_FUNCTIONS') or hasAuthority('READ_EXCHANGE_RATE')";
  private static final String CONVERT_CURRENCY_PERMISSION =
      "hasAuthority('ALL_FUNCTIONS') or hasAuthority('CONVERT_CURRENCY')";

  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;
  private final PlatformSecurityContext context;
  private final ExchangeRateDataValidator validator;
  private final ExchangeRateProviderProperties properties;

  @Override
  @PreAuthorize(READ_EXCHANGE_RATE_PERMISSION)
  public Collection<ExchangeRateData> retrieveAll(
      final String sourceCurrencyCode,
      final String targetCurrencyCode,
      final Boolean active,
      final LocalDate effectiveOn) {
    this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);

    final List<Object> params = new ArrayList<>();
    if (sourceCurrencyCode != null && !sourceCurrencyCode.isBlank()) {
      this.validator.validateSupportedCurrency(
          "sourceCurrency", sourceCurrencyCode.trim().toUpperCase(Locale.ROOT));
    }
    if (targetCurrencyCode != null && !targetCurrencyCode.isBlank()) {
      this.validator.validateSupportedCurrency(
          "targetCurrency", targetCurrencyCode.trim().toUpperCase(Locale.ROOT));
    }
    final StringBuilder sql =
        new StringBuilder(
            "SELECT id, source_currency_code, target_currency_code, exchange_rate,"
                + " effective_from, effective_to, active, created_date, last_modified_date"
                + " FROM m_exchange_rate WHERE 1 = 1");

    if (sourceCurrencyCode != null && !sourceCurrencyCode.isBlank()) {
      sql.append(" AND source_currency_code = ?");
      params.add(sourceCurrencyCode.trim().toUpperCase(Locale.ROOT));
    }
    if (targetCurrencyCode != null && !targetCurrencyCode.isBlank()) {
      sql.append(" AND target_currency_code = ?");
      params.add(targetCurrencyCode.trim().toUpperCase(Locale.ROOT));
    }
    if (active != null) {
      sql.append(" AND active = ?");
      params.add(active);
    }
    if (effectiveOn != null) {
      if (active == null) {
        sql.append(" AND active = true");
      }
      sql.append(" AND effective_from <= ?")
          .append(" AND (effective_to IS NULL OR effective_to >= ?)");
      params.add(effectiveOn);
      params.add(effectiveOn);
    }
    sql.append(
        " ORDER BY source_currency_code, target_currency_code, effective_from DESC, id DESC");

    return this.jdbcTemplate.query(sql.toString(), new ExchangeRateRowMapper(), params.toArray());
  }

  @Override
  @PreAuthorize(READ_EXCHANGE_RATE_PERMISSION)
  public ExchangeRateData retrieveOne(final Long exchangeRateId) {
    this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    validateId(exchangeRateId);
    try {
      return this.jdbcTemplate.queryForObject(
          "SELECT id, source_currency_code, target_currency_code, exchange_rate,"
              + " effective_from, effective_to, active, created_date, last_modified_date"
              + " FROM m_exchange_rate WHERE id = ?",
          new ExchangeRateRowMapper(),
          exchangeRateId);
    } catch (final EmptyResultDataAccessException e) {
      throw notFound(exchangeRateId);
    }
  }

  @Override
  @PreAuthorize(CONVERT_CURRENCY_PERMISSION)
  public CurrencyConversionData convert(final String jsonBody) {
    this.context.authenticatedUser().validateHasPermissionTo(CONVERT_PERMISSION);
    final CurrencyConversionCommand command = this.validator.validateConversion(jsonBody);
    final ResolvedExchangeRate exchangeRate =
        resolveExchangeRate(
            command.sourceCurrencyCode(), command.targetCurrencyCode(), command.conversionDate());
    final CurrencyData targetCurrency =
        this.validator.validateSupportedCurrency("targetCurrency", command.targetCurrencyCode());
    final BigDecimal convertedAmount =
        Money.of(
                targetCurrency,
                command.amount().multiply(exchangeRate.exchangeRate(), MONEY_CONTEXT),
                MONEY_CONTEXT)
            .getAmount();

    return CurrencyConversionData.instance(
        command.sourceCurrencyCode(),
        command.targetCurrencyCode(),
        exchangeRate.exchangeRate(),
        command.amount(),
        convertedAmount,
        command.conversionDate(),
        exchangeRate.rateSource(),
        exchangeRate.baseCurrency());
  }

  private ResolvedExchangeRate resolveExchangeRate(
      final String sourceCurrencyCode, final String targetCurrencyCode, final LocalDate date) {
    return retrieveDirectActiveRate(sourceCurrencyCode, targetCurrencyCode, date)
        .orElseGet(() -> retrieveCrossRate(sourceCurrencyCode, targetCurrencyCode, date));
  }

  private Optional<ResolvedExchangeRate> retrieveDirectActiveRate(
      final String sourceCurrencyCode, final String targetCurrencyCode, final LocalDate date) {
    try {
      return Optional.ofNullable(
          this.jdbcTemplate.queryForObject(
              "SELECT exchange_rate, provider"
                  + " FROM m_exchange_rate"
                  + " WHERE source_currency_code = ? AND target_currency_code = ?"
                  + " AND active = true AND effective_from <= ?"
                  + " AND (effective_to IS NULL OR effective_to >= ?)"
                  + " ORDER BY CASE WHEN provider IS NULL THEN 0 ELSE 1 END,"
                  + " effective_from DESC, id DESC LIMIT 1",
              (rs, rowNum) ->
                  new ResolvedExchangeRate(
                      rs.getBigDecimal("exchange_rate"),
                      rs.getString("provider") == null ? RATE_SOURCE_DIRECT : RATE_SOURCE_PROVIDER,
                      null),
              sourceCurrencyCode,
              targetCurrencyCode,
              date,
              date));
    } catch (final EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private ResolvedExchangeRate retrieveCrossRate(
      final String sourceCurrencyCode, final String targetCurrencyCode, final LocalDate date) {
    final String baseCurrencyCode = normalizeCurrencyCode(this.properties.getBaseCurrency());
    this.validator.validateSupportedCurrency("baseCurrency", baseCurrencyCode);

    final Map<String, ResolvedExchangeRate> baseRates =
        retrieveBaseRates(baseCurrencyCode, Set.of(sourceCurrencyCode, targetCurrencyCode), date);
    final ResolvedExchangeRate sourceRate =
        baseCurrencyCode.equals(sourceCurrencyCode)
            ? new ResolvedExchangeRate(BigDecimal.ONE, RATE_SOURCE_DIRECT, null)
            : baseRates.get(sourceCurrencyCode);
    final ResolvedExchangeRate targetRate =
        baseCurrencyCode.equals(targetCurrencyCode)
            ? new ResolvedExchangeRate(BigDecimal.ONE, RATE_SOURCE_DIRECT, null)
            : baseRates.get(targetCurrencyCode);

    if (sourceRate == null || targetRate == null) {
      throw noRateForConversion(sourceCurrencyCode, targetCurrencyCode, date);
    }

    return new ResolvedExchangeRate(
        targetRate.exchangeRate().divide(sourceRate.exchangeRate(), MONEY_CONTEXT),
        RATE_SOURCE_CROSS_RATE,
        baseCurrencyCode);
  }

  private Map<String, ResolvedExchangeRate> retrieveBaseRates(
      final String baseCurrencyCode,
      final Set<String> requestedCurrencyCodes,
      final LocalDate date) {
    final Set<String> targetCurrencyCodes =
        requestedCurrencyCodes.stream()
            .filter(currencyCode -> !baseCurrencyCode.equals(currencyCode))
            .collect(java.util.stream.Collectors.toSet());
    if (targetCurrencyCodes.isEmpty()) {
      return Map.of();
    }

    final MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("baseCurrencyCode", baseCurrencyCode)
            .addValue("targetCurrencyCodes", targetCurrencyCodes)
            .addValue("date", date);
    final List<BaseRateRow> rows =
        this.namedJdbcTemplate.query(
            "SELECT target_currency_code, exchange_rate, provider"
                + " FROM m_exchange_rate"
                + " WHERE source_currency_code = :baseCurrencyCode"
                + " AND target_currency_code IN (:targetCurrencyCodes)"
                + " AND active = true AND effective_from <= :date"
                + " AND (effective_to IS NULL OR effective_to >= :date)"
                + " ORDER BY target_currency_code,"
                + " CASE WHEN provider IS NULL THEN 0 ELSE 1 END,"
                + " effective_from DESC, id DESC",
            params,
            (rs, rowNum) ->
                new BaseRateRow(
                    rs.getString("target_currency_code"),
                    rs.getBigDecimal("exchange_rate"),
                    rs.getString("provider")));

    final Map<String, ResolvedExchangeRate> rates = new HashMap<>();
    for (final BaseRateRow row : rows) {
      rates.putIfAbsent(
          row.targetCurrencyCode(),
          new ResolvedExchangeRate(
              row.exchangeRate(),
              row.provider() == null ? RATE_SOURCE_DIRECT : RATE_SOURCE_PROVIDER,
              null));
    }
    return rates;
  }

  private String normalizeCurrencyCode(final String currencyCode) {
    return currencyCode == null ? null : currencyCode.trim().toUpperCase(Locale.ROOT);
  }

  private PlatformApiDataValidationException noRateForConversion(
      final String sourceCurrencyCode, final String targetCurrencyCode, final LocalDate date) {
    final ApiParameterError error =
        ApiParameterError.generalError(
            "validation.msg.exchangeRate.not.found.for.conversion",
            "No active exchange rate exists for "
                + sourceCurrencyCode
                + " to "
                + targetCurrencyCode
                + " on "
                + date
                + ".");
    return new PlatformApiDataValidationException(List.of(error));
  }

  private void validateId(final Long exchangeRateId) {
    if (exchangeRateId == null || exchangeRateId <= 0) {
      final ApiParameterError error =
          ApiParameterError.parameterError(
              "validation.msg.exchangeRate.id.invalid",
              "Exchange rate id must be greater than zero.",
              "exchangeRateId",
              exchangeRateId);
      throw new PlatformApiDataValidationException(List.of(error));
    }
  }

  private PlatformDataIntegrityException notFound(final Long exchangeRateId) {
    return new PlatformDataIntegrityException(
        "error.msg.exchangeRate.not.found",
        "Exchange rate with id " + exchangeRateId + " was not found.",
        "exchangeRateId",
        exchangeRateId);
  }

  private static final class ExchangeRateRowMapper implements RowMapper<ExchangeRateData> {

    @Override
    public ExchangeRateData mapRow(final ResultSet rs, final int rowNum) throws SQLException {
      return ExchangeRateData.instance(
          rs.getLong("id"),
          rs.getString("source_currency_code"),
          rs.getString("target_currency_code"),
          rs.getBigDecimal("exchange_rate"),
          rs.getDate("effective_from").toLocalDate(),
          rs.getDate("effective_to") != null ? rs.getDate("effective_to").toLocalDate() : null,
          rs.getBoolean("active"),
          toLocalDateTime(rs, "created_date"),
          toLocalDateTime(rs, "last_modified_date"));
    }

    private LocalDateTime toLocalDateTime(final ResultSet rs, final String columnName)
        throws SQLException {
      return rs.getTimestamp(columnName) != null
          ? rs.getTimestamp(columnName).toLocalDateTime()
          : null;
    }
  }

  private record ResolvedExchangeRate(
      BigDecimal exchangeRate, String rateSource, String baseCurrency) {}

  private record BaseRateRow(String targetCurrencyCode, BigDecimal exchangeRate, String provider) {}
}
