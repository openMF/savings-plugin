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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.change.core.CreateTableChange;
import liquibase.changelog.ChangeLogParameters;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import org.apache.fineract.infrastructure.codes.service.CodeValueReadPlatformService;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.CommandProcessingResultJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.ExcludeNothingWithPrettyPrintingOffJsonSerializerGoogleGson;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.serialization.GoogleGsonSerializerHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.address.domain.Address;
import org.apache.fineract.portfolio.address.service.AddressReadPlatformServiceImpl;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real Liquibase, JDBC, native parsing and API serialization; only authentication is mocked. */
@Testcontainers
class OfficeAddressPrecisionIntegrationTest {

  private static final String ROOT = "db/changelog/tenant/module/savings/";
  private static final String CHANGELOG = ROOT + "parts/092-office-coordinate-precision.xml";
  private static final String REFERENCE_LATITUDE = "19.3673440111718";
  private static final String REFERENCE_LONGITUDE = "-99.05897965";
  private static final DefaultToApiJsonSerializer<Object> SERIALIZER =
      new DefaultToApiJsonSerializer<>(
          new ExcludeNothingWithPrettyPrintingOffJsonSerializerGoogleGson(),
          new CommandProcessingResultJsonSerializer(),
          new GoogleGsonSerializerHelper());

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbc;
  private OfficeExtensionWritePlatformServiceImpl write;
  private OfficeExtensionReadPlatformServiceImpl read;
  private PlatformSecurityContext context;

  @BeforeEach
  void existingDatabase() throws Exception {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    // This is exclusively the disposable Testcontainers database, never an application database.
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    jdbc.execute("CREATE TABLE m_office(id bigint PRIMARY KEY)");
    jdbc.execute("CREATE TABLE m_code_value(id int PRIMARY KEY, code_value varchar(100))");
    jdbc.execute("CREATE TABLE m_client(id bigint PRIMARY KEY)");
    jdbc.update("INSERT INTO m_office VALUES (1), (2)");
    jdbc.update("INSERT INTO m_code_value VALUES (1, 'Home')");
    jdbc.update("INSERT INTO m_client VALUES (7)");
    createPinnedCoreAddressTable();
    migrate(ROOT + "parts/001-add-office-address.xml");
    jdbc.execute(
        "CREATE TABLE m_client_address(client_id bigint REFERENCES m_client(id),"
            + " address_id bigint REFERENCES m_address(id), address_type_id int REFERENCES"
            + " m_code_value(id), is_active boolean)");
    jdbc.update(
        "INSERT INTO m_address(id,street,postal_code,latitude,longitude) VALUES"
            + " (10,'Existing office','OFFICE',19.43260812,-99.13320912),"
            + " (20,'Existing client','CLIENT',12.97161234,77.59461234)");
    jdbc.update(
        "INSERT INTO m_office_address(office_id,address_id,address_type_id,is_active)"
            + " VALUES (1,10,1,true)");
    jdbc.update("INSERT INTO m_client_address VALUES (7,20,1,true)");
    context = mock(PlatformSecurityContext.class);
    AppUser user = mock(AppUser.class);
    when(user.getUsername()).thenReturn("coordinate-test");
    when(context.authenticatedUser()).thenReturn(user);
    write =
        new OfficeExtensionWritePlatformServiceImpl(
            jdbc, new NamedParameterJdbcTemplate(dataSource), context, new FromJsonHelper());
    read = new OfficeExtensionReadPlatformServiceImpl(jdbc, context);
  }

  @Test
  void upgradesExistingOfficeAndClientRecordsAndRerunsSafely() throws Exception {
    assertType("latitude", 10, 8);
    assertType("longitude", 10, 8);
    var addresses = snapshot("m_address");
    var offices = snapshot("m_office_address");
    var clients = snapshot("m_client_address");
    migrate(CHANGELOG);
    assertType("latitude", 16, 13);
    assertType("longitude", 16, 13);
    assertEquals(addresses, snapshot("m_address"));
    assertEquals(offices, snapshot("m_office_address"));
    assertEquals(clients, snapshot("m_client_address"));
    migrate(CHANGELOG);
    assertEquals(addresses, snapshot("m_address"));
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM databasechangelog WHERE id LIKE 'ss-092-%'", Integer.class));
    // Widening must preserve the nullable, zero-default native contract too.
    jdbc.update("INSERT INTO m_address(id,street) VALUES (30,'Default coordinates')");
    assertEquals(
        0,
        jdbc.queryForObject("SELECT latitude FROM m_address WHERE id=30", BigDecimal.class)
            .signum());
    assertEquals(
        0,
        jdbc.queryForObject("SELECT longitude FROM m_address WHERE id=30", BigDecimal.class)
            .signum());
  }

