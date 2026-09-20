/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import org.apache.fineract.creditapplication.data.CreditOriginationStageCode;
import org.apache.fineract.creditapplication.data.CreditOriginationStageEventCommand;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class CreditOriginationStageEventServiceImplTest {

  private NamedParameterJdbcTemplate jdbcTemplate;
  private CreditOriginationStageEventService service;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final Office office = mock(Office.class);
    when(context.authenticatedUser()).thenReturn(user);
    when(user.getOffice()).thenReturn(office);
    when(office.getHierarchy()).thenReturn(".1.");
    service = new CreditOriginationStageEventServiceImpl(jdbcTemplate, context);
  }

  @Test
  void rejectsInvalidStatusBeforeRelationshipLookup() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> service.recordStageEvent(command("MADE_UP")));

    verify(jdbcTemplate, never()).queryForObject(anyString(), anyMap(), eq(Long.class));
  }

  @Test
  void rejectsMismatchedClientAndLoan() {
    when(jdbcTemplate.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);

    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> service.recordStageEvent(command("COMPLETED")));

    verify(jdbcTemplate, never())
        .update(
            anyString(), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class));
  }

  @Test
  void duplicateSourceEventIsAnIdempotentNoOp() {
    when(jdbcTemplate.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(1L, 1L);

    assertFalse(service.recordStageEvent(command("COMPLETED")));

    verify(jdbcTemplate, never())
        .update(
            anyString(), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class));
  }

  private CreditOriginationStageEventCommand command(final String status) {
    return new CreditOriginationStageEventCommand(
        1L,
        2L,
        CreditOriginationStageCode.PARAMETRIC_SCORE,
        status,
        OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        null,
        "SCORING_ENGINE",
        "score-1",
        "event-1",
        null);
  }
}
