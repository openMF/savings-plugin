/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One board phase. pendingRequirements and scoreComponents are null when the backend has no
 * authoritative requirement model for the phase (currently every phase except PARAMETRIC_SCORE).
 */
public record CreditOriginationStageData(
    String code,
    String displayName,
    String status,
    OffsetDateTime completedOn,
    CreditOriginationStageDetailsData details,
    OffsetDateTime enteredOn,
    OffsetDateTime lastUpdatedOn,
    List<String> pendingRequirements,
    List<CreditOriginationScoreComponentData> scoreComponents) {}