  @ParameterizedTest
  @CsvSource({
    "19.3673440111718,-99.05897965",
    "90.0000000000000,-180.0000000000000",
    "-90,180",
    "0,0",
    "0.0000000000001,-0.0000000000001",
    "19.43260812,-99.13320912",
    "19.3673440111718000,-99.05897965000000"
  })
  void coordinatesSurviveCreateUpdateReadAndNativeApiSerialization(
      String latitude, String longitude) throws Exception {
    migrate(CHANGELOG);
    String json = coordinates(latitude, longitude);
    var result = write.createOfficeAddress(2L, json);
    assertNotNull(result.getResourceId());
    assertCoordinates(office(2L), latitude, longitude);
    var updated = write.updateOfficeAddress(1L, officeAddressId(), json);
    JsonObject changes =
        JsonParser.parseString(SERIALIZER.serializeResult(updated))
            .getAsJsonObject()
            .getAsJsonObject("changes");
    assertCoordinates(changes, latitude, longitude);
    // A fresh service/read rules out satisfying the assertion from an in-memory command result.
    read = new OfficeExtensionReadPlatformServiceImpl(new JdbcTemplate(dataSource), context);
    assertCoordinates(office(1L), latitude, longitude);
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM m_office_address", Integer.class));
    assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM m_address", Integer.class));
  }

  @Test
  void supportsNumericScientificNotationAndSingleAxisUpdatesWithoutClearingTheOtherAxis()
      throws Exception {
    migrate(CHANGELOG);
    write.updateOfficeAddress(
        1L, officeAddressId(), "{\"latitude\":1e-13,\"longitude\":-9.905897965e1}");
    assertCoordinates(office(1L), "0.0000000000001", REFERENCE_LONGITUDE);
    write.updateOfficeAddress(
        1L, officeAddressId(), "{\"latitude\":\"" + REFERENCE_LATITUDE + "\",\"locale\":\"en\"}");
    assertCoordinates(office(1L), REFERENCE_LATITUDE, REFERENCE_LONGITUDE);
    write.updateOfficeAddress(1L, officeAddressId(), "{\"postalCode\":\"UPDATED\"}");
    assertCoordinates(office(1L), REFERENCE_LATITUDE, REFERENCE_LONGITUDE);
  }

  @Test
  void acceptsOmittedAndNullPairsAndClearsExistingCoordinates() throws Exception {
    migrate(CHANGELOG);
    write.createOfficeAddress(2L, "{\"addressTypeId\":1}");
    assertCoordinates(office(2L), null, null);
    write.updateOfficeAddress(1L, officeAddressId(), coordinates(null, null));
    assertCoordinates(office(1L), null, null);
    // An explicit null pair is also valid on create.
    jdbc.update("DELETE FROM m_office_address WHERE office_id=2");
    write.createOfficeAddress(2L, coordinates(null, null));
    assertCoordinates(office(2L), null, null);
  }

