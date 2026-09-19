/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.onboarding.data.EnrollmentCasesRequest;
import org.apache.fineract.onboarding.service.AcquisitionBoardReadPlatformService;
import org.apache.fineract.onboarding.service.EnrollmentStatusReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;

class OnboardingCasesApiResourceTest {

  @Test
  void retrieveEnrollmentCasesRequiresReadClientAndEnrollmentStatusPermissionsAndDelegates() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final EnrollmentStatusReadPlatformService service =
        mock(EnrollmentStatusReadPlatformService.class);
    final AcquisitionBoardReadPlatformService acquisitionService =
        mock(AcquisitionBoardReadPlatformService.class);
    final EnrollmentCasesRequest request = new EnrollmentCasesRequest();
    when(context.authenticatedUser()).thenReturn(user);
    when(service.retrieveEnrollmentCases(request)).thenReturn(new Page<>(java.util.List.of(), 0));

    new OnboardingCasesApiResource(context, service, acquisitionService)
        .retrieveEnrollmentCases(request);

    verify(user).validateHasReadPermission("CLIENT");
    verify(user).validateHasReadPermission("ENROLLMENT_STATUS");
    verify(service).retrieveEnrollmentCases(request);
  }

  @Test
  void retrieveAcquisitionBoardRequiresAllReadPermissionsAndDelegates() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final EnrollmentStatusReadPlatformService enrollmentService =
        mock(EnrollmentStatusReadPlatformService.class);
    final AcquisitionBoardReadPlatformService acquisitionService =
        mock(AcquisitionBoardReadPlatformService.class);
    when(context.authenticatedUser()).thenReturn(user);

    new OnboardingCasesApiResource(context, enrollmentService, acquisitionService)
        .retrieveAcquisitionBoard(17L, 29L);

    verify(user).validateHasReadPermission("CLIENT");
    verify(user).validateHasReadPermission("savingsaccount");
    verify(user).validateHasReadPermission("ENROLLMENT_STATUS");
    verify(acquisitionService).retrieve(17L, 29L);
  }
}
