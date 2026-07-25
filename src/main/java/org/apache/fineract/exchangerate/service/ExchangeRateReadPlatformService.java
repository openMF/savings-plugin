/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.service;

import java.time.LocalDate;
import java.util.Collection;
import org.apache.fineract.exchangerate.data.CurrencyConversionData;
import org.apache.fineract.exchangerate.data.ExchangeRateData;

/** Read-side contract for configured exchange rates and currency conversion. */
public interface ExchangeRateReadPlatformService {

  /** Retrieves exchange rates matching the optional filters. */
  Collection<ExchangeRateData> retrieveAll(
      String sourceCurrencyCode, String targetCurrencyCode, Boolean active, LocalDate effectiveOn);

  /** Retrieves a single exchange rate by identifier. */
  ExchangeRateData retrieveOne(Long exchangeRateId);

  /** Converts an amount using the latest active rate valid for the requested date. */
  CurrencyConversionData convert(String jsonBody);
}
