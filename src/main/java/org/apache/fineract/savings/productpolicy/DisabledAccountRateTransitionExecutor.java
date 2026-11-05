/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.RatePropagationData.*;

import org.springframework.stereotype.Component;

/** No configuration flag can enable unverified financial behavior. No core write dependencies. */
@Component
public final class DisabledAccountRateTransitionExecutor implements AccountRateTransitionExecutor {
  @Override
  public boolean financialExecutionSupported() {
    return false;
  }

  @Override
  public String blockingReason(Source source) {
    return switch (source.policy().interestMode()) {
      case TIERED -> "FINERACT_TIERED_EXECUTION_UNSUPPORTED";
      case REFERENCE_RATE -> "FINERACT_REFERENCE_RATE_EXECUTION_UNSUPPORTED";
      default -> "FINERACT_EFFECTIVE_DATED_ACCOUNT_REPRICING_UNSUPPORTED";
    };
  }

  @Override
  public TransitionResult execute(
      String operationKey,
      AccountSnapshot account,
      Source source,
      java.time.LocalDate effectiveDate) {
    return new TransitionResult("BLOCKED", blockingReason(source), null);
  }

  @Override
  public TransitionResult reconcile(String operationKey) {
    return new TransitionResult(
        "UNKNOWN_OUTCOME", "AUTHORITATIVE_RECONCILIATION_UNAVAILABLE", null);
  }
}
