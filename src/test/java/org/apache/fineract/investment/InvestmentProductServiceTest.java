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

class InvestmentProductServiceTest {
  final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final InvestmentProductAccess access = mock(InvestmentProductAccess.class);
  final InvestmentProductService service =
      new InvestmentProductService(security, jdbc, access, new InvestmentProductValidation());

  @AfterEach
  void clear() {
    ThreadLocalContextUtil.reset();
  }

  void deniedEveryOperation() {
    assertThrows(AccessDeniedException.class, service::template);
    assertThrows(AccessDeniedException.class, () -> service.get(1));
    assertThrows(AccessDeniedException.class, () -> service.save(1, "{}"));
    assertThrows(AccessDeniedException.class, () -> service.validate(1, "{}"));
    assertThrows(AccessDeniedException.class, () -> service.history(1, 0, 50));
    verifyNoInteractions(jdbc, access);
  }

  @Test
  void unauthenticatedCallsCannotReachParsingOrStorage() {
    when(security.authenticatedUser()).thenThrow(new AccessDeniedException("Unauthenticated"));
    deniedEveryOperation();
  }

  @Test
  void directServiceCallsCannotBypassPermissions() {
    var actor = mock(AppUser.class);
    when(security.authenticatedUser()).thenReturn(actor);
    doThrow(new AccessDeniedException("Forbidden"))
        .when(actor)
        .validateHasPermissionTo(anyString());
    deniedEveryOperation();
  }

  @Test
  void missingTenantCannotReachAnyStorage() {
    when(security.authenticatedUser()).thenReturn(mock(AppUser.class));
    deniedEveryOperation();
  }

  @Test
  void readPermissionDoesNotAllowValidationOrSaving() {
    var actor = mock(AppUser.class);
    when(security.authenticatedUser()).thenReturn(actor);
    when(actor.getId()).thenReturn(1L);
    ThreadLocalContextUtil.setTenant(mock(FineractPlatformTenant.class));
    doThrow(new AccessDeniedException("Forbidden"))
        .when(actor)
        .validateHasPermissionTo(InvestmentProductService.MANAGE);
    assertTrue(service.template().capabilities().configurationSupported());
    assertThrows(AccessDeniedException.class, () -> service.save(1, "{}"));
    assertThrows(AccessDeniedException.class, () -> service.validate(1, "{}"));
    verifyNoInteractions(jdbc, access);
  }
}
