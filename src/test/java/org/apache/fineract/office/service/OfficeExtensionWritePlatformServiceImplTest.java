/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.exception.OfficeNotFoundException;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

@ExtendWith(MockitoExtension.class)
class OfficeExtensionWritePlatformServiceImplTest {

  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private NamedParameterJdbcTemplate namedJdbcTemplate;
  @Mock private PlatformSecurityContext context;
  @Mock private FromJsonHelper fromJsonHelper;

  private OfficeExtensionWritePlatformServiceImpl service;

  private static final Long OFFICE_ID = 1L;
  private static final Long SERVICE_ID = 10L;

  @BeforeEach
  void setUp() {
    service =
        new OfficeExtensionWritePlatformServiceImpl(
            jdbcTemplate, namedJdbcTemplate, context, fromJsonHelper);
  }

  private void mockAuth() {
    AppUser user = mock(AppUser.class);
    when(context.authenticatedUser()).thenReturn(user);
  }

  @Test
  void createOfficeService_validOffice_returnsResult() {
    mockAuth();
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(OFFICE_ID))).thenReturn(1);

    String json = "{\"serviceName\":\"Loans\",\"workingHours\":\"Mon-Fri\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.extractStringNamed("serviceName", element)).thenReturn("Loans");
    when(fromJsonHelper.extractStringNamed("serviceExternalId", element)).thenReturn(null);
    when(fromJsonHelper.extractStringNamed("workingHours", element)).thenReturn("Mon-Fri");
    when(namedJdbcTemplate.update(
            anyString(),
            any(MapSqlParameterSource.class),
            any(org.springframework.jdbc.support.GeneratedKeyHolder.class),
            any(String[].class)))
        .thenAnswer(
            invocation -> {
              org.springframework.jdbc.support.GeneratedKeyHolder kh = invocation.getArgument(2);
              var keyMap = new java.util.HashMap<String, Object>();
              keyMap.put("id", 42L);
              kh.getKeyList().add(keyMap);
              return 1;
            });

    CommandProcessingResult result = service.createOfficeService(OFFICE_ID, json);

    assertNotNull(result);
    assertEquals(42L, result.getResourceId());
  }

  @Test
  void createOfficeService_nonExistentOffice_throws() {
    mockAuth();
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(999L)))
        .thenThrow(new EmptyResultDataAccessException(1));

    assertThrows(OfficeNotFoundException.class, () -> service.createOfficeService(999L, "{}"));
  }

  @Test
  void createOfficeService_nullServiceName_throws() {
    mockAuth();
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(OFFICE_ID))).thenReturn(1);

    String json = "{\"workingHours\":\"Mon-Fri\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.extractStringNamed("serviceName", element)).thenReturn(null);

    assertThrows(
        PlatformApiDataValidationException.class, () -> service.createOfficeService(OFFICE_ID, json));
    verify(namedJdbcTemplate, never())
        .update(
            anyString(),
            any(MapSqlParameterSource.class),
            any(org.springframework.jdbc.support.GeneratedKeyHolder.class),
            any(String[].class));
  }

  @Test
  void createOfficeService_blankServiceName_throws() {
    mockAuth();
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(OFFICE_ID))).thenReturn(1);

    String json = "{\"serviceName\":\"   \",\"workingHours\":\"Mon-Fri\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.extractStringNamed("serviceName", element)).thenReturn("   ");

    assertThrows(
        PlatformApiDataValidationException.class, () -> service.createOfficeService(OFFICE_ID, json));
    verify(namedJdbcTemplate, never())
        .update(
            anyString(),
            any(MapSqlParameterSource.class),
            any(org.springframework.jdbc.support.GeneratedKeyHolder.class),
            any(String[].class));
  }

  @Test
  void createOfficeService_serviceNameTooLong_throws() {
    mockAuth();
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(OFFICE_ID))).thenReturn(1);

    final String serviceName = "A".repeat(256);
    String json = "{\"serviceName\":\"" + serviceName + "\",\"workingHours\":\"Mon-Fri\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.extractStringNamed("serviceName", element)).thenReturn(serviceName);

    assertThrows(
        PlatformApiDataValidationException.class, () -> service.createOfficeService(OFFICE_ID, json));
    verify(namedJdbcTemplate, never())
        .update(
            anyString(),
            any(MapSqlParameterSource.class),
            any(org.springframework.jdbc.support.GeneratedKeyHolder.class),
            any(String[].class));
  }

  @Test
  void updateOfficeService_withServiceName_updatesAndReturnsChanges() {
    mockAuth();

    String json = "{\"serviceName\":\"Updated\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.parameterExists("serviceName", element)).thenReturn(true);
    when(fromJsonHelper.extractStringNamed("serviceName", element)).thenReturn("Updated");
    when(fromJsonHelper.parameterExists("serviceExternalId", element)).thenReturn(false);
    when(fromJsonHelper.parameterExists("workingHours", element)).thenReturn(false);
    when(namedJdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

    CommandProcessingResult result = service.updateOfficeService(OFFICE_ID, SERVICE_ID, json);

    assertNotNull(result);
    verify(namedJdbcTemplate).update(anyString(), any(MapSqlParameterSource.class));
  }

  @Test
  void updateOfficeService_withAllFields_updatesAll() {
    mockAuth();

    String json = "{\"serviceName\":\"A\",\"serviceExternalId\":\"B\",\"workingHours\":\"C\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.parameterExists("serviceName", element)).thenReturn(true);
    when(fromJsonHelper.extractStringNamed("serviceName", element)).thenReturn("A");
    when(fromJsonHelper.parameterExists("serviceExternalId", element)).thenReturn(true);
    when(fromJsonHelper.extractStringNamed("serviceExternalId", element)).thenReturn("B");
    when(fromJsonHelper.parameterExists("workingHours", element)).thenReturn(true);
    when(fromJsonHelper.extractStringNamed("workingHours", element)).thenReturn("C");
    when(namedJdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

    CommandProcessingResult result = service.updateOfficeService(OFFICE_ID, SERVICE_ID, json);

    assertNotNull(result);
    assertEquals(3, result.getChanges().size());
  }

  @Test
  void updateOfficeService_withServiceExternalIdOnly_updatesAndReturnsChanges() {
    mockAuth();

    String json = "{\"serviceExternalId\":\"external-1\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.parameterExists("serviceName", element)).thenReturn(false);
    when(fromJsonHelper.parameterExists("serviceExternalId", element)).thenReturn(true);
    when(fromJsonHelper.extractStringNamed("serviceExternalId", element)).thenReturn("external-1");
    when(fromJsonHelper.parameterExists("workingHours", element)).thenReturn(false);
    when(namedJdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

    CommandProcessingResult result = service.updateOfficeService(OFFICE_ID, SERVICE_ID, json);

    assertNotNull(result);
    assertEquals(1, result.getChanges().size());
    assertEquals("external-1", result.getChanges().get("serviceExternalId"));
  }

  @Test
  void updateOfficeService_withWorkingHoursOnly_updatesAndReturnsChanges() {
    mockAuth();

    String json = "{\"workingHours\":\"Mon-Fri\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.parameterExists("serviceName", element)).thenReturn(false);
    when(fromJsonHelper.parameterExists("serviceExternalId", element)).thenReturn(false);
    when(fromJsonHelper.parameterExists("workingHours", element)).thenReturn(true);
    when(fromJsonHelper.extractStringNamed("workingHours", element)).thenReturn("Mon-Fri");
    when(namedJdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

    CommandProcessingResult result = service.updateOfficeService(OFFICE_ID, SERVICE_ID, json);

    assertNotNull(result);
    assertEquals(1, result.getChanges().size());
    assertEquals("Mon-Fri", result.getChanges().get("workingHours"));
  }

  @Test
  void updateOfficeService_blankServiceName_throws() {
    mockAuth();

    String json = "{\"serviceName\":\" \"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.parameterExists("serviceName", element)).thenReturn(true);
    when(fromJsonHelper.extractStringNamed("serviceName", element)).thenReturn(" ");

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.updateOfficeService(OFFICE_ID, SERVICE_ID, json));
    verify(namedJdbcTemplate, never()).update(anyString(), any(MapSqlParameterSource.class));
  }

  @Test
  void updateOfficeService_serviceNameTooLong_throws() {
    mockAuth();

    final String serviceName = "A".repeat(256);
    String json = "{\"serviceName\":\"" + serviceName + "\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.parameterExists("serviceName", element)).thenReturn(true);
    when(fromJsonHelper.extractStringNamed("serviceName", element)).thenReturn(serviceName);

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.updateOfficeService(OFFICE_ID, SERVICE_ID, json));
    verify(namedJdbcTemplate, never()).update(anyString(), any(MapSqlParameterSource.class));
  }

  @Test
  void updateOfficeService_zeroAffectedRows_throws() {
    mockAuth();

    String json = "{\"serviceName\":\"Ghost\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.parameterExists("serviceName", element)).thenReturn(true);
    when(fromJsonHelper.extractStringNamed("serviceName", element)).thenReturn("Ghost");
    when(fromJsonHelper.parameterExists("serviceExternalId", element)).thenReturn(false);
    when(fromJsonHelper.parameterExists("workingHours", element)).thenReturn(false);
    when(namedJdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);

    assertThrows(
        PlatformDataIntegrityException.class,
        () -> service.updateOfficeService(OFFICE_ID, SERVICE_ID, json));
  }

  @Test
  void updateOfficeService_noChanges_skipsUpdate() {
    mockAuth();

    String json = "{}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.parameterExists("serviceName", element)).thenReturn(false);
    when(fromJsonHelper.parameterExists("serviceExternalId", element)).thenReturn(false);
    when(fromJsonHelper.parameterExists("workingHours", element)).thenReturn(false);

    CommandProcessingResult result = service.updateOfficeService(OFFICE_ID, SERVICE_ID, json);

    assertNotNull(result);
    verify(namedJdbcTemplate, never()).update(anyString(), any(MapSqlParameterSource.class));
  }

  @Test
  void deleteOfficeService_existingService_succeeds() {
    mockAuth();
    when(jdbcTemplate.update(anyString(), eq(SERVICE_ID), eq(OFFICE_ID))).thenReturn(1);

    CommandProcessingResult result = service.deleteOfficeService(OFFICE_ID, SERVICE_ID);

    assertNotNull(result);
  }

  @Test
  void deleteOfficeService_nonExistent_throws() {
    mockAuth();
    when(jdbcTemplate.update(anyString(), eq(SERVICE_ID), eq(OFFICE_ID))).thenReturn(0);

    assertThrows(
        PlatformDataIntegrityException.class,
        () -> service.deleteOfficeService(OFFICE_ID, SERVICE_ID));
  }

  @Test
  void saveOfficeGeolocation_validCoordinates_succeeds() {
    mockAuth();
    when(jdbcTemplate.queryForObject(
            eq("SELECT 1 FROM m_office WHERE id = ?"), eq(Integer.class), eq(OFFICE_ID)))
        .thenReturn(1);
    when(jdbcTemplate.queryForObject(
            eq("SELECT id FROM m_selfservice_office_geolocation WHERE office_id = ?"),
            eq(Long.class),
            eq(OFFICE_ID)))
        .thenReturn(5L);

    String json = "{\"latitude\":\"19.43\",\"longitude\":\"-99.13\",\"locale\":\"en\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.extractBigDecimalWithLocaleNamed("latitude", element))
        .thenReturn(new java.math.BigDecimal("19.43"));
    when(fromJsonHelper.extractBigDecimalWithLocaleNamed("longitude", element))
        .thenReturn(new java.math.BigDecimal("-99.13"));

    CommandProcessingResult result = service.saveOfficeGeolocation(OFFICE_ID, json);

    assertNotNull(result);
    assertEquals(5L, result.getResourceId());
  }

  @Test
  void saveOfficeGeolocation_nullLatitude_throws() {
    mockAuth();
    when(jdbcTemplate.queryForObject(
            eq("SELECT 1 FROM m_office WHERE id = ?"), eq(Integer.class), eq(OFFICE_ID)))
        .thenReturn(1);

    String json = "{\"longitude\":\"-99.13\",\"locale\":\"en\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.extractBigDecimalWithLocaleNamed("latitude", element)).thenReturn(null);
    when(fromJsonHelper.extractBigDecimalWithLocaleNamed("longitude", element))
        .thenReturn(new java.math.BigDecimal("-99.13"));

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.saveOfficeGeolocation(OFFICE_ID, json));
  }

  @Test
  void saveOfficeGeolocation_outOfRangeLatitude_throws() {
    mockAuth();
    when(jdbcTemplate.queryForObject(
            eq("SELECT 1 FROM m_office WHERE id = ?"), eq(Integer.class), eq(OFFICE_ID)))
        .thenReturn(1);

    String json = "{\"latitude\":\"95.0\",\"longitude\":\"-99.13\",\"locale\":\"en\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.extractBigDecimalWithLocaleNamed("latitude", element))
        .thenReturn(new java.math.BigDecimal("95.0"));
    when(fromJsonHelper.extractBigDecimalWithLocaleNamed("longitude", element))
        .thenReturn(new java.math.BigDecimal("-99.13"));

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.saveOfficeGeolocation(OFFICE_ID, json));
  }

  @Test
  void saveOfficeGeolocation_longitudeAboveMaximum_throws() {
    mockAuth();
    when(jdbcTemplate.queryForObject(
            eq("SELECT 1 FROM m_office WHERE id = ?"), eq(Integer.class), eq(OFFICE_ID)))
        .thenReturn(1);

    String json = "{\"latitude\":\"19.43\",\"longitude\":\"181.0\",\"locale\":\"en\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.extractBigDecimalWithLocaleNamed("latitude", element))
        .thenReturn(new java.math.BigDecimal("19.43"));
    when(fromJsonHelper.extractBigDecimalWithLocaleNamed("longitude", element))
        .thenReturn(new java.math.BigDecimal("181.0"));

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.saveOfficeGeolocation(OFFICE_ID, json));
  }

  @Test
  void saveOfficeGeolocation_longitudeBelowMinimum_throws() {
    mockAuth();
    when(jdbcTemplate.queryForObject(
            eq("SELECT 1 FROM m_office WHERE id = ?"), eq(Integer.class), eq(OFFICE_ID)))
        .thenReturn(1);

    String json = "{\"latitude\":\"19.43\",\"longitude\":\"-181.0\",\"locale\":\"en\"}";
    var element = new com.google.gson.JsonParser().parse(json);
    when(fromJsonHelper.parse(json)).thenReturn(element);
    when(fromJsonHelper.extractBigDecimalWithLocaleNamed("latitude", element))
        .thenReturn(new java.math.BigDecimal("19.43"));
    when(fromJsonHelper.extractBigDecimalWithLocaleNamed("longitude", element))
        .thenReturn(new java.math.BigDecimal("-181.0"));

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.saveOfficeGeolocation(OFFICE_ID, json));
  }

  @Test
  void deleteOfficeGeolocation_delegatesToJdbc() {
    mockAuth();
    service.deleteOfficeGeolocation(OFFICE_ID);
    verify(jdbcTemplate)
        .update("DELETE FROM m_selfservice_office_geolocation WHERE office_id = ?", OFFICE_ID);
  }

  @Test
  void saveOfficeWorkingHours_validFullWeek_replacesSchedule() {
    mockAuth();
    OfficeExtensionWritePlatformServiceImpl scheduleService = serviceWithRealJsonHelper();
    when(jdbcTemplate.queryForObject(
            eq("SELECT 1 FROM m_office WHERE id = ?"), eq(Integer.class), eq(OFFICE_ID)))
        .thenReturn(1);
    when(jdbcTemplate.update(
            eq("DELETE FROM m_selfservice_office_working_hours WHERE office_id = ?"),
            eq(OFFICE_ID)))
        .thenReturn(7);
    when(namedJdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class)))
        .thenReturn(new int[] {1, 1, 1, 1, 1, 1, 1});

    CommandProcessingResult result =
        scheduleService.saveOfficeWorkingHours(OFFICE_ID, validWeeklyScheduleJson());

    assertNotNull(result);
    assertEquals(OFFICE_ID, result.getOfficeId());
    verify(jdbcTemplate)
        .update("DELETE FROM m_selfservice_office_working_hours WHERE office_id = ?", OFFICE_ID);
    verify(namedJdbcTemplate).batchUpdate(anyString(), any(SqlParameterSource[].class));
  }

  @Test
  void saveOfficeWorkingHours_disabledDayMayOmitTimes() {
    mockAuth();
    OfficeExtensionWritePlatformServiceImpl scheduleService = serviceWithRealJsonHelper();
    when(jdbcTemplate.queryForObject(
            eq("SELECT 1 FROM m_office WHERE id = ?"), eq(Integer.class), eq(OFFICE_ID)))
        .thenReturn(1);
    when(namedJdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class)))
        .thenReturn(new int[] {1, 1, 1, 1, 1, 1, 1});

    CommandProcessingResult result =
        scheduleService.saveOfficeWorkingHours(OFFICE_ID, validWeeklyScheduleJson());

    assertNotNull(result);
    verify(namedJdbcTemplate).batchUpdate(anyString(), any(SqlParameterSource[].class));
  }

  @Test
  void saveOfficeWorkingHours_invalidTimes_throwValidationException() {
    mockAuth();
    OfficeExtensionWritePlatformServiceImpl scheduleService = serviceWithRealJsonHelper();
    when(jdbcTemplate.queryForObject(
            eq("SELECT 1 FROM m_office WHERE id = ?"), eq(Integer.class), eq(OFFICE_ID)))
        .thenReturn(1);

    String json =
        validWeeklyScheduleJson().replace("\"openingTime\":\"09:00\"", "\"openingTime\":\"25:00\"");

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> scheduleService.saveOfficeWorkingHours(OFFICE_ID, json));
    verify(namedJdbcTemplate, never()).batchUpdate(anyString(), any(SqlParameterSource[].class));
  }

  @Test
  void saveOfficeWorkingHours_enabledDayOpeningTimeMustBeBeforeClosingTime() {
    mockAuth();
    OfficeExtensionWritePlatformServiceImpl scheduleService = serviceWithRealJsonHelper();
    when(jdbcTemplate.queryForObject(
            eq("SELECT 1 FROM m_office WHERE id = ?"), eq(Integer.class), eq(OFFICE_ID)))
        .thenReturn(1);

    String json =
        validWeeklyScheduleJson().replace("\"closingTime\":\"17:00\"", "\"closingTime\":\"09:00\"");

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> scheduleService.saveOfficeWorkingHours(OFFICE_ID, json));
    verify(namedJdbcTemplate, never()).batchUpdate(anyString(), any(SqlParameterSource[].class));
  }

  @Test
  void saveOfficeWorkingHours_duplicateWeekday_throwsValidationException() {
    mockAuth();
    OfficeExtensionWritePlatformServiceImpl scheduleService = serviceWithRealJsonHelper();
    when(jdbcTemplate.queryForObject(
            eq("SELECT 1 FROM m_office WHERE id = ?"), eq(Integer.class), eq(OFFICE_ID)))
        .thenReturn(1);

    String json = validWeeklyScheduleJson().replace("\"SUNDAY\"", "\"MONDAY\"");

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> scheduleService.saveOfficeWorkingHours(OFFICE_ID, json));
    verify(namedJdbcTemplate, never()).batchUpdate(anyString(), any(SqlParameterSource[].class));
  }

  @Test
  void saveOfficeWorkingHours_missingEnabled_throwsValidationException() {
    mockAuth();
    OfficeExtensionWritePlatformServiceImpl scheduleService = serviceWithRealJsonHelper();
    when(jdbcTemplate.queryForObject(
            eq("SELECT 1 FROM m_office WHERE id = ?"), eq(Integer.class), eq(OFFICE_ID)))
        .thenReturn(1);

    String json = validWeeklyScheduleJson().replace("\"enabled\":true,", "");

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> scheduleService.saveOfficeWorkingHours(OFFICE_ID, json));
    verify(namedJdbcTemplate, never()).batchUpdate(anyString(), any(SqlParameterSource[].class));
  }

  @Test
  void saveOfficeWorkingHours_nonExistentOffice_throws() {
    mockAuth();
    OfficeExtensionWritePlatformServiceImpl scheduleService = serviceWithRealJsonHelper();
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(999L)))
        .thenThrow(new EmptyResultDataAccessException(1));

    assertThrows(
        OfficeNotFoundException.class,
        () -> scheduleService.saveOfficeWorkingHours(999L, validWeeklyScheduleJson()));
  }

  private OfficeExtensionWritePlatformServiceImpl serviceWithRealJsonHelper() {
    return new OfficeExtensionWritePlatformServiceImpl(
        jdbcTemplate, namedJdbcTemplate, context, new FromJsonHelper());
  }

  private String validWeeklyScheduleJson() {
    return "{\"days\":["
        + "{\"weekday\":\"MONDAY\",\"enabled\":true,\"openingTime\":\"09:00\",\"closingTime\":\"17:00\"},"
        + "{\"weekday\":\"TUESDAY\",\"enabled\":true,\"openingTime\":\"09:00\",\"closingTime\":\"17:00\"},"
        + "{\"weekday\":\"WEDNESDAY\",\"enabled\":true,\"openingTime\":\"09:00\",\"closingTime\":\"17:00\"},"
        + "{\"weekday\":\"THURSDAY\",\"enabled\":true,\"openingTime\":\"09:00\",\"closingTime\":\"17:00\"},"
        + "{\"weekday\":\"FRIDAY\",\"enabled\":true,\"openingTime\":\"09:00\",\"closingTime\":\"17:00\"},"
        + "{\"weekday\":\"SATURDAY\",\"enabled\":false},"
        + "{\"weekday\":\"SUNDAY\",\"enabled\":false}"
        + "]}";
  }
}
