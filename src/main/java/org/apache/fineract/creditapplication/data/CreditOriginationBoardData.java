/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.data;

import java.util.List;

/** Read model for the nine-phase credit-origination board. */
public record CreditOriginationBoardData(
    Long creditApplicationId,
    Long clientId,
    Long loanId,
    Long prospectId,
    String currentStage,
    List<CreditOriginationStageData> stages) {}
