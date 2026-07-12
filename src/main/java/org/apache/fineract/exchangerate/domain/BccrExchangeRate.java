/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

/**
 * Stores historical exchange rates published by the Central Bank of Costa Rica (BCCR).
 *
 * <p>Each record represents the official buy/sell rates for a specific date. The data is sourced
 * from the BCCR Web Service of Economic Indicators and is used to calculate transfer fees in CRC
 * when the base fee is defined in USD.
 *
 * @see <a href="https://www.bccr.fi.cr/indicadores-economicos/servicio-web">BCCR Web Service</a>
 */
@Entity
@Table(
    name = "m_bccr_exchange_rates",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_bccr_rate_date",
          columnNames = {"rate_date"})
    })
@Getter
@Setter
@NoArgsConstructor
public class BccrExchangeRate extends AbstractPersistableCustom<Long> {

  /** Date for which the rate was published (unique). */
  @Column(name = "rate_date", nullable = false)
  private LocalDate rateDate;

  /** BCCR indicator code for the buy rate (typically 317). */
  @Column(name = "buy_indicator_code", nullable = false, length = 10)
  private String buyIndicatorCode;

  /** BCCR indicator code for the sell rate (typically 318). */
  @Column(name = "sell_indicator_code", nullable = false, length = 10)
  private String sellIndicatorCode;

  /** Buy rate in CRC per 1 USD (how many colones the bank pays for 1 USD). */
  @Column(name = "buy_rate", nullable = false, precision = 19, scale = 6)
  private BigDecimal buyRate;

  /** Sell rate in CRC per 1 USD (how many colones the bank charges for 1 USD). */
  @Column(name = "sell_rate", nullable = false, precision = 19, scale = 6)
  private BigDecimal sellRate;

  /** Reference rate (average of buy and sell), used for general conversions. */
  @Column(name = "reference_rate", nullable = false, precision = 19, scale = 6)
  private BigDecimal referenceRate;

  /** Source currency code (ISO 4217). */
  @Column(name = "source_currency", nullable = false, length = 3)
  private String sourceCurrency;

  /** Target currency code (ISO 4217). */
  @Column(name = "target_currency", nullable = false, length = 3)
  private String targetCurrency;

  /** Timestamp when the rate was fetched from BCCR. */
  @Column(name = "fetched_at", nullable = false)
  private LocalDateTime fetchedAt;

  /** Timestamp when BCCR published the rate. */
  @Column(name = "published_at")
  private LocalDateTime publishedAt;

  /** Whether this is the latest available rate. */
  @Column(name = "is_latest", nullable = false)
  private boolean latest;

  /**
   * Factory method to create a new exchange rate instance.
   *
   * @param rateDate the date of the rate
   * @param buyRate the buy rate
   * @param sellRate the sell rate
   * @param fetchedAt when it was fetched
   * @return a new BccrExchangeRate instance
   */
  public static BccrExchangeRate instance(
      LocalDate rateDate, BigDecimal buyRate, BigDecimal sellRate, LocalDateTime fetchedAt) {
    BccrExchangeRate rate = new BccrExchangeRate();
    rate.setRateDate(rateDate);
    rate.setBuyIndicatorCode("317");
    rate.setSellIndicatorCode("318");
    rate.setBuyRate(buyRate);
    rate.setSellRate(sellRate);
    rate.setReferenceRate(
        buyRate.add(sellRate).divide(BigDecimal.valueOf(2), 6, java.math.RoundingMode.HALF_UP));
    rate.setSourceCurrency("USD");
    rate.setTargetCurrency("CRC");
    rate.setFetchedAt(fetchedAt);
    rate.setLatest(true);
    return rate;
  }

  /**
   * Calculates the conversion amount from USD to CRC using the sell rate.
   *
   * @param amountInUsd the amount in USD
   * @return the equivalent amount in CRC
   */
  public BigDecimal convertUsdToCrc(BigDecimal amountInUsd) {
    if (amountInUsd == null) {
      return BigDecimal.ZERO;
    }
    return amountInUsd.multiply(sellRate).setScale(2, java.math.RoundingMode.HALF_UP);
  }

  /**
   * Calculates the conversion amount from CRC to USD using the buy rate.
   *
   * @param amountInCrc the amount in CRC
   * @return the equivalent amount in USD
   */
  public BigDecimal convertCrcToUsd(BigDecimal amountInCrc) {
    if (amountInCrc == null || buyRate.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ZERO;
    }
    return amountInCrc.divide(buyRate, 2, java.math.RoundingMode.HALF_UP);
  }
}
