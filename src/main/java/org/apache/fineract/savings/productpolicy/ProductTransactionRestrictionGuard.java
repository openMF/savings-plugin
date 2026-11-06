/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.event.business.domain.savings.transaction.SavingsAccountForceWithdrawalBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.savings.transaction.SavingsDepositBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.savings.transaction.SavingsWithdrawalBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.springframework.stereotype.Service;

/** Only actual synchronous events from the pinned Fineract dependency are registered. */
@Service
@RequiredArgsConstructor
public class ProductTransactionRestrictionGuard {
  private final BusinessEventNotifierService events;
  private final ProductTransactionEnforcement enforcement;

  @PostConstruct
  void register() {
    events.addPostBusinessEventListener(
        SavingsDepositBusinessEvent.class,
        e -> enforcement.validate(e.get(), ProductTransactionPolicyData.Operation.DEPOSIT));
    events.addPostBusinessEventListener(
        SavingsWithdrawalBusinessEvent.class,
        e -> enforcement.validate(e.get(), ProductTransactionPolicyData.Operation.WITHDRAWAL));
    events.addPostBusinessEventListener(
        SavingsAccountForceWithdrawalBusinessEvent.class,
        e ->
            enforcement.validate(
                e.get(), ProductTransactionPolicyData.Operation.FORCED_WITHDRAWAL));
  }
}
