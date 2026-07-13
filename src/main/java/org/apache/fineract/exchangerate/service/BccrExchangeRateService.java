/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.exchangerate.data.BccrDailyRate;
import org.apache.fineract.exchangerate.domain.BccrExchangeRate;

public interface BccrExchangeRateService {

  /**
   * Fetches and stores today's exchange rate from BCCR.
   *
   * @return the stored exchange rate, or empty if the fetch failed
   */
  Optional<BccrExchangeRate> fetchAndStoreTodayRate();

  /**
   * Fetches and stores exchange rates for a date range (for backfilling).
   *
   * @param fromDate start date (inclusive)
   * @param toDate end date (inclusive)
   * @return number of rates successfully stored
   */
  int fetchAndStoreRatesForRange(LocalDate fromDate, LocalDate toDate);

  /**
   * Gets the latest available exchange rate.
   *
   * @return the latest rate, or empty if none available
   */
  Optional<BccrExchangeRate> getLatestRate();

  /**
   * Gets the exchange rate for a specific date.
   *
   * @param date the date to query
   * @return the rate for that date, or empty if not available
   */
  Optional<BccrExchangeRate> getRateForDate(LocalDate date);

  /**
   * Gets historical rates for a date range.
   *
   * @param fromDate start date (inclusive)
   * @param toDate end date (inclusive)
   * @return list of rates in the range, ordered by date descending
   */
  List<BccrExchangeRate> getHistoricalRates(LocalDate fromDate, LocalDate toDate);

  /**
   * Converts an amount from USD to CRC using the latest available rate.
   *
   * @param amountInUsd the amount in USD
   * @return the equivalent amount in CRC, or the original amount if no rate is available
   */
  BigDecimal convertUsdToCrc(BigDecimal amountInUsd);

  /**
   * Gets the current daily rate as a DTO.
   *
   * @return the daily rate DTO, or empty if no rate is available
   */
  Optional<BccrDailyRate> getCurrentDailyRate();
  
    BccrExchangeRate createRate(BccrExchangeRate rate);
    Optional<BccrExchangeRate> getRateById(Long id);
    BccrExchangeRate updateRate(BccrExchangeRate rate);
    void deleteRate(Long id);
}
