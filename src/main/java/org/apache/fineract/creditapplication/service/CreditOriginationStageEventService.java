/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import org.apache.fineract.creditapplication.data.CreditOriginationStageEventCommand;

/** Internal integration boundary; this is intentionally not exposed as a user stage-toggle API. */
public interface CreditOriginationStageEventService {

  /** Records an idempotent source event, returning false when it was already recorded. */
  boolean recordStageEvent(CreditOriginationStageEventCommand command);
}
