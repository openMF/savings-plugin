/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;

class AnpaAccessTest {
  PlatformSecurityContext context;
  AppUser user;
  AnpaAccountingAccess access;

  @BeforeEach
  void setup() {
    context = mock(PlatformSecurityContext.class);
    user = mock(AppUser.class);
    when(context.authenticatedUser()).thenReturn(user);
    access = new AnpaAccountingAccess(context);
    ThreadLocalContextUtil.reset();
  }

  @AfterEach
  void clear() {
    ThreadLocalContextUtil.reset();
  }

  @Test
  void missingTenantIsRejectedEvenWithPermissions() {
    assertThrows(AccessDeniedException.class, () -> access.authorize(AnpaAccountingAccess.READ));
  }

  @Test
  void accountingPermissionIsRequiredInAdditionToLoanRead() {
    doThrow(new AccessDeniedException("No journals"))
        .when(user)
        .validateHasReadPermission("JOURNALENTRY");
    assertThrows(AccessDeniedException.class, () -> access.authorize(AnpaAccountingAccess.PREVIEW));
    verify(user).validateHasReadPermission("LOAN");
  }

  @Test
  void featurePermissionIsNotImpliedByNativePermissions() {
    doThrow(new AccessDeniedException("No preview"))
        .when(user)
        .validateHasPermissionTo(AnpaAccountingAccess.PREVIEW);
    assertThrows(AccessDeniedException.class, () -> access.authorize(AnpaAccountingAccess.PREVIEW));
  }

  @Test
  void hierarchyIsNeverSuppliedByRequest() {
    var office = mock(Office.class);
    when(user.getOffice()).thenReturn(office);
    when(office.getHierarchy()).thenReturn(".1.8.");
    assertEquals(".1.8.%", access.hierarchy());
  }
}
