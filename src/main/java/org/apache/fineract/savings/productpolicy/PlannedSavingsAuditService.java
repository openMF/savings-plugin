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

/** Append-only configuration/review evidence, atomic with the metadata transaction. */
@Service
@RequiredArgsConstructor
public class PlannedSavingsAuditService {
  private final JdbcTemplate jdbc;

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      long client,
      String type,
      String id,
      Long account,
      long product,
      long version,
      long actor,
      String action,
      String outcome,
      Object details) {
    MainAccountAuditService.requireContext();
    jdbc.update(
        "INSERT INTO m_savings_planned_audit(client_id,subject_type,subject_id,savings_account_id,"
            + "savings_product_id,policy_version,actor_id,tenant_identifier,action,outcome,business_date,recorded_at,details_json)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        client,
        type,
        id,
        account,
        product,
        version,
        actor,
        ThreadLocalContextUtil.getTenant().getTenantIdentifier(),
        action,
        outcome,
        ProductPolicyWorkflow.today(),
        DateUtils.getAuditLocalDateTime(),
        RatePropagationJson.JSON.toJson(details));
  }
}
