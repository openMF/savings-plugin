/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

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

class ProductPolicyServiceTest {
  final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final ProductPolicyService service =
      new ProductPolicyService(security, jdbc, new ProductPolicyValidation());

  @AfterEach
  void cleanup() {
    ThreadLocalContextUtil.clearTenant();
  }

  @Test
  void missingTenantCannotReachStorage() {
    var actor = mock(AppUser.class);
    doReturn(actor).when(security).authenticatedUser();
    assertThrows(AccessDeniedException.class, () -> service.get(1));
    assertThrows(
        AccessDeniedException.class, () -> service.save(1, ProductPolicyValidationTest.JSON));
    verifyNoInteractions(jdbc);
  }

  @Test
  void authenticationAndAdministrativePermissionsPrecedeParsingAndReads() {
    when(security.authenticatedUser()).thenThrow(new AccessDeniedException("Unauthenticated"));
    assertThrows(AccessDeniedException.class, () -> service.get(1));
    assertThrows(AccessDeniedException.class, () -> service.save(1, "{}"));
    verifyNoInteractions(jdbc);
    var actor = mock(AppUser.class);
    doReturn(actor).when(security).authenticatedUser();
    doThrow(new AccessDeniedException("Forbidden"))
        .when(actor)
        .validateHasPermissionTo(ProductPolicyService.READ);
    doThrow(new AccessDeniedException("Forbidden"))
        .when(actor)
        .validateHasPermissionTo(ProductPolicyService.WRITE);
    assertThrows(AccessDeniedException.class, service::template);
    assertThrows(AccessDeniedException.class, () -> service.get(1));
    assertThrows(AccessDeniedException.class, () -> service.save(1, "{}"));
    verifyNoInteractions(jdbc);
  }

  @Test
  void authorizedCapabilitiesAdvertiseNoFinancialActivation() {
    var actor = mock(AppUser.class);
    doReturn(actor).when(security).authenticatedUser();
    when(actor.getId()).thenReturn(1L);
    ThreadLocalContextUtil.setTenant(mock(FineractPlatformTenant.class));
    assertFalse(service.template().activation());
    verify(actor).validateHasPermissionTo(ProductPolicyService.READ);
    verifyNoInteractions(jdbc);
  }
}
