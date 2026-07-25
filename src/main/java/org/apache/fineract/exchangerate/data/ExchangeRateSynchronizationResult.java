/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** API response for external exchange-rate synchronization. */
@Getter
@RequiredArgsConstructor(staticName = "instance")
public final class ExchangeRateSynchronizationResult {

  private final int importedCount;
  private final int skippedCount;
  private final LocalDateTime timestamp;
  private final String provider;
  private final String baseCurrency;
  private final LocalDate providerRateDate;
}
