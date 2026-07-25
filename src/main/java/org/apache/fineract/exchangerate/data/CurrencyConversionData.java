/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Immutable DTO representing a currency conversion result. */
public final class CurrencyConversionData {

  private final String sourceCurrency;
  private final String targetCurrency;
  private final BigDecimal exchangeRate;
  private final BigDecimal sourceAmount;
  private final BigDecimal convertedAmount;
  private final LocalDate effectiveDate;
  private final String rateSource;
  private final String baseCurrency;

  private CurrencyConversionData(
      final String sourceCurrency,
      final String targetCurrency,
      final BigDecimal exchangeRate,
      final BigDecimal sourceAmount,
      final BigDecimal convertedAmount,
      final LocalDate effectiveDate,
      final String rateSource,
      final String baseCurrency) {
    this.sourceCurrency = sourceCurrency;
    this.targetCurrency = targetCurrency;
    this.exchangeRate = exchangeRate;
    this.sourceAmount = sourceAmount;
    this.convertedAmount = convertedAmount;
    this.effectiveDate = effectiveDate;
    this.rateSource = rateSource;
    this.baseCurrency = baseCurrency;
  }

  /** Creates a conversion DTO for serialization. */
  public static CurrencyConversionData instance(
      final String sourceCurrency,
      final String targetCurrency,
      final BigDecimal exchangeRate,
      final BigDecimal sourceAmount,
      final BigDecimal convertedAmount,
      final LocalDate effectiveDate) {
    return new CurrencyConversionData(
        sourceCurrency,
        targetCurrency,
        exchangeRate,
        sourceAmount,
        convertedAmount,
        effectiveDate,
        null,
        null);
  }

  /** Creates a conversion DTO with rate-source metadata for serialization. */
  public static CurrencyConversionData instance(
      final String sourceCurrency,
      final String targetCurrency,
      final BigDecimal exchangeRate,
      final BigDecimal sourceAmount,
      final BigDecimal convertedAmount,
      final LocalDate effectiveDate,
      final String rateSource,
      final String baseCurrency) {
    return new CurrencyConversionData(
        sourceCurrency,
        targetCurrency,
        exchangeRate,
        sourceAmount,
        convertedAmount,
        effectiveDate,
        rateSource,
        baseCurrency);
  }

  /** Returns the source currency code. */
  public String getSourceCurrency() {
    return sourceCurrency;
  }

  /** Returns the target currency code. */
  public String getTargetCurrency() {
    return targetCurrency;
  }

  /** Returns the exchange rate used for the conversion. */
  public BigDecimal getExchangeRate() {
    return exchangeRate;
  }

  /** Returns the amount supplied in the source currency. */
  public BigDecimal getSourceAmount() {
    return sourceAmount;
  }

  /** Returns the rounded amount in the target currency. */
  public BigDecimal getConvertedAmount() {
    return convertedAmount;
  }

  /** Returns the conversion date used to choose the rate. */
  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  /** Returns DIRECT, PROVIDER, or CROSS_RATE when available. */
  public String getRateSource() {
    return rateSource;
  }

  /** Returns the configured base currency when a cross-rate conversion was used. */
  public String getBaseCurrency() {
    return baseCurrency;
  }
}
