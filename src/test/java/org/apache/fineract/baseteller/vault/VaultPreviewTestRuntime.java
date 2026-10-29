/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Fresh real Fineract tenants, or explicitly supplied disposable local Phase 2/3 databases. */
final class VaultPreviewTestRuntime implements AutoCloseable {
  private Network network;
  private PostgreSQLContainer<?> database;
  private GenericContainer<?> fineract;
  private Connection primary;
  private Connection secondary;
  private String baseUrl;

  VaultPreviewTestRuntime() throws Exception {
    try {
      baseUrl = System.getProperty("vault.preview.it.baseUrl");
      if (baseUrl != null) {
        primary = external("vault.preview.it.jdbcUrl");
        secondary = external("vault.preview.it.secondaryJdbcUrl");
        return;
      }
      network = Network.newNetwork();
      database =
          new PostgreSQLContainer<>("postgres:15-alpine")
              .withNetwork(network)
              .withNetworkAliases("db")
              .withDatabaseName("fineract_tenants")
              .withUsername("vault_preview")
              .withPassword("preview-test-only");
      database.start();
      command("createdb", "-U", database.getUsername(), "fineract_default");
      fineract =
          new GenericContainer<>(
                  System.getProperty(
                      "fineract.it.image",
                      System.getenv().getOrDefault("FINERACT_IT_IMAGE", "apache/fineract:develop")))
              .withNetwork(network)
              .withExposedPorts(8443)
              .withEnv(
                  Map.ofEntries(
                      Map.entry(
                          "FINERACT_HIKARI_JDBC_URL", "jdbc:postgresql://db:5432/fineract_tenants"),
                      Map.entry("FINERACT_HIKARI_USERNAME", database.getUsername()),
                      Map.entry("FINERACT_HIKARI_PASSWORD", database.getPassword()),
                      Map.entry(
                          "FINERACT_HIKARI_DRIVER_SOURCE_CLASS_NAME", "org.postgresql.Driver"),
                      Map.entry("FINERACT_DEFAULT_TENANTDB_HOSTNAME", "db"),
                      Map.entry("FINERACT_DEFAULT_TENANTDB_PORT", "5432"),
                      Map.entry("FINERACT_DEFAULT_TENANTDB_UID", database.getUsername()),
                      Map.entry("FINERACT_DEFAULT_TENANTDB_PWD", database.getPassword()),
                      Map.entry("FINERACT_DEFAULT_TENANTDB_CONN_PARAMS", ""),
                      Map.entry("FINERACT_DEFAULT_TENANTDB_TIMEZONE", "UTC"),
                      Map.entry("FINERACT_MODULE_SAVINGS_ENABLED", "true"),
                      Map.entry("SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING", "true"),
                      Map.entry("FINERACT_MODE_BATCH_MANAGER_ENABLED", "false"),
                      Map.entry("FINERACT_MODE_BATCH_WORKER_ENABLED", "false"),
                      Map.entry("FINERACT_SERVER_SSL_ENABLED", "true"),
                      Map.entry("FINERACT_SERVER_PORT", "8443"),
                      Map.entry("TZ", "UTC"),
                      Map.entry("JAVA_TOOL_OPTIONS", "-Xmx2G")))
              .withCopyFileToContainer(
                  MountableFile.forHostPath(Path.of("target/savings-plugin-1.16.0-SNAPSHOT.jar")),
                  "/app/plugins/savings-plugin.jar")
              .withCreateContainerCmdModifier(
                  cmd -> {
                    cmd.withEntrypoint(
                        "sh",
                        "-c",
                        "CLASSPATH=$(cat /app/jib-classpath-file) && exec java $JAVA_TOOL_OPTIONS"
                            + " -Duser.home=/tmp -Duser.timezone=UTC -cp"
                            + " /app/plugins/savings-plugin.jar:$CLASSPATH"
                            + " org.apache.fineract.ServerApplication");
                    cmd.withCmd();
                  })
              .waitingFor(
                  Wait.forHttps("/fineract-provider/actuator/health")
                      .allowInsecure()
                      .forStatusCode(200)
                      .withStartupTimeout(Duration.ofMinutes(7)));
      fineract.start();
      baseUrl = "https://localhost:" + fineract.getMappedPort(8443);
      // Clone only our freshly migrated, empty tenant. No shared/demo database is read or changed.
      command(
          "pg_dump",
          "-U",
          database.getUsername(),
          "--no-owner",
          "--no-privileges",
          "-f",
          "/tmp/preview-empty.sql",
          "fineract_default");
      command("createdb", "-U", database.getUsername(), "fineract_secondary");
      command(
          "psql",
          "-U",
          database.getUsername(),
          "-d",
          "fineract_secondary",
          "-v",
          "ON_ERROR_STOP=1",
          "-f",
          "/tmp/preview-empty.sql");
      command(
          "psql",
          "-U",
          database.getUsername(),
          "-d",
          "fineract_tenants",
          "-v",
          "ON_ERROR_STOP=1",
          "-c",
          "INSERT INTO"
              + " tenant_server_connections(schema_server,schema_name,schema_server_port,schema_username,schema_password,master_password_hash,schema_connection_parameters)"
              + " SELECT"
              + " schema_server,'fineract_secondary',schema_server_port,schema_username,schema_password,master_password_hash,schema_connection_parameters"
              + " FROM tenant_server_connections WHERE schema_name='fineract_default';INSERT INTO"
              + " tenants(identifier,name,timezone_id,oltp_id,report_id) SELECT"
              + " 'vault-preview-secondary','Preview secondary','UTC',id,id FROM"
              + " tenant_server_connections WHERE schema_name='fineract_secondary';");
      String prefix = "jdbc:postgresql://localhost:" + database.getMappedPort(5432) + "/";
      primary =
          DriverManager.getConnection(
              prefix + "fineract_default", database.getUsername(), database.getPassword());
      secondary =
          DriverManager.getConnection(
              prefix + "fineract_secondary", database.getUsername(), database.getPassword());
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  String baseUrl() {
    return baseUrl;
  }

  Connection primary() {
    return primary;
  }

  Connection secondary() {
    return secondary;
  }

  private static Connection external(String property) throws Exception {
    String url = System.getProperty(property);
    if (url == null) throw new IllegalArgumentException("Both disposable JDBC URLs are required.");
    var c =
        DriverManager.getConnection(
            url,
            System.getProperty("vault.preview.it.dbUser", System.getProperty("user.name")),
            System.getProperty("vault.preview.it.dbPassword", ""));
    try (var statement = c.createStatement();
        var rows = statement.executeQuery("SELECT current_database()")) {
      rows.next();
      if (!rows.getString(1).startsWith("web1328p2_")
          && !rows.getString(1).startsWith("web1328p3_")
          && !rows.getString(1).startsWith("web1328p4_")) {
        c.close();
        throw new IllegalArgumentException(
            "Refusing fixture writes outside disposable web1328p2_*, web1328p3_* or web1328p4_*"
                + " databases.");
      }
    }
    return c;
  }

  private void command(String... args) throws Exception {
    var result = database.execInContainer(args);
    if (result.getExitCode() != 0)
      throw new IllegalStateException("Disposable tenant provisioning failed.");
  }

  @Override
  public void close() throws Exception {
    try {
      if (primary != null) primary.close();
      if (secondary != null) secondary.close();
      if (fineract != null)
        Files.writeString(Path.of("target/vault-preview-fineract.log"), fineract.getLogs());
    } finally {
      try {
        if (fineract != null) fineract.stop();
      } finally {
        try {
          if (database != null) database.stop();
        } finally {
          if (network != null) network.close();
        }
      }
    }
  }
}
