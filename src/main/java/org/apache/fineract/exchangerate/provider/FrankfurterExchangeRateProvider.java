/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.apache.fineract.exchangerate.config.ExchangeRateProviderProperties;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Frankfurter-backed exchange-rate provider. */
@Component
public class FrankfurterExchangeRateProvider implements ExchangeRateProvider {

  public static final String PROVIDER_NAME = "frankfurter";

  private final RestTemplate restTemplate;
  private final ExchangeRateProviderProperties properties;

  public FrankfurterExchangeRateProvider(
      final RestTemplate restTemplate, final ExchangeRateProviderProperties properties) {
    this.restTemplate = restTemplate;
    this.properties = properties;
  }

  @Override
  public String providerName() {
    return PROVIDER_NAME;
  }

  @Override
  public ExchangeRateProviderResult fetchLatestRates(final String baseCurrencyCode) {
    final URI uri =
        UriComponentsBuilder.fromUriString(this.properties.getProviderBaseUrl())
            .path("/latest")
            .queryParam("base", baseCurrencyCode)
            .build()
            .toUri();

    final FrankfurterLatestResponse response;
    try {
      response = this.restTemplate.getForObject(uri, FrankfurterLatestResponse.class);
    } catch (final RestClientException e) {
      throw validationError(
          "validation.msg.exchangeRate.provider.unavailable",
          "Exchange-rate provider is unavailable.",
          e);
    }

    if (response == null
        || response.getBase() == null
        || response.getBase().isBlank()
        || response.getDate() == null
        || response.getRates() == null
        || response.getRates().isEmpty()) {
      throw validationError(
          "validation.msg.exchangeRate.provider.response.invalid",
          "Exchange-rate provider returned a malformed response.",
          null);
    }

    final Map<String, BigDecimal> normalizedRates = new LinkedHashMap<>();
    response
        .getRates()
        .forEach(
            (currencyCode, rate) -> {
              if (currencyCode == null
                  || currencyCode.isBlank()
                  || rate == null
                  || rate.compareTo(BigDecimal.ZERO) <= 0) {
                throw validationError(
                    "validation.msg.exchangeRate.provider.response.invalid",
                    "Exchange-rate provider returned a malformed response.",
                    null);
              }
              normalizedRates.put(currencyCode.trim().toUpperCase(Locale.ROOT), rate);
            });

    return ExchangeRateProviderResult.instance(
        PROVIDER_NAME,
        response.getBase().trim().toUpperCase(Locale.ROOT),
        response.getDate(),
        normalizedRates);
  }

  private PlatformApiDataValidationException validationError(
      final String code, final String message, final Throwable cause) {
    final ApiParameterError error = ApiParameterError.generalError(code, message);
    return cause == null
        ? new PlatformApiDataValidationException(List.of(error))
        : new PlatformApiDataValidationException(List.of(error), cause);
  }

  @Getter
  @Setter
  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class FrankfurterLatestResponse {

    private String base;
    private LocalDate date;
    private Map<String, BigDecimal> rates;
  }
}
