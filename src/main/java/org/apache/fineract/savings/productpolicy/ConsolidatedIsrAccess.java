/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Tenant-routed native reads require an explicitly scoped staff principal on every entry point. */
@Component
@RequiredArgsConstructor
public class ConsolidatedIsrAccess {
  public static final String ELIGIBILITY = "READ_CONSOLIDATED_ISR_ELIGIBILITY";
  public static final String READ = "READ_CONSOLIDATED_ISR";
  public static final String PREVIEW = "PREVIEW_CONSOLIDATED_ISR";
  public static final String RULES = "MANAGE_CONSOLIDATED_ISR_RULE";
  public static final String AUDIT = "READ_CONSOLIDATED_ISR_AUDIT";
  public static final String RECONCILE = "RECONCILE_CONSOLIDATED_ISR";
  private final PlatformSecurityContext security;
  private final JdbcTemplate jdbc;

  long authorize(String permission) {
    var user = security.authenticatedUser();
    user.validateHasPermissionTo(permission);
    MainAccountAuditService.requireContext();
    if (user.getOffice() == null || user.getOffice().getHierarchy() == null)
      throw new AccessDeniedException("Office-scoped staff principal required.");
    return user.getId();
  }

  String hierarchy() {
    return security.authenticatedUser().getOffice().getHierarchy() + "%";
  }

  String tenant() {
    return ThreadLocalContextUtil.getTenant().getTenantIdentifier();
  }

  void snapshot(ConsolidatedIsrData.Calculation value) {
    for (var account : value.result().sources().accounts()) {
      if (jdbc.queryForObject(
              "SELECT COUNT(*) FROM m_savings_account a JOIN m_client c ON c.id=a.client_id JOIN m_office o ON o.id=c.office_id"
                  + " WHERE a.id=? AND a.client_id=? AND o.hierarchy LIKE ?",
              Long.class,
              account.accountId(),
              value.clientId(),
              hierarchy())
          != 1)
        throw new AccessDeniedException(
            "Assessment source accounts are outside the current office scope.");
    }
  }

  Map<String, Object> client(long client, boolean lock) {
    var rows =
        jdbc.queryForList(
            "SELECT c.id,c.legal_form_enum,c.status_enum FROM m_client c"
                + " JOIN m_office o ON o.id=c.office_id WHERE c.id=? AND o.hierarchy LIKE ?"
                + (lock ? " FOR UPDATE OF c" : ""),
            client,
            hierarchy());
    if (client <= 0 || rows.size() != 1) throw new ClientNotFoundException(client);
    return rows.getFirst();
  }
}