  @ParameterizedTest
  @CsvSource({
    "90.0000000000001,0",
    "-90.0000000000001,0",
    "0,180.0000000000001",
    "0,-180.0000000000001",
    "19.36734401117181,-99.05897965",
    "0,0.00000000000001",
    ",0",
    "0,",
    "NaN,0",
    "Infinity,0",
    "not-a-number,0"
  })
  void rejectsInvalidCreateAndUpdateBeforeWriting(String latitude, String longitude)
      throws Exception {
    migrate(CHANGELOG);
    var before = snapshot("m_address");
    String json = coordinates(latitude, longitude);
    assertThrows(
        PlatformApiDataValidationException.class, () -> write.createOfficeAddress(2L, json));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> write.updateOfficeAddress(1L, officeAddressId(), json));
    assertEquals(before, snapshot("m_address"));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM m_office_address", Integer.class));
  }

  @Test
  void rejectsOneSidedClearingAndOneSidedCreationOfAPair() throws Exception {
    migrate(CHANGELOG);
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> write.updateOfficeAddress(1L, officeAddressId(), "{\"latitude\":null}"));
    write.updateOfficeAddress(1L, officeAddressId(), coordinates(null, null));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> write.updateOfficeAddress(1L, officeAddressId(), "{\"latitude\":0}"));
    assertCoordinates(office(1L), null, null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "{}", "[]", "1e-14", "NaN", "Infinity"})
  void rejectsUnsupportedJsonCoordinates(String latitude) throws Exception {
    migrate(CHANGELOG);
    String json = "{\"latitude\":" + latitude + ",\"longitude\":0}";
    var before = snapshot("m_address");
    assertThrows(
        PlatformApiDataValidationException.class, () -> write.createOfficeAddress(2L, json));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> write.updateOfficeAddress(1L, officeAddressId(), json));
    assertEquals(before, snapshot("m_address"));
  }

  @ParameterizedTest
  @CsvSource({"16,13", "20,15"})
  void preservesAlreadyUpgradedColumnsWithoutNarrowing(int precision, int scale) throws Exception {
    jdbc.execute(
        "ALTER TABLE m_address ALTER COLUMN latitude TYPE numeric("
            + precision
            + ","
            + scale
            + ")");
    jdbc.execute(
        "ALTER TABLE m_address ALTER COLUMN longitude TYPE numeric("
            + precision
            + ","
            + scale
            + ")");
    var before = snapshot("m_address");
    migrate(CHANGELOG);
    assertType("latitude", precision, scale);
    assertType("longitude", precision, scale);
    assertEquals(before, snapshot("m_address"));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM databasechangelog WHERE id LIKE 'ss-092-%' AND exectype='MARK_RAN'",
            Integer.class));
  }

  @Test
  void finishesAPartiallyUpgradedSchema() throws Exception {
    jdbc.execute("ALTER TABLE m_address ALTER COLUMN latitude TYPE numeric(16,13)");
    migrate(CHANGELOG);
    assertType("latitude", 16, 13);
    assertType("longitude", 16, 13);
  }

  @ParameterizedTest
  @ValueSource(strings = {"text", "numeric(20,8)", "numeric(16,14)", "numeric"})
  void haltsOnIncompatibleTypesBeforeChangingEitherColumn(String type) {
    jdbc.execute("ALTER TABLE m_address ALTER COLUMN latitude TYPE " + type);
    LiquibaseException error = assertThrows(LiquibaseException.class, () -> migrate(CHANGELOG));
    assertTrue(error.toString().contains("WEB-1418"));
    assertType("longitude", 10, 8);
  }

  @Test
  void haltsWhenAColumnIsMissing() {
    jdbc.execute("ALTER TABLE m_address DROP COLUMN longitude");
    assertThrows(LiquibaseException.class, () -> migrate(CHANGELOG));
    assertType("latitude", 10, 8);
  }

  @Test
  void checksTheSchemaAgainOnRestartAndDoesNotOfferALossyRollback() throws Exception {
    migrate(CHANGELOG);
    write.updateOfficeAddress(
        1L, officeAddressId(), coordinates(REFERENCE_LATITUDE, REFERENCE_LONGITUDE));
    try (Connection connection = dataSource.getConnection();
        Liquibase liquibase = liquibase(CHANGELOG, connection)) {
      assertThrows(LiquibaseException.class, () -> liquibase.rollback(1, ""));
    }
    assertCoordinates(office(1L), REFERENCE_LATITUDE, REFERENCE_LONGITUDE);
    jdbc.execute("ALTER TABLE m_address ALTER COLUMN latitude TYPE numeric(20,8)");
    assertThrows(LiquibaseException.class, () -> migrate(CHANGELOG));
    assertType("longitude", 16, 13);
  }

  @Test
  void haltsIfRecordedMigrationsWouldHideInsufficientStorage() throws Exception {
    migrate(CHANGELOG);
    jdbc.execute("ALTER TABLE m_address ALTER COLUMN latitude TYPE numeric(10,8)");
    LiquibaseException error = assertThrows(LiquibaseException.class, () -> migrate(CHANGELOG));
    assertTrue(error.toString().contains("WEB-1418"));
  }

  @Test
  void nativeClientAddressParsingAndReadsRemainCompatible() throws Exception {
    migrate(CHANGELOG);
    AddressReadPlatformServiceImpl nativeRead =
        new AddressReadPlatformServiceImpl(jdbc, context, mock(CodeValueReadPlatformService.class));
    JsonObject existing =
        JsonParser.parseString(SERIALIZER.serializeResult(nativeRead.retrieveAllClientAddress(7L)))
            .getAsJsonArray()
            .get(0)
            .getAsJsonObject();
    assertCoordinates(existing, "12.97161234", "77.59461234");
    Address nativeAddress =
        Address.fromJsonObject(
            JsonParser.parseString(coordinates(REFERENCE_LATITUDE, REFERENCE_LONGITUDE))
                .getAsJsonObject(),
            null,
            null);
    jdbc.update(
        "UPDATE m_address SET latitude=?,longitude=? WHERE id=20",
        nativeAddress.getLatitude(),
        nativeAddress.getLongitude());
    JsonObject updated =
        JsonParser.parseString(SERIALIZER.serializeResult(nativeRead.retrieveAllClientAddress(7L)))
            .getAsJsonArray()
            .get(0)
            .getAsJsonObject();
    assertCoordinates(updated, REFERENCE_LATITUDE, REFERENCE_LONGITUDE);
    assertEquals("Existing client", updated.get("street").getAsString());
    assertEquals("CLIENT", updated.get("postalCode").getAsString());
    assertCoordinates(office(1L), "19.43260812", "-99.13320912");
  }

  @Test
  void masterRegistersTheAdditiveMigration() throws Exception {
    var accessor = new ClassLoaderResourceAccessor();
    String master = ROOT + "module-changelog-master.xml";
    var changelog =
        ChangeLogParserFactory.getInstance()
            .getParser(master, accessor)
            .parse(master, new ChangeLogParameters(), accessor);
    assertEquals(
        4,
        changelog.getChangeSets().stream()
            .filter(change -> CHANGELOG.equals(change.getFilePath()))
            .count());
  }

  private void createPinnedCoreAddressTable() throws Exception {
    // Use the exact m_address definition from the Maven-pinned Fineract artifact, not an imitation.
    String core = "db/changelog/tenant/parts/0001_initial_schema.xml";
    var accessor = new ClassLoaderResourceAccessor();
    var changelog =
        ChangeLogParserFactory.getInstance()
            .getParser(core, accessor)
            .parse(core, new ChangeLogParameters(), accessor);
    CreateTableChange address =
        changelog.getChangeSets().stream()
            .flatMap(change -> change.getChanges().stream())
            .filter(change -> change instanceof CreateTableChange)
            .map(change -> (CreateTableChange) change)
            .filter(change -> "m_address".equals(change.getTableName()))
            .findFirst()
            .orElseThrow();
    try (Connection connection = dataSource.getConnection()) {
      Database database =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      for (var statement : address.generateStatements(database)) {
        for (var sql : SqlGeneratorFactory.getInstance().generateSql(statement, database)) {
          jdbc.execute(sql.toSql());
        }
      }
    }
  }

  private Liquibase liquibase(String changelog, Connection connection) throws Exception {
    Database database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(connection));
    return new Liquibase(changelog, new ClassLoaderResourceAccessor(), database);
  }

  private void migrate(String changelog) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Liquibase liquibase = liquibase(changelog, connection)) {
      liquibase.update(new Contexts(), new LabelExpression());
    }
  }

  private JsonObject office(long id) {
    return JsonParser.parseString(SERIALIZER.serializeResult(read.retrieveOfficeAddresses(id)))
        .getAsJsonArray()
        .get(0)
        .getAsJsonObject();
  }

  private long officeAddressId() {
    return office(1L).get("officeAddressId").getAsLong();
  }

  private String coordinates(String latitude, String longitude) {
    JsonObject json = new JsonObject();
    json.addProperty("addressTypeId", 1);
    json.addProperty("latitude", latitude);
    json.addProperty("longitude", longitude);
    json.addProperty("locale", "en");
    return json.toString();
  }

  private void assertCoordinates(JsonObject json, String latitude, String longitude) {
    for (String axis : List.of("latitude", "longitude")) {
      String expected = axis.equals("latitude") ? latitude : longitude;
      if (expected == null) {
        assertTrue(!json.has(axis) || json.get(axis).isJsonNull(), axis);
      } else {
        assertEquals(0, new BigDecimal(expected).compareTo(json.get(axis).getAsBigDecimal()), axis);
      }
    }
  }

  private void assertType(String column, int precision, int scale) {
    Map<String, Object> metadata =
        jdbc.queryForMap(
            "SELECT numeric_precision,numeric_scale FROM information_schema.columns"
                + " WHERE table_schema=current_schema() AND table_name='m_address' AND column_name=?",
            column);
    assertEquals(precision, ((Number) metadata.get("numeric_precision")).intValue());
    assertEquals(scale, ((Number) metadata.get("numeric_scale")).intValue());
  }

  private List<Map<String, Object>> snapshot(String table) {
    var rows = jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1");
    rows.forEach(
        row ->
            row.replaceAll(
                (key, value) ->
                    value instanceof BigDecimal decimal ? decimal.stripTrailingZeros() : value));
    return rows;
  }
}
