/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.RatePropagationData.*;

/** Financial integration boundary. Reconciliation must precede any retry of an unknown outcome. */
public interface AccountRateTransitionExecutor {
  boolean financialExecutionSupported();

  String blockingReason(Source source);

  TransitionResult execute(
      String operationKey,
      AccountSnapshot account,
      Source source,
      java.time.LocalDate effectiveDate);

  TransitionResult reconcile(String operationKey);

  record TransitionResult(String outcome, String errorCategory, String confirmedOperationId) {}
}
