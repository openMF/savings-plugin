/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.data;

import java.time.OffsetDateTime;

/** Internal command used by authoritative workflow integrations to publish stage evidence. */
public record CreditOriginationStageEventCommand(
    Long creditApplicationId,
    Long clientId,
    CreditOriginationStageCode stage,
    String status,
    OffsetDateTime occurredOn,
    Long actorId,
    String source,
    String sourceReference,
    String eventKey,
    String reason) {}
