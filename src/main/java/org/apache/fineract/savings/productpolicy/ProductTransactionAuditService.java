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

/** Independent attempt evidence survives financial rollback; it never updates a financial table. */
@Service
@RequiredArgsConstructor
public class ProductTransactionAuditService {
  private final JdbcTemplate jdbc;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(ProductTransactionPolicyData.Evidence e) {
    insert(e);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordAllowed(ProductTransactionPolicyData.Evidence e) {
    insert(e);
  }

  private void insert(ProductTransactionPolicyData.Evidence e) {
    jdbc.update(
        "INSERT INTO m_savings_transaction_policy_audit"
            + "(correlation_id,tenant_identifier,actor_id,savings_account_id,savings_product_id,policy_version,"
            + "operation,outcome,reason_code,business_date,native_transaction_id,recorded_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
        e.correlationId(),
        e.tenant(),
        e.actorId(),
        e.accountId(),
        e.productId(),
        e.policyVersion(),
        e.operation().name(),
        e.outcome(),
        e.code(),
        e.businessDate(),
        e.nativeTransactionId());
  }
}
