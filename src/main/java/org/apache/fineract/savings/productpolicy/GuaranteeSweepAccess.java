/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Authorization is repeated inside services, including jobs and direct Java invocations. */
@Component
@RequiredArgsConstructor
public class GuaranteeSweepAccess {
  public static final String READ = "READ_GUARANTEE_SWEEP";
  public static final String APPLY = "APPLY_GUARANTEE_SWEEP";
  public static final String RELEASE = "RELEASE_GUARANTEE_SWEEP";
  public static final String PROCESS = "ASSESS_GUARANTEE_SWEEP";
  public static final String RECONCILE = "RECONCILE_GUARANTEE_SWEEP";
  private final PlatformSecurityContext security;
  private final JdbcTemplate jdbc;

  public long authorize(String permission) {
    var user = security.authenticatedUser();
    user.validateHasPermissionTo(permission);
    MainAccountAuditService.requireContext();
    if (user.getOffice() == null || user.getOffice().getHierarchy() == null)
      throw new AccessDeniedException("An authorized office-scoped staff principal is required.");
    return user.getId();
  }

  public String hierarchy() {
    return security.authenticatedUser().getOffice().getHierarchy();
  }

  public int client(long id) {
    var rows =
        jdbc.queryForList(
            "SELECT c.status_enum FROM m_client c JOIN m_office o ON o.id=c.office_id"
                + " WHERE c.id=? AND o.hierarchy LIKE ?",
            Integer.class,
            id,
            hierarchy() + "%");
    if (id <= 0 || rows.size() != 1) throw new ClientNotFoundException(id);
    return rows.getFirst();
  }

  static void page(long after, int limit) {
    if (after < 0 || limit < 1 || limit > 100)
      throw ProductPolicyValidation.invalid("pagination", "Use afterId >= 0 and limit 1-100.");
  }
}
