/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.data;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class BccrExchangeRateRequest {
    private LocalDate rateDate;
    private String buyIndicatorCode;
    private String sellIndicatorCode;
    private BigDecimal buyRate;
    private BigDecimal sellRate;
    private BigDecimal referenceRate;
    private String sourceCurrency;
    private String targetCurrency;
    private Boolean latest;
}