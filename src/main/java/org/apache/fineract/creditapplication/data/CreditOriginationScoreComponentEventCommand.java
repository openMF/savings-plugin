/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.data;

import java.time.OffsetDateTime;

/**
 * Internal command used by scoring integrations to publish component upload/validation evidence.
 */
public record CreditOriginationScoreComponentEventCommand(
    Long creditApplicationId,
    Long clientId,
    CreditOriginationScoreComponent component,
    String status,
    OffsetDateTime occurredOn,
    Long actorId,
    String source,
    String sourceReference,
    String eventKey,
    String reason) {}
