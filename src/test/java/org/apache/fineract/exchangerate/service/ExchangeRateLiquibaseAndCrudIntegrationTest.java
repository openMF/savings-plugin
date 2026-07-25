/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.fineract.exchangerate.data.CurrencyConversionData;
import org.apache.fineract.exchangerate.data.ExchangeRateData;
import org.apache.fineract.exchangerate.validation.ExchangeRateDataValidator;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.service.CurrencyReadPlatformServiceImpl;
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
class ExchangeRateLiquibaseAndCrudIntegrationTest {

  private static final String CHANGELOG =
      "db/changelog/tenant/module/savings/parts/047-create-dynamic-exchange-rates.xml";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  private JdbcTemplate jdbcTemplate;
  private NamedParameterJdbcTemplate namedJdbcTemplate;
  private PlatformSecurityContext context;
  private ExchangeRateReadPlatformServiceImpl readService;
  private ExchangeRateWritePlatformServiceImpl writeService;

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

    resetSchema();
    runLiquibase();
    seedCurrencies();

    final ExchangeRateDataValidator validator =
        new ExchangeRateDataValidator(
            new FromJsonHelper(), new CurrencyReadPlatformServiceImpl(this.jdbcTemplate));
    this.readService =
        new ExchangeRateReadPlatformServiceImpl(this.jdbcTemplate, this.context, validator);
    this.writeService =
        new ExchangeRateWritePlatformServiceImpl(
            this.jdbcTemplate, this.namedJdbcTemplate, this.context, validator);
  }

  @Test
  void exchangeRateMigrationLoadsAndCrudFilteringConversionPrecisionWork() {
    assertTrue(tableExists("m_exchange_rate"));
    assertNotNull(indexExists("uk_exchange_rate_pair_effective_from"));

    final CommandProcessingResult historical =
        this.writeService.createExchangeRate(
            rateJson("USD", "JPY", "140.000000000000", "2025-01-01", "2025-12-31", true));
    assertNotNull(historical.getResourceId());

    final CommandProcessingResult current =
        this.writeService.createExchangeRate(
            rateJson("USD", "JPY", "155.125000000000", "2026-01-01", null, true));
    final Long currentId = current.getResourceId();
    assertNotNull(currentId);

    Collection<ExchangeRateData> filtered =
        this.readService.retrieveAll("USD", "JPY", true, LocalDate.parse("2026-07-25"));
    assertEquals(1, filtered.size());
    assertEquals(currentId, filtered.iterator().next().getId());

    CurrencyConversionData jpyConversion =
        this.readService.convert(conversionJson("USD", "JPY", "1.25", "2026-07-25"));
    assertEquals(new BigDecimal("194"), jpyConversion.getConvertedAmount());

    CurrencyConversionData historicalConversion =
        this.readService.convert(conversionJson("USD", "JPY", "1.25", "2025-07-25"));
    assertEquals(new BigDecimal("175").setScale(0), historicalConversion.getConvertedAmount());

    this.writeService.updateExchangeRate(currentId, "{\"exchangeRate\":156.000000000000}");
    jpyConversion = this.readService.convert(conversionJson("USD", "JPY", "1.25", "2026-07-25"));
    assertEquals(new BigDecimal("195").setScale(0), jpyConversion.getConvertedAmount());

    final Long preciseRateId =
        this.writeService
            .createExchangeRate(
                rateJson("XOF", "KWD", "0.000550000000", "2026-01-01", "2026-06-30", true))
            .getResourceId();
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> this.readService.convert(conversionJson("XOF", "KWD", "1000", "2026-07-25")));

    this.writeService.updateExchangeRate(preciseRateId, "{\"effectiveTo\":null}");
    CurrencyConversionData kwdConversion =
        this.readService.convert(conversionJson("XOF", "KWD", "1000", "2026-07-25"));
    assertEquals(new BigDecimal("0.550"), kwdConversion.getConvertedAmount());

    this.writeService.deleteExchangeRate(preciseRateId);
    assertThrows(
        PlatformDataIntegrityException.class, () -> this.readService.retrieveOne(preciseRateId));
  }

  @Test
  void validationRejectsUnsupportedCurrenciesOverlapsAndMissingRates() {
    this.writeService.createExchangeRate(
        rateJson("USD", "JPY", "155.125000000000", "2026-01-01", null, true));

    assertThrows(
        PlatformDataIntegrityException.class,
        () ->
            this.writeService.createExchangeRate(
                rateJson("USD", "JPY", "156.000000000000", "2026-06-01", null, true)));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            this.writeService.createExchangeRate(
                rateJson("ZZZ", "JPY", "156.000000000000", "2026-06-01", null, true)));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> this.readService.convert(conversionJson("JPY", "USD", "10", "2026-07-25")));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            this.writeService.createExchangeRate(
                rateJson("USD", "USD", "1.000000000000", "2026-06-01", null, true)));
  }

  @Test
  void duplicateAndConcurrentCreateAreRejected() throws Exception {
    this.writeService.createExchangeRate(
        rateJson("USD", "KWD", "0.305000000000", "2026-01-01", null, false));
    final Collection<ExchangeRateData> inactiveRates =
        this.readService.retrieveAll("USD", "KWD", false, LocalDate.parse("2026-07-25"));
    assertEquals(1, inactiveRates.size());

    assertThrows(
        PlatformDataIntegrityException.class,
        () ->
            this.writeService.createExchangeRate(
                rateJson("USD", "KWD", "0.306000000000", "2026-01-01", null, false)));

    final Callable<Boolean> createRate =
        () -> {
          try {
            this.writeService.createExchangeRate(
                rateJson("JPY", "KWD", "0.002000000000", "2026-01-01", null, false));
            return true;
          } catch (final PlatformDataIntegrityException | DataIntegrityViolationException e) {
            return false;
          }
        };

    try (var executor = Executors.newFixedThreadPool(2)) {
      final List<Future<Boolean>> results = executor.invokeAll(List.of(createRate, createRate));
      final long successCount =
          results.stream()
              .filter(
                  result -> {
                    try {
                      return result.get();
                    } catch (final Exception e) {
                      throw new IllegalStateException(e);
                    }
                  })
              .count();
      assertEquals(1, successCount);
    }
  }

  @Test
  void migrationCreatesPermissionsAndDatabaseConstraints() {
    assertPermissionExists("READ_EXCHANGE_RATE");
    assertPermissionExists("CREATE_EXCHANGE_RATE");
    assertPermissionExists("UPDATE_EXCHANGE_RATE");
    assertPermissionExists("DELETE_EXCHANGE_RATE");
    assertPermissionExists("CONVERT_CURRENCY");
    assertRoleHasPermission("Super user", "READ_EXCHANGE_RATE");
    assertRoleHasPermission("Super user", "CREATE_EXCHANGE_RATE");
    assertRoleHasPermission("Super user", "UPDATE_EXCHANGE_RATE");
    assertRoleHasPermission("Super user", "DELETE_EXCHANGE_RATE");
    assertRoleHasPermission("Super user", "CONVERT_CURRENCY");
    assertRoleHasPermission("Self Service User", "READ_EXCHANGE_RATE");
    assertRoleHasPermission("Self Service User", "CONVERT_CURRENCY");

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            this.jdbcTemplate.update(
                "INSERT INTO m_exchange_rate"
                    + " (source_currency_code, target_currency_code, exchange_rate, effective_from,"
                    + " active, created_date, last_modified_date)"
                    + " VALUES (?, ?, ?, ?, true, now(), now())",
                "USD",
                "JPY",
                new BigDecimal("-1.00"),
                LocalDate.parse("2026-01-01")));
  }

  private void resetSchema() {
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_exchange_rate");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_role_permission");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_permission");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_role");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS m_currency");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS databasechangelog");
    this.jdbcTemplate.execute("DROP TABLE IF EXISTS databasechangeloglock");
    this.jdbcTemplate.execute(
        "CREATE TABLE m_permission (id BIGSERIAL PRIMARY KEY, grouping VARCHAR(50),"
            + " code VARCHAR(100) UNIQUE, entity_name VARCHAR(100), action_name VARCHAR(100),"
            + " can_maker_checker BOOLEAN)");
    this.jdbcTemplate.execute("CREATE TABLE m_role (id BIGINT PRIMARY KEY, name VARCHAR(100))");
    this.jdbcTemplate.execute(
        "CREATE TABLE m_role_permission (role_id BIGINT, permission_id BIGINT,"
            + " UNIQUE(role_id, permission_id))");
    this.jdbcTemplate.update(
        "INSERT INTO m_role (id, name) VALUES (?, ?), (?, ?)",
        1L,
        "Super user",
        2L,
        "Self Service User");
    this.jdbcTemplate.execute(
        "CREATE TABLE m_currency (code VARCHAR(3) PRIMARY KEY, name VARCHAR(100),"
            + " decimal_places INTEGER, currency_multiplesof INTEGER,"
            + " display_symbol VARCHAR(10), internationalized_name_code VARCHAR(100))");
  }

  private void runLiquibase() throws Exception {
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

  private void seedCurrencies() {
    this.jdbcTemplate.update(
        "INSERT INTO m_currency"
            + " (code, name, decimal_places, currency_multiplesof, display_symbol, internationalized_name_code)"
            + " VALUES (?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?)",
        "USD",
        "US Dollar",
        2,
        null,
        "$",
        "currency.USD",
        "JPY",
        "Japanese Yen",
        0,
        null,
        "JPY",
        "currency.JPY",
        "KWD",
        "Kuwaiti Dinar",
        3,
        null,
        "KD",
        "currency.KWD",
        "XOF",
        "CFA Franc BCEAO",
        0,
        null,
        "CFA",
        "currency.XOF");
  }

  private boolean tableExists(final String tableName) {
    final Integer count =
        this.jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
            Integer.class,
            tableName);
    return count != null && count > 0;
  }

  private Boolean indexExists(final String indexName) {
    return this.jdbcTemplate.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?)",
        Boolean.class,
        indexName);
  }

  private void assertPermissionExists(final String code) {
    final Integer count =
        this.jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_permission WHERE code = ?", Integer.class, code);
    assertEquals(1, count);
  }

  private void assertRoleHasPermission(final String roleName, final String permissionCode) {
    final Integer count =
        this.jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_role_permission rp"
                + " JOIN m_role r ON r.id = rp.role_id"
                + " JOIN m_permission p ON p.id = rp.permission_id"
                + " WHERE r.name = ? AND p.code = ?",
            Integer.class,
            roleName,
            permissionCode);
    assertEquals(1, count);
  }

  private String rateJson(
      final String sourceCurrency,
      final String targetCurrency,
      final String exchangeRate,
      final String effectiveFrom,
      final String effectiveTo,
      final boolean active) {
    return "{\"sourceCurrency\":\""
        + sourceCurrency
        + "\",\"targetCurrency\":\""
        + targetCurrency
        + "\",\"exchangeRate\":"
        + exchangeRate
        + ",\"effectiveFrom\":\""
        + effectiveFrom
        + "\",\"active\":"
        + active
        + (effectiveTo == null ? "" : ",\"effectiveTo\":\"" + effectiveTo + "\"")
        + "}";
  }

  private String conversionJson(
      final String sourceCurrency,
      final String targetCurrency,
      final String amount,
      final String conversionDate) {
    return "{\"sourceCurrency\":\""
        + sourceCurrency
        + "\",\"targetCurrency\":\""
        + targetCurrency
        + "\",\"amount\":"
        + amount
        + ",\"conversionDate\":\""
        + conversionDate
        + "\"}";
  }
}
