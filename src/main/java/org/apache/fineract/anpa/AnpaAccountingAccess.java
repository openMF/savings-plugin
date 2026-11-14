/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Authentication precedes every tenant-routed read, including idempotent replay and audit reads.
 */
@Component
@RequiredArgsConstructor
public class AnpaAccountingAccess {
  public static final String READ = "READ_ANPA_ACCOUNTING";
  public static final String PREVIEW = "PREVIEW_ANPA_WRITEOFF";
  public static final String CREATE = "CREATE_ANPA_ASSESSMENT";
  public static final String AUDIT = "READ_ANPA_AUDIT";
  private final PlatformSecurityContext security;

  long authorize(String permission) {
    var user = security.authenticatedUser();
    user.validateHasPermissionTo(permission);
    user.validateHasReadPermission("LOAN");
    user.validateHasReadPermission("GLACCOUNT");
    user.validateHasReadPermission("JOURNALENTRY");
    if (ThreadLocalContextUtil.getTenant() == null
        || DateUtils.getBusinessLocalDate() == null
        || user.getOffice() == null
        || user.getOffice().getHierarchy() == null)
      throw new AccessDeniedException(
          "Authenticated tenant, business date and office scope required.");
    return user.getId();
  }

  String tenant() {
    return ThreadLocalContextUtil.getTenant().getTenantIdentifier();
  }

  String hierarchy() {
    return security.authenticatedUser().getOffice().getHierarchy() + "%";
  }
}
