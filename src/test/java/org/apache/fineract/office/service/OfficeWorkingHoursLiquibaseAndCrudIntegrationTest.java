/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalTime;
import java.util.Collection;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.office.data.OfficeWorkingHoursData;
import org.apache.fineract.office.data.OfficeWorkingHoursDayData;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class OfficeWorkingHoursLiquibaseAndCrudIntegrationTest {

  private static final String CHANGELOG =
      "db/changelog/tenant/module/savings/parts/046-create-office-working-hours-table.xml";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  private JdbcTemplate jdbcTemplate;
  private NamedParameterJdbcTemplate namedJdbcTemplate;
  private PlatformSecurityContext context;

  @BeforeEach
  void setUp() throws Exception {
    final DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    this.namedJdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    this.context = mock(PlatformSecurityContext.class);

    final AppUser user = mock(AppUser.class);
    when(this.context.authenticatedUser()).thenReturn(user);

    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_selfservice_office_working_hours");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS databasechangelog");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS databasechangeloglock");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_office");
    this.jdbcTemplate.execute("CREATE TABLE m_office (id BIGINT PRIMARY KEY)");
    this.jdbcTemplate.update("INSERT INTO m_office (id) VALUES (?)", 1L);

    try (Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      final Database database =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      final Liquibase liquibase =
          new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
      liquibase.update(new Contexts(), new LabelExpression());
    }
  }

  @Test
  void officeWorkingHoursMigrationLoadsAndCrudWorks() {
    assertTrue(tableExists("m_selfservice_office_working_hours"));
    assertEquals("time without time zone", columnType("opening_time"));
    assertEquals("time without time zone", columnType("closing_time"));

    final OfficeExtensionWritePlatformServiceImpl writeService =
        new OfficeExtensionWritePlatformServiceImpl(
            this.jdbcTemplate, this.namedJdbcTemplate, this.context, new FromJsonHelper());
    final OfficeExtensionReadPlatformServiceImpl readService =
        new OfficeExtensionReadPlatformServiceImpl(this.jdbcTemplate, this.context);

    final CommandProcessingResult createResult =
        writeService.saveOfficeWorkingHours(1L, weeklyScheduleJson("09:00", "17:00"));
    assertEquals(1L, createResult.getOfficeId());

    OfficeWorkingHoursData schedule = readService.retrieveOfficeWorkingHours(1L);
    assertEquals(7, schedule.getDays().size());
    OfficeWorkingHoursDayData monday = day(schedule.getDays(), "MONDAY");
    assertTrue(monday.getEnabled());
    assertEquals(LocalTime.parse("09:00"), monday.getOpeningTime());
    assertEquals(LocalTime.parse("17:00"), monday.getClosingTime());

    OfficeWorkingHoursDayData saturday = day(schedule.getDays(), "SATURDAY");
    assertFalse(saturday.getEnabled());
    assertNull(saturday.getOpeningTime());
    assertNull(saturday.getClosingTime());

    writeService.saveOfficeWorkingHours(1L, weeklyScheduleJson("10:00", "16:00"));
    schedule = readService.retrieveOfficeWorkingHours(1L);
    monday = day(schedule.getDays(), "MONDAY");
    assertEquals(7, schedule.getDays().size());
    assertEquals(LocalTime.parse("10:00"), monday.getOpeningTime());
    assertEquals(LocalTime.parse("16:00"), monday.getClosingTime());
  }

  @Test
  void officeWorkingHoursMigrationPreventsDuplicateOfficeWeekdayRows() {
    this.jdbcTemplate.update(
        "INSERT INTO m_selfservice_office_working_hours"
            + " (office_id, weekday, enabled, opening_time, closing_time)"
            + " VALUES (?, ?, ?, CAST(? AS time), CAST(? AS time))",
        1L,
        "MONDAY",
        true,
        "09:00",
        "17:00");

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            this.jdbcTemplate.update(
                "INSERT INTO m_selfservice_office_working_hours"
                    + " (office_id, weekday, enabled, opening_time, closing_time)"
                    + " VALUES (?, ?, ?, CAST(? AS time), CAST(? AS time))",
                1L,
                "MONDAY",
                true,
                "10:00",
                "16:00"));
  }

  private OfficeWorkingHoursDayData day(
      final Collection<OfficeWorkingHoursDayData> days, final String weekday) {
    return days.stream()
        .filter(day -> weekday.equals(day.getWeekday()))
        .findFirst()
        .orElseThrow();
  }

  private boolean tableExists(final String tableName) {
    final Integer count =
        this.jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
            Integer.class,
            tableName);
    return count != null && count > 0;
  }

  private String columnType(final String columnName) {
    return this.jdbcTemplate.queryForObject(
        "SELECT data_type FROM information_schema.columns"
            + " WHERE table_schema = 'public' AND table_name = 'm_selfservice_office_working_hours'"
            + " AND column_name = ?",
        String.class,
        columnName);
  }

  private String weeklyScheduleJson(final String openingTime, final String closingTime) {
    return "{\"days\":["
        + "{\"weekday\":\"MONDAY\",\"enabled\":true,\"openingTime\":\""
        + openingTime
        + "\",\"closingTime\":\""
        + closingTime
        + "\"},"
        + "{\"weekday\":\"TUESDAY\",\"enabled\":true,\"openingTime\":\""
        + openingTime
        + "\",\"closingTime\":\""
        + closingTime
        + "\"},"
        + "{\"weekday\":\"WEDNESDAY\",\"enabled\":true,\"openingTime\":\""
        + openingTime
        + "\",\"closingTime\":\""
        + closingTime
        + "\"},"
        + "{\"weekday\":\"THURSDAY\",\"enabled\":true,\"openingTime\":\""
        + openingTime
        + "\",\"closingTime\":\""
        + closingTime
        + "\"},"
        + "{\"weekday\":\"FRIDAY\",\"enabled\":true,\"openingTime\":\""
        + openingTime
        + "\",\"closingTime\":\""
        + closingTime
        + "\"},"
        + "{\"weekday\":\"SATURDAY\",\"enabled\":false},"
        + "{\"weekday\":\"SUNDAY\",\"enabled\":false}"
        + "]}";
  }
}
