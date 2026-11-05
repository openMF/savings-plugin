/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.restassured.response.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Real deployed plugin, native accounts, separate users, HTTP errors and service restart. */
class RatePropagationApiHttpIntegrationTest {
  static final String ROOT = "/v1/savingsproducts/policy/rate-propagation";
  static final List<Map<String, Object>> evidence = new ArrayList<>();
  static String checker, reader, date;
  private static Network network;
  private static PostgreSQLContainer<?> postgres;
  private static GenericContainer<?> fineract;

  @BeforeAll
  static void runtime() throws Exception {
    // This test restarts its server. It must never invalidate the shared native test harness.
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
            "CREATE DATABASE fineract_tenants");
    assertEquals(0, master.getExitCode(), master.getStderr());
    String image =
        System.getProperty(
            "fineract.it.image",
            System.getenv().getOrDefault("FINERACT_IT_IMAGE", "apache/fineract:develop"));
    Path jar = Path.of("target/savings-plugin-1.16.0-SNAPSHOT.jar");
    assertTrue(Files.isRegularFile(jar) && Files.size(jar) > 0);
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
            .withEnv("FINERACT_MODULE_SAVINGS_ENABLED", "true")
            .withEnv("SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING", "true")
            .withEnv("FINERACT_SERVER_SSL_ENABLED", "true")
            .withEnv("FINERACT_SERVER_PORT", "8443")
            .withEnv("TZ", "UTC")
            .withEnv("JAVA_TOOL_OPTIONS", "-Xmx2G")
            .withCopyFileToContainer(
                MountableFile.forHostPath(jar), "/app/plugins/savings-plugin.jar")
            .withCreateContainerCmdModifier(
                cmd -> {
                  cmd.withEntrypoint(
                      "sh",
                      "-c",
                      "CLASSPATH=$(cat /app/jib-classpath-file) && exec java $JAVA_TOOL_OPTIONS"
                          + " -Duser.home=/tmp -Dfile.encoding=UTF-8 -Duser.timezone=UTC -cp"
                          + " /app/plugins/savings-plugin.jar:$CLASSPATH"
                          + " org.apache.fineract.ServerApplication");
                  cmd.withCmd();
                })
            .waitingFor(
                Wait.forHttps("/fineract-provider/actuator/health")
                    .allowInsecure()
                    .forStatusCode(200)
                    .withStartupTimeout(java.time.Duration.ofMinutes(3)));
    fineract.start();
  }

  @AfterAll
  static void closeRuntime() {
    if (fineract != null) fineract.stop();
    if (postgres != null) postgres.stop();
    if (network != null) network.close();
  }

  private static String querySingleValueInDefaultTenant(String sql) {
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
              "-t",
              "-A",
              "-c",
              sql);
      assertEquals(0, result.getExitCode(), result.getStderr());
      return result.getStdout().strip();
    } catch (Exception e) {
      throw new IllegalStateException("Disposable native state query failed", e);
    }
  }

  private static int runtimePort() {
    // Docker can assign a new ephemeral host port on restart. Do not reuse cached container info.
    var bindings = fineract.getCurrentContainerInfo().getNetworkSettings().getPorts().getBindings();
    return Integer.parseInt(
        bindings.get(com.github.dockerjava.api.model.ExposedPort.tcp(8443))[0].getHostPortSpec());
  }

  Response call(String user, String method, String path, Object body, int status) {
    var spec = given(SavingsTestUtils.requestSpec(runtimePort()));
    if (user != null)
      spec.header(
          "Authorization",
          SavingsTestUtils.basicAuthHeader(
              user, user.equals("mifos") ? "password" : "Be05-review-26!"));
    String json =
        body == null
            ? null
            : body instanceof String ? (String) body : RatePropagationJson.JSON.toJson(body);
    if (json != null) spec.body(json);
    var response = spec.request(method, SavingsTestUtils.CONTEXT_PATH + "/api" + path);
    var record = new LinkedHashMap<String, Object>();
    record.put("method", method);
    record.put("path", path);
    record.put("actor", user == null ? "UNAUTHENTICATED" : user);
    if (json != null) {
      var sanitized = JsonParser.parseString(json).getAsJsonObject();
      sanitized.remove("password");
      sanitized.remove("repeatPassword");
      record.put("request", sanitized);
    }
    record.put("status", response.statusCode());
    // Restrict response capture to this feature. No authentication payloads or native customer
    // DTOs.
    if (path.startsWith(ROOT)) record.put("response", response.asString());
    evidence.add(record);
    assertEquals(
        status, response.statusCode(), () -> method + " " + path + ": " + response.asString());
    return response;
  }

  long post(String path, Object body) {
    return call("mifos", "POST", path, body, 200).jsonPath().getLong("resourceId");
  }

  Map<String, Object> action(Response b) {
    return Map.of(
        "expectedRevision",
        b.jsonPath().getLong("revision"),
        "reason",
        "Independently reviewed exact nonfinancial plan");
  }

  Response discover(Response b) {
    while (!b.jsonPath().getBoolean("planningComplete"))
      b =
          call(
              "mifos",
              "POST",
              ROOT + "/batches/" + b.jsonPath().getString("id") + "/discover?limit=1",
              action(b),
              200);
    return b;
  }

  void user(String name, long role) {
    post(
        "/v1/users",
        Map.ofEntries(
            Map.entry("username", name),
            Map.entry("firstname", "BE05"),
            Map.entry("lastname", "Reviewer"),
            Map.entry("email", name + "@example.invalid"),
            Map.entry("officeId", 1),
            Map.entry("roles", List.of(role)),
            Map.entry("sendPasswordToEmail", false),
            Map.entry("password", "Be05-review-26!"),
            Map.entry("repeatPassword", "Be05-review-26!"),
            Map.entry("passwordNeverExpires", true)));
  }

  String financial(long product) {
    return querySingleValueInDefaultTenant(
        "SELECT md5(COALESCE((SELECT string_agg(row_to_json(a)::text,',' ORDER BY a.id) FROM"
            + " m_savings_account a WHERE a.product_id="
            + product
            + "),'') || '|' || COALESCE((SELECT string_agg(row_to_json(t)::text,',' ORDER BY t.id)"
            + " FROM m_savings_account_transaction t JOIN m_savings_account a ON"
            + " a.id=t.savings_account_id WHERE a.product_id="
            + product
            + "),'') || '|' || (SELECT COUNT(*)::text FROM acc_gl_journal_entry) || '|' ||"
            + " COALESCE((SELECT string_agg(row_to_json(b)::text,',' ORDER BY b.savings_account_id)"
            + " FROM m_savings_account_policy b WHERE b.savings_product_id="
            + product
            + "),''))");
  }

  void restart() throws Exception {
    fineract.getDockerClient().restartContainerCmd(fineract.getContainerId()).exec();
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(150).toNanos();
    while (System.nanoTime() < deadline) {
      try {
        if (given(SavingsTestUtils.requestSpec(runtimePort()))
                .get(SavingsTestUtils.CONTEXT_PATH + "/actuator/health")
                .statusCode()
            == 200) return;
      } catch (Exception notReady) {
        /* Bounded readiness polling, never a successful substitute. */
      }
      Thread.sleep(1000);
    }
    fail("Real Fineract service did not recover after restart");
  }

  @Test
  void realPlanReviewBlockedAssessmentAndRestartPreserveAllNativeFinancialState() throws Exception {
    try {
      var local = Path.of("target/savings-plugin-1.16.0-SNAPSHOT.jar");
      String checksum =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(local)));
      var deployed = fineract.execInContainer("sha256sum", "/app/plugins/savings-plugin.jar");
      assertEquals(0, deployed.getExitCode());
      assertTrue(deployed.getStdout().startsWith(checksum));
      String properties =
          fineract.execInContainer("cat", "/app/resources/git.properties").getStdout();
      var metadata = new Properties();
      metadata.load(new java.io.StringReader(properties));
      evidence.add(
          Map.of(
              "pluginSha256",
              checksum,
              "fineractVersion",
              metadata.getProperty("git.build.version", "unknown"),
              "fineractCommit",
              metadata.getProperty("git.commit.id", "unknown"),
              "pinnedDependency",
              "0.0.1628-bc1c598"));
      call(null, "GET", ROOT + "/template", null, 401);
      assertFalse(
          call("mifos", "GET", ROOT + "/template", null, 200)
              .jsonPath()
              .getBoolean("financialExecutionSupported"));
      date =
          call("mifos", "GET", "/v2/base-teller/cash-allocations/context?officeId=1", null, 200)
              .jsonPath()
              .getString("businessDate");
      String suffix = UUID.randomUUID().toString().substring(0, 8);
      checker = "be05check" + suffix;
      reader = "be05read" + suffix;
      long superRole =
          call("mifos", "GET", "/v1/roles", null, 200)
              .jsonPath()
              .getLong("find { it.name == 'Super user' }.id");
      user(checker, superRole);
      long readRole =
          post(
              "/v1/roles",
              Map.of("name", "BE05 Reader " + suffix, "description", "Propagation read only"));
      call(
          "mifos",
          "PUT",
          "/v1/roles/" + readRole + "/permissions",
          Map.of("permissions", Map.of(RatePropagationService.READ, true)),
          200);
      user(reader, readRole);
      call("mifos", "PUT", "/v1/currencies", Map.of("currencies", List.of("MXN", "USD")), 200);
      long product =
          post(
              "/v1/savingsproducts",
              Map.ofEntries(
                  Map.entry("name", "BE05 " + suffix),
                  Map.entry("shortName", suffix.substring(0, 4)),
                  Map.entry("description", "Disposable propagation verification"),
                  Map.entry("currencyCode", "MXN"),
                  Map.entry("digitsAfterDecimal", 2),
                  Map.entry("inMultiplesOf", 1),
                  Map.entry("nominalAnnualInterestRate", 6),
                  Map.entry("interestCompoundingPeriodType", 1),
                  Map.entry("interestPostingPeriodType", 4),
                  Map.entry("interestCalculationType", 1),
                  Map.entry("interestCalculationDaysInYearType", 365),
                  Map.entry("minRequiredOpeningBalance", 0),
                  Map.entry("accountingRule", 1),
                  Map.entry("locale", "en")));
      String policy =
          ProductPolicyValidationTest.JSON
              .replace(
                  "\"classification\":\"SIGHT\"", "\"classification\":\"INTEREST_BEARING_SIGHT\"")
              .replace("\"interestMode\":\"NONE\"", "\"interestMode\":\"FIXED\"");
      String policyPath = "/v1/savingsproducts/policy/" + product;
      call("mifos", "PUT", policyPath, policy, 200);
      call(
          "mifos",
          "POST",
          policyPath + "/submit",
          Map.of("expectedRevision", 1, "effectiveDate", date),
          200);
      call(
          checker,
          "POST",
          policyPath + "/versions/1/approve",
          Map.of("reason", "Independent source review"),
          200);
      long cashPayment =
          post(
              "/v1/paymenttypes",
              Map.of("name", "BE05 cash " + suffix, "isCashPayment", true, "position", 1));
      List<Long> accounts = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        long client =
            post(
                "/v1/clients",
                Map.of(
                    "officeId",
                    1,
                    "legalFormId",
                    1,
                    "firstname",
                    "SyntheticBE05",
                    "lastname",
                    suffix + i,
                    "active",
                    true,
                    "activationDate",
                    date,
                    "dateFormat",
                    "yyyy-MM-dd",
                    "locale",
                    "en"));
        long account =
            call(
                    "mifos",
                    "POST",
                    policyPath + "/applications",
                    Map.of("clientId", client, "expectedPolicyVersion", 1),
                    200)
                .jsonPath()
                .getLong("savingsId");
        call(
            "mifos",
            "POST",
            "/v1/savingsaccounts/" + account + "?command=approve",
            Map.of("approvedOnDate", date, "dateFormat", "yyyy-MM-dd", "locale", "en"),
            200);
        call(
            "mifos",
            "POST",
            "/v1/savingsaccounts/" + account + "?command=activate",
            Map.of("activatedOnDate", date, "dateFormat", "yyyy-MM-dd", "locale", "en"),
            200);
        call(
            "mifos",
            "POST",
            "/v1/savingsaccounts/" + account + "/transactions?command=deposit",
            Map.of(
                "transactionDate",
                date,
                "transactionAmount",
                1000,
                "paymentTypeId",
                cashPayment,
                "dateFormat",
                "yyyy-MM-dd",
                "locale",
                "en"),
            200);
        accounts.add(account);
      }
      String before = financial(product);
      Map<String, Object> plan = Map.of("policyVersion", 1, "effectiveDate", date);
      var preview = call("mifos", "POST", ROOT + "/" + product + "/preview", plan, 200);
      assertEquals(3, preview.jsonPath().getInt("population.totalMatching"));
      assertEquals(3, preview.jsonPath().getInt("population.totalBlocked"));
      call(reader, "POST", ROOT + "/" + product + "/batches", plan, 403);
      call(
          "mifos",
          "POST",
          ROOT + "/" + product + "/preview",
          Map.of("policyVersion", 999, "effectiveDate", date),
          400);
      call("mifos", "GET", ROOT + "/batches/" + UUID.randomUUID(), null, 404);
      var create = new LinkedHashMap<String, Object>(plan);
      create.put("requestKey", UUID.randomUUID().toString());
      var b = call("mifos", "POST", ROOT + "/" + product + "/batches", create, 200);
      String id = b.jsonPath().getString("id"), path = ROOT + "/batches/" + id;
      assertEquals(
          id,
          call("mifos", "POST", ROOT + "/" + product + "/batches", create, 200)
              .jsonPath()
              .getString("id"));
      b = discover(b);
      b = call("mifos", "POST", path + "/submit", action(b), 200);
      call("mifos", "POST", path + "/approve", action(b), 403);
      call(reader, "POST", path + "/approve", action(b), 403);
      b = call(checker, "POST", path + "/approve", action(b), 200);
      assertEquals("AUTHORIZED", b.jsonPath().getString("status"));
      call(reader, "POST", path + "/start", action(b), 403);
      assertTrue(
          call("mifos", "POST", path + "/start", action(b), 409)
              .jsonPath()
              .getString("code")
              .contains("UNSUPPORTED"));
      b = call("mifos", "POST", path + "/assess?limit=1", action(b), 200);
      assertEquals(2, b.jsonPath().getInt("counters.totalPending"));
      long revision = b.jsonPath().getLong("revision");
      String sourceHash = b.jsonPath().getString("sourceHash");
      restart();
      evidence.add(Map.of("serviceRestart", "CONFIRMED"));
      b = call("mifos", "GET", path, null, 200);
      assertEquals(revision, b.jsonPath().getLong("revision"));
      assertEquals(sourceHash, b.jsonPath().getString("sourceHash"));
      b = call("mifos", "POST", path + "/assess?limit=2", action(b), 200);
      assertEquals("BLOCKED", b.jsonPath().getString("status"));
      assertEquals(3, b.jsonPath().getInt("counters.totalBlocked"));
      assertEquals(0, b.jsonPath().getInt("counters.totalApplied"));
      long done = b.jsonPath().getLong("revision");
      b = call("mifos", "POST", path + "/assess?limit=2", action(b), 200);
      assertEquals(done, b.jsonPath().getLong("revision"));
      for (int i = 0; i < 2; i++) call("mifos", "POST", path + "/start", action(b), 409);
      assertEquals(
          3,
          call(reader, "GET", path + "/accounts?limit=100", null, 200)
              .jsonPath()
              .getList("items")
              .size());
      assertEquals(
          3,
          call(reader, "GET", path + "/attempts?limit=100", null, 200)
              .jsonPath()
              .getList("items")
              .size());
      assertTrue(
          call(reader, "GET", path + "/audit?limit=2", null, 200).jsonPath().getBoolean("hasMore"));
      call(
          "mifos",
          "PUT",
          "/v1/savingsaccounts/" + accounts.getFirst(),
          Map.of("nominalAnnualInterestRate", 9, "locale", "en"),
          400);
      assertEquals(before, financial(product));
      String tomorrow = LocalDate.parse(date).plusDays(1).toString();
      var future =
          discover(
              call(
                  "mifos",
                  "POST",
                  ROOT + "/" + product + "/batches",
                  Map.of(
                      "requestKey",
                      UUID.randomUUID().toString(),
                      "policyVersion",
                      1,
                      "effectiveDate",
                      tomorrow),
                  200));
      String futurePath = ROOT + "/batches/" + future.jsonPath().getString("id");
      future = call("mifos", "POST", futurePath + "/submit", action(future), 200);
      future = call(checker, "POST", futurePath + "/approve", action(future), 200);
      assertEquals("SCHEDULED", future.jsonPath().getString("status"));
      assertEquals(
          "EFFECTIVE_DATE_NOT_REACHED",
          call("mifos", "POST", futurePath + "/start", action(future), 409)
              .jsonPath()
              .getString("code"));
      call("mifos", "POST", futurePath + "/assess", action(future), 409);
      call("mifos", "POST", futurePath + "/cancel", action(future), 200);
      assertEquals(before, financial(product));
      evidence.add(Map.of("nativeFinancialState", "UNCHANGED", "accounts", accounts, "batch", id));
    } finally {
      Files.createDirectories(Path.of("target"));
      Files.writeString(
          Path.of("target/be05-runtime-evidence.json"), RatePropagationJson.JSON.toJson(evidence));
    }
  }
}
