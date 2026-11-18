/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

class InvestmentRateServiceTest {
  final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final InvestmentProductAccess access = mock(InvestmentProductAccess.class);
  final InvestmentRateService service =
      new InvestmentRateService(
          security,
          jdbc,
          access,
          new InvestmentProductValidation(),
          new InvestmentRateValidation(),
          new InvestmentRateTime());

  void deniedEveryOperation() {
    assertThrows(AccessDeniedException.class, () -> service.template(1));
    assertThrows(AccessDeniedException.class, () -> service.draft(1));
    assertThrows(AccessDeniedException.class, () -> service.current(1));
    assertThrows(AccessDeniedException.class, () -> service.save(1, "{}"));
    assertThrows(AccessDeniedException.class, () -> service.validate(1, "{}"));
    assertThrows(AccessDeniedException.class, () -> service.publish(1, "{}"));
    assertThrows(AccessDeniedException.class, () -> service.version(1, 1));
    assertThrows(AccessDeniedException.class, () -> service.versions(1, false, 0, 50));
    assertThrows(AccessDeniedException.class, () -> service.history(1, 0, 50));
    assertThrows(AccessDeniedException.class, () -> service.selection(1, "1000", 90, null, null));
    verifyNoInteractions(jdbc, access);
  }

  @AfterEach
  void clear() {
    ThreadLocalContextUtil.reset();
  }

  @Test
  void unauthenticatedDirectServiceCallsCannotReachStorage() {
    when(security.authenticatedUser()).thenThrow(new AccessDeniedException("Unauthenticated"));
    deniedEveryOperation();
  }

  @Test
  void directServiceCallsCannotBypassPermissions() {
    var actor = mock(AppUser.class);
    when(security.authenticatedUser()).thenReturn(actor);
    ThreadLocalContextUtil.setTenant(mock(FineractPlatformTenant.class));
    doThrow(new AccessDeniedException("Forbidden"))
        .when(actor)
        .validateHasPermissionTo(anyString());
    deniedEveryOperation();
  }

  @Test
  void tenantIsMandatoryEvenForPrivilegedUsers() {
    when(security.authenticatedUser()).thenReturn(mock(AppUser.class));
    deniedEveryOperation();
  }
}
