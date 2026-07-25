/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Immutable DTO representing a configured exchange rate. */
public final class ExchangeRateData {

  private final Long id;
  private final String sourceCurrencyCode;
  private final String targetCurrencyCode;
  private final BigDecimal exchangeRate;
  private final LocalDate effectiveFrom;
  private final LocalDate effectiveTo;
  private final boolean active;
  private final LocalDateTime createdDate;
  private final LocalDateTime lastModifiedDate;

  private ExchangeRateData(
      final Long id,
      final String sourceCurrencyCode,
      final String targetCurrencyCode,
      final BigDecimal exchangeRate,
      final LocalDate effectiveFrom,
      final LocalDate effectiveTo,
      final boolean active,
      final LocalDateTime createdDate,
      final LocalDateTime lastModifiedDate) {
    this.id = id;
    this.sourceCurrencyCode = sourceCurrencyCode;
    this.targetCurrencyCode = targetCurrencyCode;
    this.exchangeRate = exchangeRate;
    this.effectiveFrom = effectiveFrom;
    this.effectiveTo = effectiveTo;
    this.active = active;
    this.createdDate = createdDate;
    this.lastModifiedDate = lastModifiedDate;
  }

  /** Creates an exchange-rate DTO for serialization. */
  public static ExchangeRateData instance(
      final Long id,
      final String sourceCurrencyCode,
      final String targetCurrencyCode,
      final BigDecimal exchangeRate,
      final LocalDate effectiveFrom,
      final LocalDate effectiveTo,
      final boolean active,
      final LocalDateTime createdDate,
      final LocalDateTime lastModifiedDate) {
    return new ExchangeRateData(
        id,
        sourceCurrencyCode,
        targetCurrencyCode,
        exchangeRate,
        effectiveFrom,
        effectiveTo,
        active,
        createdDate,
        lastModifiedDate);
  }

  /** Returns the exchange-rate identifier. */
  public Long getId() {
    return id;
  }

  /** Returns the source currency code. */
  public String getSourceCurrencyCode() {
    return sourceCurrencyCode;
  }

  /** Returns the target currency code. */
  public String getTargetCurrencyCode() {
    return targetCurrencyCode;
  }

  /** Returns the configured exchange rate. */
  public BigDecimal getExchangeRate() {
    return exchangeRate;
  }

  /** Returns the first date this rate can apply. */
  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  /** Returns the final date this rate can apply, or null when open-ended. */
  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  /** Returns whether the rate is active. */
  public boolean isActive() {
    return active;
  }

  /** Returns the creation timestamp. */
  public LocalDateTime getCreatedDate() {
    return createdDate;
  }

  /** Returns the last modification timestamp. */
  public LocalDateTime getLastModifiedDate() {
    return lastModifiedDate;
  }
}
