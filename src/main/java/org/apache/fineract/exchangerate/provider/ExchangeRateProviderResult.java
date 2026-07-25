/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Normalized response returned by an external exchange-rate provider. */
@Getter
@RequiredArgsConstructor(staticName = "instance")
public final class ExchangeRateProviderResult {

  private final String provider;
  private final String baseCurrencyCode;
  private final LocalDate rateDate;
  private final Map<String, BigDecimal> rates;
}
