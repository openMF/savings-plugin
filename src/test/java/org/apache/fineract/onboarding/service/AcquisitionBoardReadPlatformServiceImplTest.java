/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class AcquisitionBoardReadPlatformServiceImplTest {

  @Test
  void permissionFailureStopsBeforeAnyQuery() {
    final NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    when(context.authenticatedUser()).thenReturn(user);
    doThrow(new RuntimeException("missing permission"))
        .when(user)
        .validateHasReadPermission("ENROLLMENT_STATUS");
    final AcquisitionBoardReadPlatformService service =
        new AcquisitionBoardReadPlatformServiceImpl(jdbcTemplate, context);

    assertThrows(RuntimeException.class, () -> service.retrieve(1L, 2L));

    verify(user).validateHasReadPermission("CLIENT");
    verify(user).validateHasReadPermission("savingsaccount");
    verify(user).validateHasReadPermission("ENROLLMENT_STATUS");
    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void invalidIdentifierStopsBeforeAnyQuery() {
    final NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    when(context.authenticatedUser()).thenReturn(mock(AppUser.class));
    final AcquisitionBoardReadPlatformService service =
        new AcquisitionBoardReadPlatformServiceImpl(jdbcTemplate, context);

    assertThrows(RuntimeException.class, () -> service.retrieve(0L, 2L));
    assertThrows(RuntimeException.class, () -> service.retrieve(1L, -2L));

    verifyNoInteractions(jdbcTemplate);
  }
}
