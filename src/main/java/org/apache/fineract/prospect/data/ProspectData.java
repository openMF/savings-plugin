/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.prospect.data;

public record ProspectData(
    Long prospectId,
    String externalRef,
    String displayName,
    Long officeId,
    Long clientId,
    String registrationStatus,
    String createdAt,
    String submittedAt,
    String lastUpdatedAt,
    String currentStage,
    String lastCompletedStage,
    String stoppedAtStage,
    Integer pendingCreditCount) {}
