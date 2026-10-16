package org.apache.fineract.baseteller.transfer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Separate native tenant: currency/office/security fixtures must not change other teller suites.
 */
abstract class AccountTransferNativeTestSupport {
  private static final Network NETWORK = Network.newNetwork();
  private static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:15-alpine")
          .withNetwork(NETWORK)
          .withNetworkAliases("db")
          .withDatabaseName("fineract_tenants")
          .withUsername("anvaykharb")
          .withPassword("transfer-test");
  private static GenericContainer<?> fineract;

  @BeforeAll
  static void startNativeTenant() throws Exception {
    DATABASE.start();
    var created = DATABASE.execInContainer("createdb", "-U", "anvaykharb", "fineract_default");
    if (created.getExitCode() != 0) throw new IllegalStateException(created.getStderr());
    String image =
        System.getProperty(
            "fineract.it.image",
            System.getenv().getOrDefault("FINERACT_IT_IMAGE", "apache/fineract:develop"));
    Path plugin = Path.of("target/savings-plugin-1.16.0-SNAPSHOT.jar");
    fineract =
        new GenericContainer<>(image)
            .withNetwork(NETWORK)
            .withExposedPorts(8443)
            .withEnv("FINERACT_HIKARI_JDBC_URL", "jdbc:postgresql://db:5432/fineract_tenants")
            .withEnv("FINERACT_HIKARI_USERNAME", "anvaykharb")
            .withEnv("FINERACT_HIKARI_PASSWORD", "transfer-test")
            .withEnv("FINERACT_HIKARI_DRIVER_SOURCE_CLASS_NAME", "org.postgresql.Driver")
            .withEnv("FINERACT_DEFAULT_TENANTDB_HOSTNAME", "db")
            .withEnv("FINERACT_DEFAULT_TENANTDB_PORT", "5432")
            .withEnv("FINERACT_DEFAULT_TENANTDB_UID", "anvaykharb")
            .withEnv("FINERACT_DEFAULT_TENANTDB_PWD", "transfer-test")
            .withEnv("FINERACT_DEFAULT_TENANTDB_CONN_PARAMS", "")
            .withEnv("FINERACT_MODULE_SAVINGS_ENABLED", "true")
            .withEnv("SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING", "true")
            .withEnv("TZ", "UTC")
            .withEnv("JAVA_TOOL_OPTIONS", "-Xmx2G")
            .withEnv("FINERACT_SERVER_SSL_ENABLED", "true")
            .withEnv("FINERACT_SERVER_PORT", "8443")
            .withCopyFileToContainer(
                MountableFile.forHostPath(plugin), "/app/plugins/savings-plugin.jar")
            .withCreateContainerCmdModifier(
                cmd -> {
                  cmd.withEntrypoint(
                      "sh",
                      "-c",
                      "CLASSPATH=$(cat /app/jib-classpath-file) && exec java $JAVA_TOOL_OPTIONS"
                          + " -Duser.home=/tmp -Dfile.encoding=UTF-8 -Duser.timezone=UTC"
                          + " -Djava.security.egd=file:/dev/./urandom -cp"
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
  }

  @AfterAll
  static void stopNativeTenant() {
    if (fineract != null) fineract.stop();
    DATABASE.stop();
    NETWORK.close();
  }

  protected static int getFineractPort() {
    return fineract.getMappedPort(8443);
  }

  protected static String querySingleValueInDefaultTenant(String sql) {
    return psql(sql, true).strip();
  }

  protected static void executeSqlInDefaultTenant(String sql) {
    psql(sql, false);
  }

  protected static String sqlLiteral(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static String psql(String sql, boolean scalar) {
    var command =
        new java.util.ArrayList<>(
            List.of("psql", "-U", "anvaykharb", "-d", "fineract_default", "-v", "ON_ERROR_STOP=1"));
    if (scalar) command.add("-At");
    command.add("-c");
    command.add(sql);
    try {
      var result = DATABASE.execInContainer(command.toArray(String[]::new));
      if (result.getExitCode() != 0) throw new IllegalStateException(result.getStderr());
      return result.getStdout();
    } catch (java.io.IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }
}
