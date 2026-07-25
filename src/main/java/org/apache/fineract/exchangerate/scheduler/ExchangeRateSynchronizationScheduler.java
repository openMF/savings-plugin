/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.exchangerate.config.ExchangeRateProviderProperties;
import org.apache.fineract.exchangerate.service.ExchangeRateSynchronizationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Optional scheduled synchronization for provider-backed exchange rates. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExchangeRateSynchronizationScheduler {

  private final ExchangeRateProviderProperties properties;
  private final ExchangeRateSynchronizationService synchronizationService;

  /** Runs provider synchronization for the configured base currency when scheduling is enabled. */
  @Scheduled(cron = "${exchange-rate.sync.cron:0 0 18 * * *}")
  public void synchronizeConfiguredBaseCurrency() {
    if (!this.properties.isProviderEnabled() || !this.properties.isSyncEnabled()) {
      log.debug("Exchange-rate synchronization is disabled. Skipping scheduler execution.");
      return;
    }

    try {
      this.synchronizationService.synchronizeBaseCurrency(this.properties.getBaseCurrency());
    } catch (final RuntimeException e) {
      log.warn("Exchange-rate synchronization failed: {}", e.getMessage(), e);
    }
  }
}
