/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.testing.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public abstract class SavingsIntegrationTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(SavingsIntegrationTestBase.class);
  private static final Path PLUGIN_JAR = Path.of("target/savings-plugin-1.16.0-SNAPSHOT.jar");

  private static final Network network = Network.newNetwork();

  // 1. Boot Postgres with the default tenant DB
  protected static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:15-alpine")
          .withNetwork(network)
          .withNetworkAliases("db")
          .withDatabaseName("fineract_default")
          // Use 'postgres' superuser to easily create the second DB
          .withUsername("postgres")
          .withPassword("postgres");

  protected static final GenericContainer<?> fineract;

  static {
    try {
      if (!Files.isRegularFile(PLUGIN_JAR) || Files.size(PLUGIN_JAR) == 0) {
        throw new IllegalStateException(
            "Savings plugin JAR is missing or empty: " + PLUGIN_JAR.toAbsolutePath());
      }
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to inspect savings plugin JAR: " + PLUGIN_JAR.toAbsolutePath(), e);
    }

    postgres.start();

    // 2. Pre-Initialize the Master Tenant Database
    try {
      postgres.execInContainer("psql", "-U", "postgres", "-c", "CREATE DATABASE fineract_tenants;");
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to initialize fineract_tenants database in Testcontainers", e);
    }

    // 3. Fineract Container with Strict Env Variables
    //
    // Image selection (first match wins): -Dfineract.it.image, then env FINERACT_IT_IMAGE, else
    // apache/fineract:develop (Docker Hub — tracks Fineract develop, aligned with README and
    // mifosx/docker-compose.yml). Use FINERACT_IT_IMAGE (-Dfineract.it.image) to pin a digest or a
    // locally built image that matches pom fineract.version exactly.
    final String fromEnv = System.getenv("FINERACT_IT_IMAGE");
    final String fromProperty = System.getProperty("fineract.it.image");
    final String defaultImage = "apache/fineract:develop";
    final String fineractImage =
        fromProperty != null && !fromProperty.isBlank()
            ? fromProperty
            : (fromEnv != null && !fromEnv.isBlank() ? fromEnv : defaultImage);
    final DockerImageName dockerImageName =
        DockerImageName.parse(fineractImage).asCompatibleSubstituteFor("apache/fineract");

    fineract =
        new GenericContainer<>(dockerImageName)
            .withNetwork(network)
            .withExposedPorts(8443)

            // Hikari Master Configuration (fineract_tenants)
            .withEnv("FINERACT_HIKARI_JDBC_URL", "jdbc:postgresql://db:5432/fineract_tenants")
            .withEnv("FINERACT_HIKARI_USERNAME", "postgres")
            .withEnv("FINERACT_HIKARI_PASSWORD", "postgres")
            .withEnv("FINERACT_HIKARI_DRIVER_SOURCE_CLASS_NAME", "org.postgresql.Driver")

            // Default Tenant Database Properties (fineract_default)
            .withEnv("FINERACT_DEFAULT_TENANTDB_HOSTNAME", "db")
            .withEnv("FINERACT_DEFAULT_TENANTDB_PORT", "5432")
            .withEnv("FINERACT_DEFAULT_TENANTDB_UID", "postgres")
            .withEnv("FINERACT_DEFAULT_TENANTDB_PWD", "postgres")
            .withEnv("FINERACT_DEFAULT_TENANTDB_CONN_PARAMS", "")

            // Enable the self-service module (matches Fineract docker-compose.override.yml)
            .withEnv("FINERACT_MODULE_SAVINGS_ENABLED", "true")
            .withEnv("SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING", "true")
            .withEnv("FINERACT_MODULES_SAVINGS_RUNREPORTS_ALLOWLIST", "Client Details")

            // Timezone (Optional but highly recommended to prevent sync errors)
            .withEnv("TZ", "UTC")
            .withEnv("JAVA_TOOL_OPTIONS", "-Xmx2G")

            // Keep SSL enabled (default for Fineract container images); tests use relaxed HTTPS
            // validation.
            .withEnv("FINERACT_SERVER_SSL_ENABLED", "true")
            .withEnv("FINERACT_SERVER_PORT", "8443")

            // Mount the compiled Plugin JAR into the auto-scanned /app/plugins directory
            .withCopyFileToContainer(
                MountableFile.forHostPath(PLUGIN_JAR),
                "/app/plugins/savings-plugin.jar")

            // Prepend the plugin JAR to the JIB container's classpath.
            .withCreateContainerCmdModifier(
                cmd -> {
                  cmd.withEntrypoint(
                      "sh",
                      "-c",
                      "CLASSPATH=$(cat /app/jib-classpath-file) && "
                          + "exec java $JAVA_TOOL_OPTIONS "
                          + "-Duser.home=/tmp -Dfile.encoding=UTF-8 -Duser.timezone=UTC -Djava.security.egd=file:/dev/./urandom "
                          + "-cp /app/plugins/savings-plugin.jar:$CLASSPATH "
                          + "org.apache.fineract.ServerApplication");
                  cmd.withCmd();
                })

            // Stream container stdout/stderr to test output for diagnostic visibility.
            // This reveals the actual failure reason instead of generic ContainerLaunchException.
            .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("fineract"))

            // HostPortWaitStrategy still runs an internal port check that requires bash (missing in
            // apache/fineract images). HTTPS + allowInsecure waits until the app serves actuator.
            .waitingFor(
                Wait.forHttps("/fineract-provider/actuator/health")
                    .allowInsecure()
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(7)));

    fineract.start();
  }

  protected static int getFineractPort() {
    return fineract.getMappedPort(8443);
  }

  /**
   * Executes a SQL statement inside the Postgres test container using {@code psql}. Uses {@code
   * ON_ERROR_STOP=1} so that any SQL error causes the execution to fail immediately, preventing
   * partial state corruption.
   */
  protected static void executeSqlInPostgres(String sql) {
    Container.ExecResult result =
        execPsql(
            """
                BEGIN;
                %s
                COMMIT;
                """
                .formatted(sql),
            false);
    if (result.getExitCode() != 0) {
      throw new RuntimeException("Failed to execute SQL in test database: " + result.getStderr());
    }
  }

  /**
   * Executes a SQL statement inside the Postgres test container after safely rendering parameters
   * as SQL literals for test-only usage.
   */
  protected static void executeSqlInPostgres(String sqlTemplate, Object... parameters) {
    String[] rendered = new String[parameters.length];
    for (int index = 0; index < parameters.length; index++) {
      rendered[index] = sqlLiteral(parameters[index]);
    }
    executeSqlInPostgres(sqlTemplate.formatted((Object[]) rendered));
  }

  /** Queries a single scalar value from the Postgres test container using {@code psql -tA}. */
  protected static String querySingleValueInPostgres(String sql) {
    Container.ExecResult result = execPsql(postgres.getDatabaseName(), sql, true);
    if (result.getExitCode() != 0) {
      throw new RuntimeException("Failed to query test database: " + result.getStderr());
    }
    return result
        .getStdout()
        .lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .findFirst()
        .orElse("");
  }

  /** Executes SQL in the actual database used by Fineract's default tenant. */
  protected static void executeSqlInDefaultTenant(String sqlTemplate, Object... parameters) {
    String[] rendered = new String[parameters.length];
    for (int index = 0; index < parameters.length; index++) {
      rendered[index] = sqlLiteral(parameters[index]);
    }
    Container.ExecResult result =
        execPsql(
            "fineract_default",
            """
                BEGIN;
                %s
                COMMIT;
                """
                .formatted(sqlTemplate.formatted((Object[]) rendered)),
            false);
    if (result.getExitCode() != 0) {
      throw new RuntimeException(
          "Failed to execute SQL in the default tenant database: " + result.getStderr());
    }
  }

  /** Queries a scalar value from the actual database used by Fineract's default tenant. */
  protected static String querySingleValueInDefaultTenant(String sql) {
    Container.ExecResult result = execPsql("fineract_default", sql, true);
    if (result.getExitCode() != 0) {
      throw new RuntimeException(
          "Failed to query the default tenant database: " + result.getStderr());
    }
    return result
        .getStdout()
        .lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .findFirst()
        .orElse("");
  }

  /**
   * Wraps a Java string value as a SQL literal, escaping single quotes. Returns the string {@code
   * NULL} for null input.
   */
  protected static String sqlLiteral(String value) {
    if (value == null) {
      return "NULL";
    }
    return "'" + value.replace("'", "''") + "'";
  }

  /** Wraps a Java value as a SQL literal for test statements executed via {@code psql}. */
  protected static String sqlLiteral(Object value) {
    return sqlLiteral(value == null ? null : String.valueOf(value));
  }

  private static Container.ExecResult execPsql(String sql, boolean tuplesOnly) {
    return execPsql(postgres.getDatabaseName(), sql, tuplesOnly);
  }

  private static Container.ExecResult execPsql(
      String databaseName, String sql, boolean tuplesOnly) {
    List<String> command = new ArrayList<>();
    command.add("psql");
    command.add("-v");
    command.add("ON_ERROR_STOP=1");
    command.add("-U");
    command.add(postgres.getUsername());
    command.add("-d");
    command.add(databaseName);
    if (tuplesOnly) {
      command.add("-t");
      command.add("-A");
    }
    command.add("-c");
    command.add(sql);
    try {
      return postgres.execInContainer(command.toArray(String[]::new));
    } catch (Exception e) {
      throw new RuntimeException("Failed to execute SQL in Postgres test container", e);
    }
  }
}
