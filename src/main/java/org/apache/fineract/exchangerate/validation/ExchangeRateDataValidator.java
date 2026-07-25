/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.validation;

import com.google.gson.JsonElement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.exchangerate.data.CurrencyConversionCommand;
import org.apache.fineract.exchangerate.data.ExchangeRateCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.service.CurrencyReadPlatformService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Component;

/** Validates dynamic exchange-rate requests against tenant currency configuration. */
@Component
@RequiredArgsConstructor
public class ExchangeRateDataValidator {

  private static final String RESOURCE_NAME = "exchangeRate";
  private static final Set<String> RATE_PARAMETERS =
      Set.of(
          "sourceCurrencyCode",
          "sourceCurrency",
          "targetCurrencyCode",
          "targetCurrency",
          "exchangeRate",
          "effectiveFrom",
          "effectiveTo",
          "active");
  private static final Set<String> CONVERSION_PARAMETERS =
      Set.of(
          "sourceCurrencyCode",
          "sourceCurrency",
          "targetCurrencyCode",
          "targetCurrency",
          "amount",
          "conversionDate");

  private final FromJsonHelper fromJsonHelper;
  private final CurrencyReadPlatformService currencyReadPlatformService;

  /** Validates a complete create request and resolves tenant-supported currencies. */
  public ExchangeRateCommand validateCreate(final String jsonBody) {
    final JsonElement element = this.fromJsonHelper.parse(jsonBody);
    this.fromJsonHelper.checkForUnsupportedParameters(element.getAsJsonObject(), RATE_PARAMETERS);

    final String sourceCurrency =
        extractCurrencyCode("sourceCurrencyCode", "sourceCurrency", element);
    final String targetCurrency =
        extractCurrencyCode("targetCurrencyCode", "targetCurrency", element);
    final BigDecimal exchangeRate =
        this.fromJsonHelper.extractBigDecimalWithLocaleNamed("exchangeRate", element);
    final LocalDate effectiveFrom = extractIsoLocalDateNamed("effectiveFrom", element);
    final LocalDate effectiveTo = extractIsoLocalDateNamed("effectiveTo", element);
    final Boolean active = this.fromJsonHelper.extractBooleanNamed("active", element);

    validateRateData(
        sourceCurrency, targetCurrency, exchangeRate, effectiveFrom, effectiveTo, true);
    return new ExchangeRateCommand(
        sourceCurrency, targetCurrency, exchangeRate, effectiveFrom, effectiveTo, true, active);
  }

  /** Validates a partial update request and resolves any provided tenant-supported currencies. */
  public ExchangeRateCommand validateUpdate(final String jsonBody) {
    final JsonElement element = this.fromJsonHelper.parse(jsonBody);
    this.fromJsonHelper.checkForUnsupportedParameters(element.getAsJsonObject(), RATE_PARAMETERS);

    final String sourceCurrency =
        extractCurrencyCode("sourceCurrencyCode", "sourceCurrency", element);
    final String targetCurrency =
        extractCurrencyCode("targetCurrencyCode", "targetCurrency", element);
    final BigDecimal exchangeRate =
        this.fromJsonHelper.extractBigDecimalWithLocaleNamed("exchangeRate", element);
    final LocalDate effectiveFrom = extractIsoLocalDateNamed("effectiveFrom", element);
    final boolean effectiveToPresent = this.fromJsonHelper.parameterExists("effectiveTo", element);
    final LocalDate effectiveTo = extractIsoLocalDateNamed("effectiveTo", element);
    final Boolean active = this.fromJsonHelper.extractBooleanNamed("active", element);

    validateRateData(
        sourceCurrency, targetCurrency, exchangeRate, effectiveFrom, effectiveTo, false);
    return new ExchangeRateCommand(
        sourceCurrency,
        targetCurrency,
        exchangeRate,
        effectiveFrom,
        effectiveTo,
        effectiveToPresent,
        active);
  }

  /** Validates a conversion request and resolves both tenant-supported currencies. */
  public CurrencyConversionCommand validateConversion(final String jsonBody) {
    final JsonElement element = this.fromJsonHelper.parse(jsonBody);
    this.fromJsonHelper.checkForUnsupportedParameters(
        element.getAsJsonObject(), CONVERSION_PARAMETERS);

    final String sourceCurrency =
        extractCurrencyCode("sourceCurrencyCode", "sourceCurrency", element);
    final String targetCurrency =
        extractCurrencyCode("targetCurrencyCode", "targetCurrency", element);
    final BigDecimal amount =
        this.fromJsonHelper.extractBigDecimalWithLocaleNamed("amount", element);
    final LocalDate conversionDate = extractIsoLocalDateNamed("conversionDate", element);

    final List<ApiParameterError> errors = new ArrayList<>();
    final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource(RESOURCE_NAME);
    validator.parameter("sourceCurrency").value(sourceCurrency).notBlank().notExceedingLengthOf(3);
    validator.parameter("targetCurrency").value(targetCurrency).notBlank().notExceedingLengthOf(3);
    validator.parameter("amount").value(amount).notNull().positiveAmount();
    validator.parameter("conversionDate").value(conversionDate).notNull();
    validateDistinctCurrencies(sourceCurrency, targetCurrency, errors);
    throwIfErrors(errors);

    validateSupportedCurrency("sourceCurrency", sourceCurrency);
    validateSupportedCurrency("targetCurrency", targetCurrency);
    return new CurrencyConversionCommand(sourceCurrency, targetCurrency, amount, conversionDate);
  }

