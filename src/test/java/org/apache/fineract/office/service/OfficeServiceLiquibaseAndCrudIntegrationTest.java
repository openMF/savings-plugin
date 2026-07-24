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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.DriverManager;
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
import org.apache.fineract.office.data.OfficeServiceData;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class OfficeServiceLiquibaseAndCrudIntegrationTest {

  private static final String CHANGELOG =
      "db/changelog/tenant/module/savings/parts/045-create-office-services-table.xml";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  private JdbcTemplate jdbcTemplate;
  private NamedParameterJdbcTemplate namedJdbcTemplate;
  private PlatformSecurityContext context;
  private FromJsonHelper fromJsonHelper;

  @BeforeEach
  void setUp() throws Exception {
    final DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    this.namedJdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    this.context = mock(PlatformSecurityContext.class);
    this.fromJsonHelper = mock(FromJsonHelper.class);

    final AppUser user = mock(AppUser.class);
    when(this.context.authenticatedUser()).thenReturn(user);

    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_selfservice_office_service");
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
  void officeServiceMigrationLoadsAndCrudWorks() {
    assertTrue(tableExists("m_selfservice_office_service"));

    final OfficeExtensionWritePlatformServiceImpl writeService =
        new OfficeExtensionWritePlatformServiceImpl(
            this.jdbcTemplate, this.namedJdbcTemplate, this.context, this.fromJsonHelper);
    final OfficeExtensionReadPlatformServiceImpl readService =
        new OfficeExtensionReadPlatformServiceImpl(this.jdbcTemplate, this.context);

    final String createJson =
        "{\"serviceName\":\"Cash Deposit\",\"serviceExternalId\":\"cash-deposit\",\"workingHours\":\"09:00-17:00\"}";
    final JsonElement createElement = JsonParser.parseString(createJson);
    when(this.fromJsonHelper.parse(createJson)).thenReturn(createElement);
    when(this.fromJsonHelper.extractStringNamed("serviceName", createElement))
        .thenReturn("Cash Deposit");
    when(this.fromJsonHelper.extractStringNamed("serviceExternalId", createElement))
        .thenReturn("cash-deposit");
    when(this.fromJsonHelper.extractStringNamed("workingHours", createElement))
        .thenReturn("09:00-17:00");

    final CommandProcessingResult createResult = writeService.createOfficeService(1L, createJson);
    final Long serviceId = createResult.getResourceId();
    assertNotNull(serviceId);

    Collection<OfficeServiceData> services = readService.retrieveOfficeServices(1L);
    assertEquals(1, services.size());
    OfficeServiceData service = services.iterator().next();
    assertEquals("Cash Deposit", service.getServiceName());
    assertEquals("cash-deposit", service.getServiceExternalId());
    assertEquals("09:00-17:00", service.getWorkingHours());

    final String updateJson = "{\"serviceName\":\"Cash Withdrawal\",\"workingHours\":\"10:00-16:00\"}";
    final JsonElement updateElement = JsonParser.parseString(updateJson);
    when(this.fromJsonHelper.parse(updateJson)).thenReturn(updateElement);
    when(this.fromJsonHelper.parameterExists("serviceName", updateElement)).thenReturn(true);
    when(this.fromJsonHelper.extractStringNamed("serviceName", updateElement))
        .thenReturn("Cash Withdrawal");
    when(this.fromJsonHelper.parameterExists("serviceExternalId", updateElement)).thenReturn(false);
    when(this.fromJsonHelper.parameterExists("workingHours", updateElement)).thenReturn(true);
    when(this.fromJsonHelper.extractStringNamed("workingHours", updateElement))
        .thenReturn("10:00-16:00");

    writeService.updateOfficeService(1L, serviceId, updateJson);
    service = readService.retrieveOfficeService(serviceId);
    assertEquals("Cash Withdrawal", service.getServiceName());
    assertEquals("10:00-16:00", service.getWorkingHours());

    writeService.deleteOfficeService(1L, serviceId);
    services = readService.retrieveOfficeServices(1L);
    assertFalse(services.iterator().hasNext());
  }

  private boolean tableExists(final String tableName) {
    final Integer count =
        this.jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
            Integer.class,
            tableName);
    return count != null && count > 0;
  }
}
