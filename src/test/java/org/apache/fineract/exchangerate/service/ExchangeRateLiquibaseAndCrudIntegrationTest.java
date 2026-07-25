/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.apache.fineract.exchangerate.config.ExchangeRateProviderProperties;
import org.apache.fineract.exchangerate.data.CurrencyConversionData;
import org.apache.fineract.exchangerate.data.ExchangeRateData;
import org.apache.fineract.exchangerate.data.ExchangeRateSynchronizationResult;
import org.apache.fineract.exchangerate.provider.ExchangeRateProvider;
import org.apache.fineract.exchangerate.provider.ExchangeRateProviderResult;
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
  private static final String SYNC_CHANGELOG =
      "db/changelog/tenant/module/savings/parts/048-add-exchange-rate-provider-sync.xml";

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
        new ExchangeRateReadPlatformServiceImpl(
            this.jdbcTemplate,
            this.namedJdbcTemplate,
            this.context,
            validator,
            providerProperties(true));
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
        () -> this.readService.convert(conversionJson("KWD", "XOF", "10", "2026-07-25")));
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
    assertPermissionExists("SYNC_EXCHANGE_RATE");
    assertRoleHasPermission("Super user", "READ_EXCHANGE_RATE");
    assertRoleHasPermission("Super user", "CREATE_EXCHANGE_RATE");
    assertRoleHasPermission("Super user", "UPDATE_EXCHANGE_RATE");
    assertRoleHasPermission("Super user", "DELETE_EXCHANGE_RATE");
    assertRoleHasPermission("Super user", "CONVERT_CURRENCY");
    assertRoleHasPermission("Super user", "SYNC_EXCHANGE_RATE");
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

  @Test
  void synchronizationPreservesHistorySkipsUnchangedRatesAndStoresProviderMetadata() {
    final ExchangeRateProvider provider = mock(ExchangeRateProvider.class);
    when(provider.providerName()).thenReturn("frankfurter");
    when(provider.fetchLatestRates("USD"))
        .thenReturn(
            ExchangeRateProviderResult.instance(
                "frankfurter",
                "USD",
                LocalDate.parse("2026-01-01"),
                rates(
                    "JPY",
                    new BigDecimal("150.000000000000"),
                    "KWD",
                    new BigDecimal("0.305000000000"),
                    "ZZZ",
                    new BigDecimal("1.500000000000"))),
            ExchangeRateProviderResult.instance(
                "frankfurter",
                "USD",
                LocalDate.parse("2026-07-25"),
                rates(
                    "JPY",
                    new BigDecimal("155.125000000000"),
                    "KWD",
                    new BigDecimal("0.305000000000"),
                    "ZZZ",
                    new BigDecimal("1.500000000000"))));

    final ExchangeRateSynchronizationServiceImpl synchronizationService =
        new ExchangeRateSynchronizationServiceImpl(
            this.jdbcTemplate,
            this.namedJdbcTemplate,
            providerProperties(true),
            List.of(provider),
            new ExchangeRateDataValidator(
                new FromJsonHelper(), new CurrencyReadPlatformServiceImpl(this.jdbcTemplate)));

    final ExchangeRateSynchronizationResult firstResult =
        synchronizationService.synchronize("{\"baseCurrency\":\"USD\"}");

    assertEquals(2, firstResult.getImportedCount());
    assertEquals(1, firstResult.getSkippedCount());
    assertEquals(LocalDate.parse("2026-01-01"), firstResult.getProviderRateDate());

    final ExchangeRateSynchronizationResult secondResult =
        synchronizationService.synchronize("{\"baseCurrency\":\"USD\"}");

    assertEquals(1, secondResult.getImportedCount());
    assertEquals(2, secondResult.getSkippedCount());
    assertEquals(LocalDate.parse("2026-07-25"), secondResult.getProviderRateDate());

    final Collection<ExchangeRateData> currentJpyRates =
        this.readService.retrieveAll("USD", "JPY", true, LocalDate.parse("2026-07-25"));
    assertEquals(1, currentJpyRates.size());
    assertEquals(
        new BigDecimal("155.125000000000"), currentJpyRates.iterator().next().getExchangeRate());

    final LocalDate closedHistoricalTo =
        this.jdbcTemplate.queryForObject(
            "SELECT effective_to FROM m_exchange_rate WHERE source_currency_code = ?"
                + " AND target_currency_code = ? AND effective_from = ?",
            LocalDate.class,
            "USD",
            "JPY",
            LocalDate.parse("2026-01-01"));
    assertEquals(LocalDate.parse("2026-07-24"), closedHistoricalTo);

    final Integer providerRows =
        this.jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_exchange_rate WHERE source_currency_code = ?"
                + " AND target_currency_code = ? AND provider = ?"
                + " AND provider_rate_date = ? AND last_synced_at IS NOT NULL",
            Integer.class,
            "USD",
            "JPY",
            "frankfurter",
            LocalDate.parse("2026-07-25"));
    assertEquals(1, providerRows);

    final CurrencyConversionData historicalConversion =
        this.readService.convert(conversionJson("USD", "JPY", "1", "2026-07-20"));
    assertEquals(new BigDecimal("150"), historicalConversion.getConvertedAmount());
  }

  @Test
  void directAndCrossRateConversionsRespectHistoryAndManualOverride() {
    final ExchangeRateProvider provider = mock(ExchangeRateProvider.class);
    when(provider.providerName()).thenReturn("frankfurter");
    when(provider.fetchLatestRates("USD"))
        .thenReturn(
            ExchangeRateProviderResult.instance(
                "frankfurter",
                "USD",
                LocalDate.parse("2026-01-01"),
                rates(
                    "AOA",
                    new BigDecimal("900.000000000000"),
                    "DZD",
                    new BigDecimal("120.000000000000"),
                    "EUR",
                    new BigDecimal("0.900000000000"))),
            ExchangeRateProviderResult.instance(
                "frankfurter",
                "USD",
                LocalDate.parse("2026-07-25"),
                rates(
                    "AOA",
                    new BigDecimal("920.000000000000"),
                    "DZD",
                    new BigDecimal("135.000000000000"),
                    "EUR",
                    new BigDecimal("0.850000000000"))));

    final ExchangeRateSynchronizationServiceImpl synchronizationService =
        new ExchangeRateSynchronizationServiceImpl(
            this.jdbcTemplate,
            this.namedJdbcTemplate,
            providerProperties(true),
            List.of(provider),
            new ExchangeRateDataValidator(
                new FromJsonHelper(), new CurrencyReadPlatformServiceImpl(this.jdbcTemplate)));
    synchronizationService.synchronize("{\"baseCurrency\":\"USD\"}");
    synchronizationService.synchronize("{\"baseCurrency\":\"USD\"}");

    final CurrencyConversionData directProviderConversion =
        this.readService.convert(conversionJson("USD", "EUR", "100", "2026-07-25"));
    assertEquals(new BigDecimal("85.00"), directProviderConversion.getConvertedAmount());
    assertEquals("PROVIDER", directProviderConversion.getRateSource());
    assertNull(directProviderConversion.getBaseCurrency());

    final CurrencyConversionData crossRateConversion =
        this.readService.convert(conversionJson("AOA", "DZD", "920", "2026-07-25"));
    assertEquals(new BigDecimal("135.00"), crossRateConversion.getConvertedAmount());
    assertEquals("CROSS_RATE", crossRateConversion.getRateSource());
    assertEquals("USD", crossRateConversion.getBaseCurrency());

    final CurrencyConversionData historicalCrossRateConversion =
        this.readService.convert(conversionJson("AOA", "DZD", "900", "2026-01-15"));
    assertEquals(new BigDecimal("120.00"), historicalCrossRateConversion.getConvertedAmount());
    assertEquals("CROSS_RATE", historicalCrossRateConversion.getRateSource());

    this.writeService.createExchangeRate(
        rateJson("AOA", "DZD", "0.200000000000", "2026-01-01", null, true));
    final CurrencyConversionData manualOverrideConversion =
        this.readService.convert(conversionJson("AOA", "DZD", "100", "2026-07-25"));
    assertEquals(new BigDecimal("20.00"), manualOverrideConversion.getConvertedAmount());
    assertEquals("DIRECT", manualOverrideConversion.getRateSource());
    assertNull(manualOverrideConversion.getBaseCurrency());
  }

  @Test
  void synchronizationUpdatesExistingProviderRowsWithoutDuplicatingThem() {
    final ExchangeRateProvider provider = mock(ExchangeRateProvider.class);
    when(provider.providerName()).thenReturn("frankfurter");
    when(provider.fetchLatestRates("USD"))
        .thenReturn(
            ExchangeRateProviderResult.instance(
                "frankfurter",
                "USD",
                LocalDate.parse("2026-07-25"),
                rates(
                    "AOA",
                    new BigDecimal("920.000000000000"),
                    "DZD",
                    new BigDecimal("135.000000000000"),
                    "EUR",
                    new BigDecimal("0.850000000000"))),
            ExchangeRateProviderResult.instance(
                "frankfurter",
                "USD",
                LocalDate.parse("2026-07-25"),
                rates(
                    "AOA",
                    new BigDecimal("930.000000000000"),
                    "DZD",
                    new BigDecimal("135.000000000000"),
                    "EUR",
                    new BigDecimal("0.850000000000"))));

    final ExchangeRateSynchronizationServiceImpl synchronizationService =
        new ExchangeRateSynchronizationServiceImpl(
            this.jdbcTemplate,
            this.namedJdbcTemplate,
            providerProperties(true),
            List.of(provider),
            new ExchangeRateDataValidator(
                new FromJsonHelper(), new CurrencyReadPlatformServiceImpl(this.jdbcTemplate)));

    assertEquals(
        3, synchronizationService.synchronize("{\"baseCurrency\":\"USD\"}").getImportedCount());
    final ExchangeRateSynchronizationResult secondResult =
        synchronizationService.synchronize("{\"baseCurrency\":\"USD\"}");
    assertEquals(1, secondResult.getImportedCount());
    assertEquals(2, secondResult.getSkippedCount());

    final Integer providerRows =
        this.jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_exchange_rate WHERE provider = ?"
                + " AND provider_rate_date = ?",
            Integer.class,
            "frankfurter",
            LocalDate.parse("2026-07-25"));
    assertEquals(3, providerRows);

    final BigDecimal updatedAoaRate =
        this.jdbcTemplate.queryForObject(
            "SELECT exchange_rate FROM m_exchange_rate WHERE provider = ?"
                + " AND source_currency_code = ? AND target_currency_code = ?"
                + " AND provider_rate_date = ?",
            BigDecimal.class,
            "frankfurter",
            "USD",
            "AOA",
            LocalDate.parse("2026-07-25"));
    assertEquals(0, new BigDecimal("930.000000000000").compareTo(updatedAoaRate));

    final CurrencyConversionData crossRateConversion =
        this.readService.convert(conversionJson("AOA", "DZD", "930", "2026-07-25"));
    assertEquals(new BigDecimal("135.00"), crossRateConversion.getConvertedAmount());
  }

  @Test
  void crossRateConversionStillRejectsUnsupportedCurrency() {
    this.writeService.createExchangeRate(
        rateJson("USD", "AOA", "920.000000000000", "2026-01-01", null, true));

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> this.readService.convert(conversionJson("AOA", "ZZZ", "10", "2026-07-25")));
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
      final Liquibase syncLiquibase =
          new Liquibase(SYNC_CHANGELOG, new ClassLoaderResourceAccessor(), database);
      syncLiquibase.update(new Contexts(), new LabelExpression());
    }
  }

  private ExchangeRateProviderProperties providerProperties(final boolean enabled) {
    final ExchangeRateProviderProperties properties = new ExchangeRateProviderProperties();
    properties.setProviderEnabled(enabled);
    properties.setProvider("frankfurter");
    properties.setProviderBaseUrl("https://api.frankfurter.dev/v1");
    properties.setBaseCurrency("USD");
    return properties;
  }

  private Map<String, BigDecimal> rates(
      final String firstCurrency,
      final BigDecimal firstRate,
      final String secondCurrency,
      final BigDecimal secondRate,
      final String thirdCurrency,
      final BigDecimal thirdRate) {
    final Map<String, BigDecimal> rates = new LinkedHashMap<>();
    rates.put(firstCurrency, firstRate);
    rates.put(secondCurrency, secondRate);
    rates.put(thirdCurrency, thirdRate);
    return rates;
  }

  private void seedCurrencies() {
    insertCurrency("USD", "US Dollar", 2, "$");
    insertCurrency("JPY", "Japanese Yen", 0, "JPY");
    insertCurrency("KWD", "Kuwaiti Dinar", 3, "KD");
    insertCurrency("XOF", "CFA Franc BCEAO", 0, "CFA");
    insertCurrency("EUR", "Euro", 2, "EUR");
    insertCurrency("AOA", "Angolan Kwanza", 2, "AOA");
    insertCurrency("DZD", "Algerian Dinar", 2, "DZD");
  }

  private void insertCurrency(
      final String code, final String name, final int decimalPlaces, final String displaySymbol) {
    this.jdbcTemplate.update(
        "INSERT INTO m_currency"
            + " (code, name, decimal_places, currency_multiplesof, display_symbol, internationalized_name_code)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        code,
        name,
        decimalPlaces,
        null,
        displaySymbol,
        "currency." + code);
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
