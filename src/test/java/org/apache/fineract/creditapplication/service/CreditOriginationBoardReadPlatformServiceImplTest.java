/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class CreditOriginationBoardReadPlatformServiceImplTest {

  private NamedParameterJdbcTemplate jdbcTemplate;
  private AppUser user;
  private CreditOriginationBoardReadPlatformService service;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    user = mock(AppUser.class);
    when(context.authenticatedUser()).thenReturn(user);
    service = new CreditOriginationBoardReadPlatformServiceImpl(jdbcTemplate, context);
  }

  @Test
  void invalidApplicationIdIsRejectedBeforeQuerying() {
    assertThrows(GeneralPlatformDomainRuleException.class, () -> service.retrieve(0L));

    verify(user).validateHasReadPermission("LOAN");
    verify(jdbcTemplate, never()).query(any(String.class), anyMap(), any(RowMapper.class));
  }

  @Test
  void permissionFailureStopsBeforeValidationAndQuerying() {
    doThrow(new SecurityException("denied")).when(user).validateHasReadPermission("LOAN");

    assertThrows(SecurityException.class, () -> service.retrieve(1L));

    verify(jdbcTemplate, never()).query(any(String.class), anyMap(), any(RowMapper.class));
  }
}
