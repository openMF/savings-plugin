/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Calendar-day aging of the current stage, measured from the authoritative stage-entry timestamp
 * (UTC date) to the tenant business date. Status is ON_TIME, STALLED (strictly more than
 * thresholdDays), UNKNOWN (missing, invalid or future entry evidence) or NOT_APPLICABLE (servicing
 * or inactive applications).
 */
public record CreditOriginationAgingData(
    String stage,
    OffsetDateTime enteredOn,
    String enteredOnSource,
    LocalDate businessDate,
    Long daysInStage,
    String status,
    int thresholdDays,
    String unit) {}
