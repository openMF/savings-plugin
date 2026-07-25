/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.Map;
import org.apache.fineract.exchangerate.config.ExchangeRateProviderProperties;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

class FrankfurterExchangeRateProviderTest {

  @Test
  void fetchLatestRatesNormalizesValidProviderResponse() {
    final RestTemplate restTemplate = mock(RestTemplate.class);
    final ExchangeRateProviderProperties properties = providerProperties();
    final FrankfurterExchangeRateProvider.FrankfurterLatestResponse response =
        new FrankfurterExchangeRateProvider.FrankfurterLatestResponse();
    response.setBase("usd");
    response.setDate(LocalDate.parse("2026-07-25"));
    response.setRates(Map.of("eur", new BigDecimal("0.850000000000")));
    when(restTemplate.getForObject(
            any(URI.class), eq(FrankfurterExchangeRateProvider.FrankfurterLatestResponse.class)))
        .thenReturn(response);

    final ExchangeRateProviderResult result =
        new FrankfurterExchangeRateProvider(restTemplate, properties).fetchLatestRates("USD");

    assertEquals("frankfurter", result.getProvider());
    assertEquals("USD", result.getBaseCurrencyCode());
    assertEquals(LocalDate.parse("2026-07-25"), result.getRateDate());
    assertEquals(new BigDecimal("0.850000000000"), result.getRates().get("EUR"));
  }

  @Test
  void fetchLatestRatesRejectsMalformedProviderResponse() {
    final RestTemplate restTemplate = mock(RestTemplate.class);
    final FrankfurterExchangeRateProvider.FrankfurterLatestResponse response =
        new FrankfurterExchangeRateProvider.FrankfurterLatestResponse();
    response.setBase("USD");
    response.setDate(LocalDate.parse("2026-07-25"));
    response.setRates(Map.of("EUR", BigDecimal.ZERO));
    when(restTemplate.getForObject(
            any(URI.class), eq(FrankfurterExchangeRateProvider.FrankfurterLatestResponse.class)))
        .thenReturn(response);

    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            new FrankfurterExchangeRateProvider(restTemplate, providerProperties())
                .fetchLatestRates("USD"));
  }

  @Test
  void fetchLatestRatesHandlesProviderUnavailable() {
    final RestTemplate restTemplate = mock(RestTemplate.class);
    when(restTemplate.getForObject(
            any(URI.class), eq(FrankfurterExchangeRateProvider.FrankfurterLatestResponse.class)))
        .thenThrow(new ResourceAccessException("timeout"));

    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            new FrankfurterExchangeRateProvider(restTemplate, providerProperties())
                .fetchLatestRates("USD"));
  }

  private ExchangeRateProviderProperties providerProperties() {
    final ExchangeRateProviderProperties properties = new ExchangeRateProviderProperties();
    properties.setProviderBaseUrl("https://api.frankfurter.dev/v1");
    return properties;
  }
}
