/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only evidence commits with the diagnostic receipt/checkpoint, never after a failed post.
 */
@Service
@RequiredArgsConstructor
public class GuaranteeSweepAuditService {
  private final JdbcTemplate jdbc;

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      String execution, String event, long actor, GuaranteeSweepData.Decision decision) {
    insert(execution, event, actor, decision, decision.state().name(), null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordReconciliation(
      GuaranteeSweepData.Receipt receipt, GuaranteeSweepData.Reconciliation result, long actor) {
    insert(
        receipt.id(),
        "RECONCILIATION_EVALUATED",
        actor,
        receipt.evaluation(),
        result.status(),
        RatePropagationJson.JSON.toJson(result));
  }

  private void insert(
      String execution,
      String event,
      long actor,
      GuaranteeSweepData.Decision decision,
      String reason,
      String reconciliation) {
    MainAccountAuditService.requireContext();
    jdbc.update(
        "INSERT INTO m_savings_guarantee_audit(execution_id,tenant_identifier,client_id,loan_id,"
            + "savings_account_id,actor_id,business_date,policy_version,event,reason_code,evaluation_json,reconciliation_json,recorded_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
        execution,
        MonthlyFeeAssessmentService.tenant(),
        decision.clientId(),
        decision.loanId(),
        decision.savingsAccountId() == 0 ? null : decision.savingsAccountId(),
        actor,
        ProductPolicyWorkflow.today(),
        decision.policyVersion(),
        event,
        reason,
        RatePropagationJson.JSON.toJson(decision),
        reconciliation);
  }
}