  /** Returns configured currency metadata or raises a validation error for unsupported codes. */
  public CurrencyData validateSupportedCurrency(
      final String parameterName, final String currencyCode) {
    try {
      return this.currencyReadPlatformService.retrieveCurrency(currencyCode);
    } catch (final EmptyResultDataAccessException e) {
      throwUnsupportedCurrency(parameterName, currencyCode);
      return null;
    }
  }

  private void validateRateData(
      final String sourceCurrency,
      final String targetCurrency,
      final BigDecimal exchangeRate,
      final LocalDate effectiveFrom,
      final LocalDate effectiveTo,
      final boolean requireAllFields) {
    final List<ApiParameterError> errors = new ArrayList<>();
    final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource(RESOURCE_NAME);

    if (requireAllFields || sourceCurrency != null) {
      validator
          .parameter("sourceCurrency")
          .value(sourceCurrency)
          .notBlank()
          .notExceedingLengthOf(3);
    }
    if (requireAllFields || targetCurrency != null) {
      validator
          .parameter("targetCurrency")
          .value(targetCurrency)
          .notBlank()
          .notExceedingLengthOf(3);
    }
    if (requireAllFields || exchangeRate != null) {
      validator.parameter("exchangeRate").value(exchangeRate).notNull().positiveAmount();
    }
    if (requireAllFields || effectiveFrom != null) {
      validator.parameter("effectiveFrom").value(effectiveFrom).notNull();
    }
    if (effectiveFrom != null && effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
      errors.add(
          ApiParameterError.parameterError(
              "validation.msg.exchangeRate.effectiveTo.before.effectiveFrom",
              "Effective to date must be on or after effective from date.",
              "effectiveTo",
              effectiveTo));
    }
    validateDistinctCurrencies(sourceCurrency, targetCurrency, errors);
    throwIfErrors(errors);

    if (sourceCurrency != null) {
      validateSupportedCurrency("sourceCurrency", sourceCurrency);
    }
    if (targetCurrency != null) {
      validateSupportedCurrency("targetCurrency", targetCurrency);
    }
  }

  private void validateDistinctCurrencies(
      final String sourceCurrency,
      final String targetCurrency,
      final List<ApiParameterError> errors) {
    if (sourceCurrency != null && sourceCurrency.equals(targetCurrency)) {
      errors.add(
          ApiParameterError.parameterError(
              "validation.msg.exchangeRate.same.currency",
              "Source and target currencies must differ.",
              "targetCurrency",
              targetCurrency));
    }
  }

  private String extractCurrencyCode(
      final String canonicalParameter, final String aliasParameter, final JsonElement element) {
    String value = null;
    if (this.fromJsonHelper.parameterExists(canonicalParameter, element)) {
      value = this.fromJsonHelper.extractStringNamed(canonicalParameter, element);
    } else if (this.fromJsonHelper.parameterExists(aliasParameter, element)) {
      value = this.fromJsonHelper.extractStringNamed(aliasParameter, element);
    }
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private LocalDate extractIsoLocalDateNamed(
      final String parameterName, final JsonElement element) {
    if (!this.fromJsonHelper.parameterExists(parameterName, element)) {
      return null;
    }
    final String value = this.fromJsonHelper.extractStringNamed(parameterName, element);
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (final DateTimeParseException e) {
      final ApiParameterError error =
          ApiParameterError.parameterError(
              "validation.msg.exchangeRate.date.invalid",
              "The parameter " + parameterName + " must be a valid ISO date.",
              parameterName,
              value);
      throw new PlatformApiDataValidationException(List.of(error), e);
    }
  }

  private void throwUnsupportedCurrency(final String parameterName, final String currencyCode) {
    final ApiParameterError error =
        ApiParameterError.parameterError(
            "validation.msg.exchangeRate.currency.unsupported",
            "Currency " + currencyCode + " is not configured for this tenant.",
            parameterName,
            currencyCode);
    throw new PlatformApiDataValidationException(List.of(error));
  }

  private void throwIfErrors(final List<ApiParameterError> errors) {
    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }
  }
}
