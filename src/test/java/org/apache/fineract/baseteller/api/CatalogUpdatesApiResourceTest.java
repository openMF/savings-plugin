/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.baseteller.data.CatalogUpdateCategory;
import org.apache.fineract.baseteller.data.CatalogUpdateData;
import org.apache.fineract.baseteller.data.CatalogUpdateStatus;
import org.apache.fineract.baseteller.service.CatalogUpdatePlatformService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;

class CatalogUpdatesApiResourceTest {

  @Test
  void getAllRequiresReadPermission() {
    final Fixture fixture = fixture();
    final List<CatalogUpdateData> expected = List.of(status(CatalogUpdateCategory.GENERAL));
    when(fixture.service.retrieveAll()).thenReturn(expected);

    assertSame(expected, fixture.resource.retrieveAll());
    verify(fixture.user).validateHasReadPermission("BASE_TELLER_CATALOG_UPDATE");
  }

  @Test
  void getOneParsesCategoryAndRequiresReadPermission() {
    final Fixture fixture = fixture();
    final CatalogUpdateData expected = status(CatalogUpdateCategory.ACCOUNTING);
    when(fixture.service.retrieve(CatalogUpdateCategory.ACCOUNTING)).thenReturn(expected);

    assertSame(expected, fixture.resource.retrieve("accounting"));
    verify(fixture.user).validateHasReadPermission("BASE_TELLER_CATALOG_UPDATE");
  }

  @Test
  void synchronizeRequiresUpdatePermission() {
    final Fixture fixture = fixture();
    final CatalogUpdateData expected = status(CatalogUpdateCategory.USERS);
    when(fixture.service.synchronize(CatalogUpdateCategory.USERS)).thenReturn(expected);

    assertSame(expected, fixture.resource.synchronize("USERS"));
    verify(fixture.user).validateHasUpdatePermission("BASE_TELLER_CATALOG_UPDATE");
  }

  private static CatalogUpdateData status(final CatalogUpdateCategory category) {
    return new CatalogUpdateData(
        category, null, null, true, CatalogUpdateStatus.UPDATE_REQUIRED, null);
  }

  private static Fixture fixture() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final CatalogUpdatePlatformService service = mock(CatalogUpdatePlatformService.class);
    when(context.authenticatedUser()).thenReturn(user);
    return new Fixture(user, service, new CatalogUpdatesApiResource(context, service));
  }

  private record Fixture(
      AppUser user, CatalogUpdatePlatformService service, CatalogUpdatesApiResource resource) {}
}
