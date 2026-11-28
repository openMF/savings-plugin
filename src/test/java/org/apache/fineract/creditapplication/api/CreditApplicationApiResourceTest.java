/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.creditapplication.data.CreditApplicationData;
import org.apache.fineract.creditapplication.data.CreditApplicationSearchRequest;
import org.apache.fineract.creditapplication.data.CreditOriginationBoardData;
import org.apache.fineract.creditapplication.data.CreditOriginationDashboardData;
import org.apache.fineract.creditapplication.data.CreditOriginationDashboardRequest;
import org.apache.fineract.creditapplication.service.CreditApplicationReadPlatformService;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardReadPlatformService;
import org.apache.fineract.creditapplication.service.CreditOriginationDashboardReadPlatformService;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;

class CreditApplicationApiResourceTest {

  @Test
  void searchRequiresLoanReadPermissionAndDelegates() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final CreditApplicationReadPlatformService service =
        mock(CreditApplicationReadPlatformService.class);
    final CreditOriginationBoardReadPlatformService boardService =
        mock(CreditOriginationBoardReadPlatformService.class);
    final CreditApplicationSearchRequest request = new CreditApplicationSearchRequest();
    final Page<CreditApplicationData> page = new Page<>(List.of(), 0);
    when(context.authenticatedUser()).thenReturn(user);
    when(service.search(request)).thenReturn(page);
    final CreditApplicationApiResource resource =
        new CreditApplicationApiResource(
            context,
            service,
            boardService,
            mock(CreditOriginationDashboardReadPlatformService.class));

    assertSame(page, resource.search(request));

    verify(user).validateHasReadPermission("LOAN");
    verify(service).search(request);
  }

  @Test
  void originationBoardRequiresLoanReadPermissionAndDelegates() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final CreditApplicationReadPlatformService searchService =
        mock(CreditApplicationReadPlatformService.class);
    final CreditOriginationBoardReadPlatformService boardService =
        mock(CreditOriginationBoardReadPlatformService.class);
    final CreditOriginationBoardData board =
        new CreditOriginationBoardData(
            7L, 8L, 7L, null, "ONBOARDING", List.of(), 1L, 2L, null, null, null);
    when(context.authenticatedUser()).thenReturn(user);
    when(boardService.retrieve(7L)).thenReturn(board);
    final CreditApplicationApiResource resource =
        new CreditApplicationApiResource(
            context,
            searchService,
            boardService,
            mock(CreditOriginationDashboardReadPlatformService.class));

    assertSame(board, resource.retrieveOriginationBoard(7L));

    verify(user).validateHasReadPermission("LOAN");
    verify(boardService).retrieve(7L);
  }

  @Test
  void originationDashboardRequiresLoanReadPermissionBeforeDelegating() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final CreditOriginationDashboardReadPlatformService dashboardService =
        mock(CreditOriginationDashboardReadPlatformService.class);
    final CreditOriginationDashboardRequest request = new CreditOriginationDashboardRequest();
    final CreditOriginationDashboardData dashboard =
        new CreditOriginationDashboardData(
            null, 7, "CALENDAR_DAYS", null, null, null, List.of(), new Page<>(List.of(), 0));
    when(context.authenticatedUser()).thenReturn(user);
    when(dashboardService.retrieve(request)).thenReturn(dashboard);
    final CreditApplicationApiResource resource =
        new CreditApplicationApiResource(
            context,
            mock(CreditApplicationReadPlatformService.class),
            mock(CreditOriginationBoardReadPlatformService.class),
            dashboardService);

    assertSame(dashboard, resource.retrieveOriginationDashboard(request));
    verify(user).validateHasReadPermission("LOAN");

    doThrow(new SecurityException("denied")).when(user).validateHasReadPermission("LOAN");
    final CreditOriginationDashboardReadPlatformService untouched =
        mock(CreditOriginationDashboardReadPlatformService.class);
    final CreditApplicationApiResource denied =
        new CreditApplicationApiResource(
            context,
            mock(CreditApplicationReadPlatformService.class),
            mock(CreditOriginationBoardReadPlatformService.class),
            untouched);
    assertThrows(SecurityException.class, () -> denied.retrieveOriginationDashboard(request));
    verify(untouched, never()).retrieve(any());
  }
}
