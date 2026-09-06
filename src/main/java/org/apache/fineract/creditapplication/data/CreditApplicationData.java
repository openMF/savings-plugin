/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Read-only credit-application row returned to back-office clients. */
public record CreditApplicationData(
    Long loanId,
    String accountNo,
    Long clientId,
    Long groupId,
    String clientName,
    Long clientTypeId,
    Long officeId,
    Long productId,
    String productName,
    String currencyCode,
    BigDecimal amount,
    CreditApplicationStatusData status,
    LocalDate submittedOnDate,
    Long stateProvinceId,
    String municipality) {}
