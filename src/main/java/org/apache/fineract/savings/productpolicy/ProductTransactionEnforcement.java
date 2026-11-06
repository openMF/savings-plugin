/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductTransactionPolicyData.*;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Synchronous event veto; failure marks the originating transaction rollback-only even if caught.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductTransactionEnforcement {
  private final ProductTransactionPolicyResolver resolver;
  private final ProductTransactionPolicyEvaluator evaluator;
  private final ProductTransactionAuditService audit;
  private final PlatformSecurityContext security;

  @Transactional(propagation = Propagation.MANDATORY)
  public void validate(SavingsAccountTransaction transaction, Operation operation) {
    var account = transaction.getSavingsAccount();
    var resolved = resolver.resolve(account);
    if (resolved.decision().allowed() && resolved.policy() == null) return; // Native legacy path.
    var actor = security.authenticatedUser(); // Never a request-supplied actor/override flag.
    Decision decision = resolved.decision();
    if (decision.allowed())
      decision =
          evaluator.evaluate(
              resolved.policy(),
              operation,
              transaction.getAmount(),
              account.getCurrency().getDigitsAfterDecimal(),
              ProductPolicyWorkflow.today(),
              transaction.getTransactionDate());
    String correlation = UUID.randomUUID().toString();
    var evidence =
        new Evidence(
            correlation,
            ThreadLocalContextUtil.getTenant().getTenantIdentifier(),
            actor.getId(),
            account.getId(),
            resolved.productId(),
            resolved.version(),
            operation,
            decision.outcome().name(),
            decision.code(),
            ProductPolicyWorkflow.today(),
            null);
    if (!decision.allowed()) {
      audit.record(evidence);
      log.info(
          "Savings product transaction veto correlation={} operation={} outcome={} reason={}"
              + " policyVersion={}",
          correlation,
          operation,
          decision.outcome(),
          decision.code(),
          resolved.version());
      decision.requireAllowed();
    }
    // This row commits or rolls back with the native command, including both transfer legs.
    // EVALUATED_ALLOW is not a financial receipt; the linked native record is authoritative.
    audit.recordAllowed(
        new Evidence(
            evidence.correlationId(),
            evidence.tenant(),
            evidence.actorId(),
            evidence.accountId(),
            evidence.productId(),
            evidence.policyVersion(),
            evidence.operation(),
            "EVALUATED_ALLOW",
            evidence.code(),
            evidence.businessDate(),
            transaction.getId()));
  }
}
