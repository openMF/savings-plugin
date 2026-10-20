package org.apache.fineract.baseteller.foreigncurrency;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Owns an isolated native tenant so FX currency/security fixtures cannot affect other suites. */
final class FxNativeTestSupport implements AutoCloseable {
  private final Network network = Network.newNetwork();
  private final PostgreSQLContainer<?> database =
      new PostgreSQLContainer<>("postgres:15-alpine")
          .withNetwork(network)
          .withNetworkAliases("db")
          .withDatabaseName("fineract_tenants")
          .withUsername("fx_test")
          .withPassword("foreign-currency-test");
  private GenericContainer<?> fineract;

  FxNativeTestSupport() throws IOException, InterruptedException {
    try {
      database.start();
      var created =
          database.execInContainer("createdb", "-U", database.getUsername(), "fineract_default");
      if (created.getExitCode() != 0) throw new IllegalStateException(created.getStderr());
      String image =
          System.getProperty(
              "fineract.it.image",
              System.getenv().getOrDefault("FINERACT_IT_IMAGE", "apache/fineract:develop"));
      fineract =
          new GenericContainer<>(image)
              .withNetwork(network)
              .withExposedPorts(8443)
              .withEnv("FINERACT_HIKARI_JDBC_URL", "jdbc:postgresql://db:5432/fineract_tenants")
              .withEnv("FINERACT_HIKARI_USERNAME", database.getUsername())
              .withEnv("FINERACT_HIKARI_PASSWORD", database.getPassword())
              .withEnv("FINERACT_HIKARI_DRIVER_SOURCE_CLASS_NAME", "org.postgresql.Driver")
              .withEnv("FINERACT_DEFAULT_TENANTDB_HOSTNAME", "db")
              .withEnv("FINERACT_DEFAULT_TENANTDB_PORT", "5432")
              .withEnv("FINERACT_DEFAULT_TENANTDB_UID", database.getUsername())
              .withEnv("FINERACT_DEFAULT_TENANTDB_PWD", database.getPassword())
              .withEnv("FINERACT_DEFAULT_TENANTDB_CONN_PARAMS", "")
              .withEnv("FINERACT_MODULE_SAVINGS_ENABLED", "true")
              .withEnv("SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING", "true")
              .withEnv("TZ", "UTC")
              .withEnv("JAVA_TOOL_OPTIONS", "-Xmx2G")
              .withEnv("FINERACT_SERVER_SSL_ENABLED", "true")
              .withEnv("FINERACT_SERVER_PORT", "8443")
              .withCopyFileToContainer(
                  MountableFile.forHostPath(Path.of("target/savings-plugin-1.16.0-SNAPSHOT.jar")),
                  "/app/plugins/savings-plugin.jar")
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
    } catch (IOException | InterruptedException | RuntimeException e) {
      close();
      throw e;
    }
  }

  String baseUrl() {
    return "https://localhost:" + fineract.getMappedPort(8443);
  }

  String read(String sql) throws IOException, InterruptedException {
    var result =
        database.execInContainer(
            "psql",
            "-U",
            database.getUsername(),
            "-d",
            "fineract_default",
            "-v",
            "ON_ERROR_STOP=1",
            "-At",
            "-c",
            sql);
    if (result.getExitCode() != 0) throw new IllegalStateException(result.getStderr());
    return result.getStdout().strip();
  }

  @Override
  public void close() {
    try {
      if (fineract != null) fineract.stop();
    } finally {
      try {
        database.stop();
      } finally {
        network.close();
      }
    }
  }
}
