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
import org.springframework.security.access.AccessDeniedException;

class InvestmentSimulationServiceTest {
  final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
  final InvestmentSimulationAccess access = mock(InvestmentSimulationAccess.class);
  final InvestmentRateService rates = mock(InvestmentRateService.class);
  final InvestmentSimulationAuditService audit = mock(InvestmentSimulationAuditService.class);
  final InvestmentSimulationService service =
      new InvestmentSimulationService(
          security,
          access,
          rates,
          new InvestmentRateTime(),
          new InvestmentSimulationValidation(),
          new InvestmentSimulationCalculator(),
          audit,
          mock(org.springframework.transaction.PlatformTransactionManager.class));

  void deniedEveryOperation() {
    assertThrows(AccessDeniedException.class, service::template);
    assertThrows(AccessDeniedException.class, () -> service.validate("{}"));
    assertThrows(AccessDeniedException.class, () -> service.create("{}"));
    assertThrows(AccessDeniedException.class, () -> service.get("invalid"));
    assertThrows(AccessDeniedException.class, () -> service.history(null, 0, 50));
    verifyNoInteractions(access, rates, audit);
  }

  @AfterEach
  void clear() {
    ThreadLocalContextUtil.reset();
  }

  @Test
  void directServiceCallsRequireAuthentication() {
    when(security.authenticatedUser()).thenThrow(new AccessDeniedException("Unauthenticated"));
    deniedEveryOperation();
  }

  @Test
  void directServiceCallsRequirePermissions() {
    var actor = mock(AppUser.class);
    when(security.authenticatedUser()).thenReturn(actor);
    doThrow(new AccessDeniedException("Denied")).when(actor).validateHasPermissionTo(anyString());
    deniedEveryOperation();
  }

  @Test
  void tenantIsMandatoryForPrivilegedActors() {
    when(security.authenticatedUser()).thenReturn(mock(AppUser.class));
    deniedEveryOperation();
    ThreadLocalContextUtil.setTenant(mock(FineractPlatformTenant.class));
    deniedEveryOperation();
  }
}
