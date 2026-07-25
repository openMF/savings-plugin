/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.service;

import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;

/** Write-side contract for administrator-configured exchange rates. */
public interface ExchangeRateWritePlatformService {

  /** Creates an exchange rate from the supplied request payload. */
  CommandProcessingResult createExchangeRate(String jsonBody);

  /** Updates an exchange rate from the supplied request payload. */
  CommandProcessingResult updateExchangeRate(Long exchangeRateId, String jsonBody);

  /** Deletes an exchange rate by identifier. */
  CommandProcessingResult deleteExchangeRate(Long exchangeRateId);
}
