/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.prospect.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.prospect.data.ProspectSearchRequest;
import org.apache.fineract.prospect.validation.ProspectSearchValidator;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ProspectReadPlatformServiceImplTest {

  @Test
  void searchRequiresReadProspectBeforeValidationOrSql() {
    final NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final ProspectSearchValidator validator = mock(ProspectSearchValidator.class);
    when(context.authenticatedUser()).thenReturn(user);
    doThrow(new RuntimeException("missing permission")).when(user).validateHasReadPermission("PROSPECT");
    final ProspectReadPlatformServiceImpl service =
        new ProspectReadPlatformServiceImpl(
            jdbcTemplate, context, validator, mock(DatabaseSpecificSQLGenerator.class));

    assertThrows(RuntimeException.class, () -> service.search(new ProspectSearchRequest()));

    verify(user).validateHasReadPermission("PROSPECT");
    verifyNoInteractions(jdbcTemplate, validator);
  }
}
