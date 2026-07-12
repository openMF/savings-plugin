/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.exchangerate.scheduler;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.exchangerate.data.BccrServiceConfiguration;
import org.apache.fineract.exchangerate.domain.BccrExchangeRate;
import org.apache.fineract.exchangerate.service.BccrConfigurationService;
import org.apache.fineract.exchangerate.service.BccrExchangeRateService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job that fetches the daily USD/CRC exchange rate from the Central Bank of Costa Rica
 * (BCCR) and stores it in the database for use by the transfer fee system.
 *
 * <p>The scheduler is tenant-aware: each tenant can have its own BCCR configuration stored in the
 * {@code c_external_service_properties} table. The scheduler will only run if the service is
 * enabled for the current tenant.
 *
 * <p>The job runs daily at 8:00 AM Costa Rica time (CST/UTC-6) by default, which is typically when
 * the BCCR publishes the previous business day's exchange rates. The cron expression and timezone
 * can be configured per tenant.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BccrExchangeRateScheduler {

  private final BccrExchangeRateService exchangeRateService;
  private final BccrConfigurationService configurationService;

  /**
   * Scheduled job that runs daily at 8:00 AM Costa Rica time.
   *
   * <p>This job:
   *
   * <ol>
   *   <li>Checks if the BCCR service is enabled for the current tenant
   *   <li>Fetches today's exchange rate from BCCR
   *   <li>Stores it in the database
   *   <li>Optionally backfills missing historical rates
   * </ol>
   */
  @Scheduled(cron = "0 0 8 * * *", zone = "America/Costa_Rica")
  public void fetchDailyExchangeRate() {
    BccrServiceConfiguration config = configurationService.getConfiguration();

    if (!config.isEnabled()) {
      log.debug("BCCR service is disabled for this tenant. Skipping scheduler execution.");
      return;
    }

    if (!config.isValid()) {
      log.warn(
          "BCCR service configuration is invalid for this tenant. Token may not be configured. Skipping scheduler execution.");
      return;
    }

    if (!config.isSchedulerEnabled()) {
      log.debug("BCCR scheduler is disabled for this tenant. Skipping execution.");
      return;
    }

    ZoneId timezone;
    try {
      timezone = ZoneId.of(config.getTimezone());
    } catch (Exception e) {
      log.warn("Invalid timezone '{}', falling back to America/Costa_Rica", config.getTimezone());
      timezone = ZoneId.of("America/Costa_Rica");
    }

    log.info("Starting BCCR exchange rate fetch job for tenant timezone: {}", timezone);
    LocalDate today = LocalDate.now(timezone);

    try {
      // Fetch today's rate
      Optional<BccrExchangeRate> rate = exchangeRateService.fetchAndStoreTodayRate();

      if (rate.isPresent()) {
        log.info(
            "Successfully fetched exchange rate for {}: Buy={}, Sell={}",
            today,
            rate.get().getBuyRate(),
            rate.get().getSellRate());
      } else {
        log.warn(
            "Could not fetch exchange rate for {}. BCCR may not have published it yet.", today);
        // Try to get the most recent available rate
        Optional<BccrExchangeRate> latestRate = exchangeRateService.getLatestRate();
        if (latestRate.isPresent()) {
          log.info("Using most recent available rate from {}", latestRate.get().getRateDate());
        }
      }

      // Optional: Backfill missing historical rates
      if (config.getBackfillDays() > 0) {
        backfillMissingRates(today, config.getBackfillDays());
      }

    } catch (Exception e) {
      log.error("Failed to execute BCCR exchange rate fetch job: {}", e.getMessage(), e);
    }

    log.info("BCCR exchange rate fetch job completed");
  }

  /** Backfills missing exchange rates for the configured number of past days. */
  private void backfillMissingRates(LocalDate today, int backfillDays) {
    LocalDate fromDate = today.minusDays(backfillDays);
    log.info("Backfilling exchange rates from {} to {}", fromDate, today);

    try {
      int storedCount = exchangeRateService.fetchAndStoreRatesForRange(fromDate, today);
      log.info("Backfilled {} exchange rates", storedCount);
    } catch (Exception e) {
      log.error("Failed to backfill exchange rates: {}", e.getMessage(), e);
    }
  }
}
