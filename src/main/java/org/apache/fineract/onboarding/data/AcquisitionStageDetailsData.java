/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.data;

import java.math.BigDecimal;

public record AcquisitionStageDetailsData(
    String source,
    String sourceStatus,
    Long actorId,
    String actorName,
    String sourceReference,
    String reason,
    Long transactionId,
    BigDecimal amount,
    String currencyCode,
    String accountStatus) {}
