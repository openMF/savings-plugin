/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.apache.fineract.exchangerate.config.ExchangeRateProviderProperties;
import org.apache.fineract.exchangerate.service.ExchangeRateSynchronizationService;
import org.junit.jupiter.api.Test;

class ExchangeRateSynchronizationSchedulerTest {

  @Test
  void schedulerDoesNothingWhenSynchronizationIsDisabled() {
    final ExchangeRateProviderProperties properties = new ExchangeRateProviderProperties();
    final ExchangeRateSynchronizationService synchronizationService =
        mock(ExchangeRateSynchronizationService.class);

    new ExchangeRateSynchronizationScheduler(properties, synchronizationService)
        .synchronizeConfiguredBaseCurrency();

    verifyNoInteractions(synchronizationService);
  }

  @Test
  void schedulerSynchronizesConfiguredBaseCurrencyWhenEnabled() {
    final ExchangeRateProviderProperties properties = new ExchangeRateProviderProperties();
    properties.setProviderEnabled(true);
    properties.setSyncEnabled(true);
    properties.setBaseCurrency("USD");
    final ExchangeRateSynchronizationService synchronizationService =
        mock(ExchangeRateSynchronizationService.class);

    new ExchangeRateSynchronizationScheduler(properties, synchronizationService)
        .synchronizeConfiguredBaseCurrency();

    verify(synchronizationService).synchronizeBaseCurrency("USD");
  }
}
