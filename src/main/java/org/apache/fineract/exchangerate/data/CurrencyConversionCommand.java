/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Parsed command for converting an amount between two configured currencies. */
public record CurrencyConversionCommand(
    String sourceCurrencyCode,
    String targetCurrencyCode,
    BigDecimal amount,
    LocalDate conversionDate) {}
