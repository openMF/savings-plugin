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

/**
 * Data Transfer Object representing a single economic indicator response from the BCCR Web Service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BccrIndicatorResponse {

  /** BCCR indicator code (e.g., "317" for buy rate, "318" for sell rate). */
  private String indicatorCode;

  /** Date when the rate was published. */
  private LocalDate date;

  /** The published value of the indicator. */
  private BigDecimal value;

  /** Description of the indicator. */
  private String description;
}
