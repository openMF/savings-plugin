/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Aggregated daily exchange rate containing both buy and sell rates from BCCR. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BccrDailyRate {

  private LocalDate date;
  private BigDecimal buyRate;
  private BigDecimal sellRate;
  private BigDecimal referenceRate;
  private String sourceCurrency;
  private String targetCurrency;
}
