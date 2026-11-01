/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.event.business.domain.client.ClientActivateBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.client.ClientCreateBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.savings.SavingsActivateBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.savings.SavingsApproveBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.savings.SavingsCreateBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.springframework.stereotype.Service;

/** Synchronous veto inside the native savings transaction, including administrative API calls. */
@Service
@RequiredArgsConstructor
public class ProductPolicyOpeningGuard {
  private final BusinessEventNotifierService events;
  private final ProductPolicyOpeningEnforcement enforcement;
  private final SavingsAccountRepositoryWrapper accounts;

  @PostConstruct
  void register() {
    events.addPostBusinessEventListener(
        SavingsCreateBusinessEvent.class,
        e -> enforcement.validate(e.get(), "CREATE_SAVINGSACCOUNT"));
    events.addPostBusinessEventListener(
        SavingsApproveBusinessEvent.class,
        e -> enforcement.validate(e.get(), "APPROVE_SAVINGSACCOUNT"));
    events.addPostBusinessEventListener(
        SavingsActivateBusinessEvent.class,
        e -> enforcement.validate(e.get(), "ACTIVATE_SAVINGSACCOUNT"));
    events.addPostBusinessEventListener(
        ClientCreateBusinessEvent.class, e -> validateClientAccount(e.get()));
    events.addPostBusinessEventListener(
        ClientActivateBusinessEvent.class, e -> validateClientAccount(e.get()));
  }

  private void validateClientAccount(Client client) {
    if (client.savingsAccountId() != null)
      enforcement.validate(
          accounts.findOneWithNotFoundDetection(client.savingsAccountId()),
          "ACTIVATE_SAVINGSACCOUNT");
  }
}
