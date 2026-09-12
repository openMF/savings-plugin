/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.prospect.data;

import java.time.LocalDate;

public record ProspectSearchCriteria(
    String q,
    Long officeId,
    String registrationStatus,
    LocalDate createdFrom,
    LocalDate createdTo,
    Long clientId,
    String lastCompletedStageCode,
    int offset,
    int limit,
    String orderBy,
    String sortOrder) {}
