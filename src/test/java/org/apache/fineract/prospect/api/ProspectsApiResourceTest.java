/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.prospect.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.prospect.data.ProspectData;
import org.apache.fineract.prospect.data.ProspectSearchRequest;
import org.apache.fineract.prospect.service.ProspectReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;

class ProspectsApiResourceTest {

  @Test
  void searchRequiresProspectReadPermissionAndDelegates() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final ProspectReadPlatformService service = mock(ProspectReadPlatformService.class);
    final ProspectSearchRequest request = new ProspectSearchRequest();
    final Page<ProspectData> page = new Page<>(List.of(), 0);
    when(context.authenticatedUser()).thenReturn(user);
    when(service.search(request)).thenReturn(page);

    assertSame(page, new ProspectsApiResource(context, service).search(request));

    verify(user).validateHasReadPermission("PROSPECT");
    verify(service).search(request);
  }
}
