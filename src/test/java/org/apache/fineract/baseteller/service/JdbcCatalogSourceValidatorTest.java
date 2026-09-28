/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.baseteller.data.CatalogUpdateCategory;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcCatalogSourceValidatorTest {

  @Test
  void validatesGeneralSources() {
    final NamedParameterJdbcTemplate jdbc = successfulJdbc();

    new JdbcCatalogSourceValidator(jdbc)
        .validate(CatalogUpdateCategory.GENERAL, 1L, ".", 7L);

    verify(jdbc, times(9)).queryForObject(anyString(), anyMap(), eq(Long.class));
  }

  @Test
  void validatesAccountingSources() {
    final NamedParameterJdbcTemplate jdbc = successfulJdbc();

    new JdbcCatalogSourceValidator(jdbc)
        .validate(CatalogUpdateCategory.ACCOUNTING, 1L, ".", 7L);

    verify(jdbc, times(5)).queryForObject(anyString(), anyMap(), eq(Long.class));
  }

  @Test
  void validatesUserSecurityAndAssignmentSources() {
    final NamedParameterJdbcTemplate jdbc = successfulJdbc();

    new JdbcCatalogSourceValidator(jdbc)
        .validate(CatalogUpdateCategory.USERS, 1L, ".", 7L);

    verify(jdbc, times(6)).queryForObject(anyString(), anyMap(), eq(Long.class));
  }

  @Test
  void rejectsAuthenticatedUserOutsideOfficeScope() {
    final NamedParameterJdbcTemplate jdbc = successfulJdbc();
    when(jdbc.queryForObject(
            org.mockito.ArgumentMatchers.contains("u.id=:userId"), anyMap(), eq(Long.class)))
        .thenReturn(0L);

    assertThrows(
        PlatformDataIntegrityException.class,
        () ->
            new JdbcCatalogSourceValidator(jdbc)
                .validate(CatalogUpdateCategory.USERS, 1L, ".", 7L));
  }

  private static NamedParameterJdbcTemplate successfulJdbc() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(1L);
    return jdbc;
  }
}
