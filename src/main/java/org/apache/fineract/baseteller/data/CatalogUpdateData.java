/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.data;

import java.time.OffsetDateTime;

/**
 * Office-scoped Base Teller catalog refresh status. {@code needsUpdate} means that refresh is
 * recommended by the configured freshness policy; it does not assert that Fineract data changed.
 */
public record CatalogUpdateData(
    CatalogUpdateCategory category,
    OffsetDateTime lastUpdatedAt,
    OffsetDateTime lastAttemptAt,
    boolean needsUpdate,
    CatalogUpdateStatus status,
    String failureCode) {}
