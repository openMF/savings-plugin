/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/**
 * Owns the pinned BE-06 runtime without changing the shared integration suites' image or tenant.
 */
abstract class ProductTransactionNativeTestSupport {
  private static Network network;
  private static PostgreSQLContainer<?> postgres;
  protected static GenericContainer<?> fineract;

  @BeforeAll
  static void startNativeRuntime() throws Exception {
    // Maven prepares only the resolved runtime dependencies at pre-integration-test. Do not use
    // the Surefire classpath, cached image tags, or a manually prepared local libs directory.
    String classpath = Files.readString(Path.of("target/be06-runtime-classpath.txt")).strip();
    if (classpath.isEmpty()) throw new IllegalStateException("BE-06 runtime classpath is empty.");
    var image =
        new ImageFromDockerfile()
            .withFileFromClasspath("Dockerfile", "be06/Dockerfile")
            .withFileFromClasspath("jib-classpath-file", "be06/jib-classpath-file");
    var names = new HashSet<String>();
    for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
      Path jar = Path.of(entry);
      String name = jar.getFileName().toString();
      if (!Files.isRegularFile(jar) || !name.endsWith(".jar"))
        throw new IllegalStateException("Invalid BE-06 runtime dependency: " + jar);
      // Different Maven groups can publish the same filename (for example JAXB). Keep both.
      while (!names.add(name)) name = "dependency-" + name;
      image.withFileFromPath("libs/" + name, jar);
    }
    Path plugin = Path.of("target/savings-plugin-1.16.0-SNAPSHOT.jar");
    if (!Files.isRegularFile(plugin) || Files.size(plugin) == 0)
      throw new IllegalStateException(
          "Package the savings plugin before native integration tests.");

    network = Network.newNetwork();
    postgres =
        new PostgreSQLContainer<>("postgres:15-alpine")
            .withNetwork(network)
            .withNetworkAliases("db")
            .withDatabaseName("fineract_default")
            .withUsername("postgres")
            .withPassword("postgres");
    postgres.start();
    var master =
        postgres.execInContainer(
            "psql",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            "postgres",
            "-c",
            "CREATE DATABASE fineract_tenants;");
    if (master.getExitCode() != 0) throw new IllegalStateException(master.getStderr());

    fineract =
        new GenericContainer<>(image)
            .withNetwork(network)
            .withExposedPorts(8443)
            .withEnv("FINERACT_HIKARI_JDBC_URL", "jdbc:postgresql://db:5432/fineract_tenants")
            .withEnv("FINERACT_HIKARI_USERNAME", "postgres")
            .withEnv("FINERACT_HIKARI_PASSWORD", "postgres")
            .withEnv("FINERACT_HIKARI_DRIVER_SOURCE_CLASS_NAME", "org.postgresql.Driver")
            .withEnv("FINERACT_DEFAULT_TENANTDB_HOSTNAME", "db")
            .withEnv("FINERACT_DEFAULT_TENANTDB_PORT", "5432")
            .withEnv("FINERACT_DEFAULT_TENANTDB_UID", "postgres")
            .withEnv("FINERACT_DEFAULT_TENANTDB_PWD", "postgres")
            .withEnv("FINERACT_DEFAULT_TENANTDB_CONN_PARAMS", "")
            .withEnv("FINERACT_DEFAULT_TENANTDB_TIMEZONE", "UTC")
            .withEnv("FINERACT_MODULE_SAVINGS_ENABLED", "true")
            .withEnv("SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING", "true")
            .withEnv("FINERACT_SERVER_SSL_ENABLED", "true")
            .withEnv("FINERACT_SERVER_PORT", "8443")
            .withEnv("TZ", "UTC")
            .withEnv("JAVA_TOOL_OPTIONS", "-Xmx2G")
            .withCopyFileToContainer(
                MountableFile.forHostPath(plugin), "/app/plugins/savings-plugin.jar")
            .withCreateContainerCmdModifier(
                cmd -> {
                  cmd.withEntrypoint(
                      "sh",
                      "-c",
                      "CLASSPATH=$(cat /app/jib-classpath-file) && exec java $JAVA_TOOL_OPTIONS"
                          + " -Duser.home=/tmp -Dfile.encoding=UTF-8 -Duser.timezone=UTC"
                          + " -cp /app/plugins/savings-plugin.jar:$CLASSPATH"
                          + " org.apache.fineract.ServerApplication");
                  cmd.withCmd();
                })
            .withLogConsumer(
                new Slf4jLogConsumer(
                        LoggerFactory.getLogger(ProductTransactionNativeTestSupport.class))
                    .withPrefix("be06-fineract"))
            .waitingFor(
                Wait.forHttps("/fineract-provider/actuator/health")
                    .allowInsecure()
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(7)));
    fineract.start();
  }

  @AfterAll
  static void stopNativeRuntime() throws Exception {
    try {
      if (fineract != null && fineract.getContainerId() != null)
        Files.writeString(Path.of("target/be06-fineract.log"), fineract.getLogs());
    } finally {
      try {
        if (fineract != null) fineract.stop();
      } finally {
        try {
          if (postgres != null) postgres.stop();
        } finally {
          if (network != null) network.close();
        }
      }
    }
  }

  protected static int getFineractPort() {
    return fineract.getMappedPort(8443);
  }

  protected static void executeSqlInDefaultTenant(String sql, Object... parameters) {
    Object[] literals = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++)
      literals[i] =
          parameters[i] == null ? "NULL" : "'" + parameters[i].toString().replace("'", "''") + "'";
    psql("BEGIN; " + sql.formatted(literals) + " COMMIT;");
  }

  protected static String querySingleValueInDefaultTenant(String sql) {
    return psql(sql).strip();
  }

  private static String psql(String sql) {
    try {
      var result =
          postgres.execInContainer(
              "psql",
              "-v",
              "ON_ERROR_STOP=1",
              "-U",
              "postgres",
              "-d",
              "fineract_default",
              "-At",
              "-c",
              sql);
      if (result.getExitCode() != 0) throw new IllegalStateException(result.getStderr());
      return result.getStdout();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while querying the BE-06 tenant.", e);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Failed to query the BE-06 tenant.", e);
    }
  }
}
