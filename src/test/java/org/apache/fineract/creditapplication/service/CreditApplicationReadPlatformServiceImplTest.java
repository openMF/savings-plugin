/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.fineract.creditapplication.data.CreditApplicationData;
import org.apache.fineract.creditapplication.data.CreditApplicationSearchCriteria;
import org.apache.fineract.creditapplication.data.CreditApplicationSearchRequest;
import org.apache.fineract.creditapplication.validation.CreditApplicationSearchValidator;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class CreditApplicationReadPlatformServiceImplTest {

  private NamedParameterJdbcTemplate jdbcTemplate;
  private PlatformSecurityContext context;
  private AppUser user;
  private CreditApplicationSearchValidator validator;
  private DatabaseSpecificSQLGenerator sqlGenerator;
  private CreditApplicationReadPlatformServiceImpl service;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    context = mock(PlatformSecurityContext.class);
    user = mock(AppUser.class);
    final Office office = mock(Office.class);
    validator = mock(CreditApplicationSearchValidator.class);
    sqlGenerator = mock(DatabaseSpecificSQLGenerator.class);
    when(context.authenticatedUser()).thenReturn(user);
    when(user.getOffice()).thenReturn(office);
    when(office.getHierarchy()).thenReturn(".1.");
    service =
        new CreditApplicationReadPlatformServiceImpl(
            jdbcTemplate, context, validator, sqlGenerator);
  }

  @Test
  void emptyResultIsScopedCountAndDoesNotRunPageQuery() {
    final CreditApplicationSearchRequest request = new CreditApplicationSearchRequest();
    when(validator.validate(request)).thenReturn(criteria());
    when(jdbcTemplate.queryForObject(any(String.class), anyMap(), eq(Long.class))).thenReturn(0L);

    final Page<CreditApplicationData> result = service.search(request);

    assertEquals(0, result.getTotalFilteredRecords());
    assertTrue(result.getPageItems().isEmpty());
    verify(user).validateHasReadPermission("LOAN");
    final ArgumentCaptor<Map<String, Object>> parameters = ArgumentCaptor.forClass(Map.class);
    verify(jdbcTemplate).queryForObject(any(String.class), parameters.capture(), eq(Long.class));
    assertEquals(".1.%", parameters.getValue().get("officeHierarchy"));
  }

  @Test
  void permissionFailureStopsBeforeCountingRows() {
    final CreditApplicationSearchRequest request = new CreditApplicationSearchRequest();
    doThrow(new SecurityException("denied")).when(user).validateHasReadPermission("LOAN");

    org.junit.jupiter.api.Assertions.assertThrows(
        SecurityException.class, () -> service.search(request));

    verify(jdbcTemplate, never()).queryForObject(any(String.class), anyMap(), eq(Long.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void allFiltersAreAppliedBeforeCountAndPageWithStableTieBreaker() {
    final CreditApplicationSearchRequest request = new CreditApplicationSearchRequest();
    final CreditApplicationSearchCriteria criteria =
        new CreditApplicationSearchCriteria(
            1L,
            2L,
            3L,
            4L,
            100,
            LocalDate.parse("2026-09-01"),
            LocalDate.parse("2026-09-05"),
            BigDecimal.TEN,
            BigDecimal.valueOf(20),
            "MXN",
            5L,
            "Central",
            10,
            25,
            "amount",
            "ASC");
    when(validator.validate(request)).thenReturn(criteria);
    when(jdbcTemplate.queryForObject(any(String.class), anyMap(), eq(Long.class))).thenReturn(3L);
    when(sqlGenerator.limit(25, 10)).thenReturn("LIMIT 25 OFFSET 10");
    when(jdbcTemplate.query(any(String.class), anyMap(), any(RowMapper.class)))
        .thenReturn(List.of());

    final Page<CreditApplicationData> result = service.search(request);

    assertEquals(3, result.getTotalFilteredRecords());
    final ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<Map<String, Object>> countParams = ArgumentCaptor.forClass(Map.class);
    verify(jdbcTemplate).queryForObject(countSql.capture(), countParams.capture(), eq(Long.class));
    assertTrue(countSql.getValue().contains("COUNT(*)"));
    assertTrue(countSql.getValue().contains("principal_amount_proposed >= :minAmount"));
    assertTrue(countSql.getValue().contains("submittedon_date <= :submittedTo"));
    assertTrue(countSql.getValue().contains("a2.state_province_id = :stateProvinceId"));
    assertTrue(countSql.getValue().contains("a2.county_district = :municipality"));
    assertTrue(countSql.getValue().contains("ca.id IS NOT NULL"));
    assertEquals(14, countParams.getValue().size());

    final ArgumentCaptor<String> dataSql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).query(dataSql.capture(), anyMap(), any(RowMapper.class));
    assertTrue(dataSql.getValue().contains("ORDER BY l.principal_amount_proposed ASC, l.id ASC"));
    assertTrue(dataSql.getValue().endsWith("LIMIT 25 OFFSET 10"));
  }

  private CreditApplicationSearchCriteria criteria() {
    return new CreditApplicationSearchCriteria(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0,
        50,
        "submittedOnDate",
        "DESC");
  }
}
