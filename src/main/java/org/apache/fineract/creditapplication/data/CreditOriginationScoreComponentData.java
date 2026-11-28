/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.data;

import java.time.OffsetDateTime;

/**
 * Latest published evidence for one parametric-score component. Status is UNAVAILABLE when no
 * evidence was ever published; provider payloads are never exposed, only their references.
 */
public record CreditOriginationScoreComponentData(
    String code,
    String displayName,
    boolean required,
    String status,
    String evidenceSource,
    String evidenceReference,
    OffsetDateTime occurredOn,
    OffsetDateTime lastUpdatedOn,
    String reason) {}
