/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Validated and normalized criteria used by the credit-application query. */
public record CreditApplicationSearchCriteria(
    Long clientId,
    Long officeId,
    Long clientTypeId,
    Long productId,
    Integer status,
    LocalDate submittedFrom,
    LocalDate submittedTo,
    BigDecimal minAmount,
    BigDecimal maxAmount,
    String currencyCode,
    Long stateProvinceId,
    String municipality,
    int offset,
    int limit,
    String orderBy,
    String sortOrder) {}
