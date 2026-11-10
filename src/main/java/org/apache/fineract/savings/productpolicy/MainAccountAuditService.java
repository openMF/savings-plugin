/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Evidence commits atomically with native policy opening or explicit relationship binding. */
@Service
@RequiredArgsConstructor
public class MainAccountAuditService {
  private final JdbcTemplate jdbc;

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(long client, long account, long actor, String action) {
    requireContext();
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_main_account_audit WHERE savings_account_id=? AND action=?",
            Long.class,
            account,
            action)
        != 0) return;
    if (jdbc.update(
            "INSERT INTO m_savings_main_account_audit(client_id,savings_account_id,savings_product_id,"
                + "policy_version,action,actor_id,tenant_identifier,business_date,recorded_at)"
                + " SELECT ?,b.savings_account_id,b.savings_product_id,b.version,?,?,?,?,?"
                + " FROM m_savings_account_policy b JOIN m_savings_account a ON a.id=b.savings_account_id"
                + " WHERE b.savings_account_id=? AND b.purpose='MAIN' AND a.client_id=?",
            client,
            action,
            actor,
            ThreadLocalContextUtil.getTenant().getTenantIdentifier(),
            ProductPolicyWorkflow.today(),
            DateUtils.getAuditLocalDateTime(),
            account,
            client)
        != 1)
      throw ProductPolicyValidation.invalid(
          "policyVersion", "Main Account binding is missing; no relationship changed.");
  }

  static void requireContext() {
    if (ThreadLocalContextUtil.getTenant() == null)
      throw new org.springframework.security.access.AccessDeniedException(
          "Authenticated tenant required.");
    try {
      if (ProductPolicyWorkflow.today() == null) throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Initialized business date required.");
    }
  }
}
