/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.fineract.exchangerate.config.ExchangeRateProviderProperties;
import org.apache.fineract.exchangerate.data.ExchangeRateSynchronizationResult;
import org.apache.fineract.exchangerate.provider.ExchangeRateProvider;
import org.apache.fineract.exchangerate.provider.ExchangeRateProviderResult;
import org.apache.fineract.exchangerate.validation.ExchangeRateDataValidator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ExchangeRateSynchronizationServiceImplTest {

  @Test
  @SuppressWarnings("unchecked")
  void synchronizationSkipsCurrencyWhenProviderInsertFindsConflict() {
    final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    final NamedParameterJdbcTemplate namedJdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    final ExchangeRateProvider provider = mock(ExchangeRateProvider.class);
    final ExchangeRateDataValidator validator = mock(ExchangeRateDataValidator.class);
    when(validator.validateSynchronization("{\"baseCurrency\":\"USD\"}", "USD")).thenReturn("USD");
    when(provider.providerName()).thenReturn("frankfurter");
    when(provider.fetchLatestRates("USD"))
        .thenReturn(
            ExchangeRateProviderResult.instance(
                "frankfurter",
                "USD",
                LocalDate.parse("2026-07-25"),
                Map.of("EUR", new BigDecimal("0.850000000000"))));
    when(jdbcTemplate.queryForObject(
            startsWith("SELECT id, exchange_rate FROM m_exchange_rate"),
            any(RowMapper.class),
            eq("frankfurter"),
            eq(LocalDate.parse("2026-07-25")),
            eq("USD"),
            eq("EUR")))
        .thenThrow(new EmptyResultDataAccessException(1));
    when(jdbcTemplate.queryForObject(startsWith("SELECT COUNT(*)"), eq(Long.class), any()))
        .thenReturn(0L);
    when(jdbcTemplate.queryForObject(
            startsWith("SELECT id, exchange_rate, effective_from, effective_to"),
            any(RowMapper.class),
            eq("USD"),
            eq("EUR"),
            eq(LocalDate.parse("2026-07-25")),
            eq(LocalDate.parse("2026-07-25"))))
        .thenThrow(new EmptyResultDataAccessException(1));
    when(namedJdbcTemplate.update(
            startsWith("INSERT INTO m_exchange_rate"), any(MapSqlParameterSource.class)))
        .thenReturn(0);

    final ExchangeRateSynchronizationServiceImpl service =
        new ExchangeRateSynchronizationServiceImpl(
            jdbcTemplate, namedJdbcTemplate, providerProperties(), List.of(provider), validator);

    final ExchangeRateSynchronizationResult result =
        service.synchronize("{\"baseCurrency\":\"USD\"}");

    assertEquals(0, result.getImportedCount());
    assertEquals(1, result.getSkippedCount());
    verify(namedJdbcTemplate, never())
        .update(
            startsWith("UPDATE m_exchange_rate SET effective_to"),
            any(MapSqlParameterSource.class));
  }

  private ExchangeRateProviderProperties providerProperties() {
    final ExchangeRateProviderProperties properties = new ExchangeRateProviderProperties();
    properties.setProviderEnabled(true);
    properties.setProvider("frankfurter");
    properties.setBaseCurrency("USD");
    return properties;
  }
}
