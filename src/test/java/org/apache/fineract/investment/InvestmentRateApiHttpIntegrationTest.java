/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static io.restassured.RestAssured.given;
import static org.apache.fineract.investment.InvestmentProductFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.restassured.response.Response;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.apache.fineract.infrastructure.core.exceptionmapper.AccessDeniedExceptionMapper;
import org.apache.fineract.infrastructure.core.exceptionmapper.PlatformApiDataValidationExceptionMapper;
import org.apache.fineract.infrastructure.core.exceptionmapper.PlatformResourceNotFoundExceptionMapper;
import org.apache.fineract.infrastructure.core.exceptionmapper.UnAuthenticatedUserExceptionMapper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.exception.UnAuthenticatedUserException;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;

/**
 * Real loopback HTTP/Jersey + PostgreSQL + transaction proxy. Identity is a test security adapter,
 * not the native Fineract authentication filter; native deployment verification remains separate.
 */
class InvestmentRateApiHttpIntegrationTest extends InvestmentRateDatabaseSupport {
  static final String ROOT = "/v1/investment-products/";
  final ThreadLocal<String> identity = new ThreadLocal<>();
  HttpServer server;
  org.springframework.context.support.GenericApplicationContext context;
  ApplicationHandler application;
  String base;

  @BeforeEach
  void httpServer() throws Exception {
    var reader = mock(AppUser.class);
    when(reader.getId()).thenReturn(2L);
    doThrow(new AccessDeniedException("Read only"))
        .when(reader)
        .validateHasPermissionTo(InvestmentRateService.MANAGE);
    doThrow(new AccessDeniedException("Read only"))
        .when(reader)
        .validateHasPermissionTo(InvestmentRateService.PUBLISH);
    var checker = mock(AppUser.class);
    when(checker.getId()).thenReturn(2L);
    doThrow(new AccessDeniedException("Checker only"))
        .when(checker)
        .validateHasPermissionTo(InvestmentRateService.MANAGE);
    var denied = mock(AppUser.class);
    doThrow(new AccessDeniedException("Forbidden"))
        .when(denied)
        .validateHasPermissionTo(anyString());
    when(security.authenticatedUser())
        .thenAnswer(
            invocation -> {
              String user = identity.get();
              if (user == null) throw new UnAuthenticatedUserException();
              return switch (user) {
                case "admin" -> actor;
                case "reader" -> reader;
                case "checker" -> checker;
                default -> denied;
              };
            });
    context = new org.springframework.context.support.GenericApplicationContext();
    var resource = new InvestmentRateApiResource(rates);
    context.registerBean(InvestmentRateApiResource.class, () -> resource);
    context.refresh();
    var app =
        new ApplicationHandler(
            new ResourceConfig()
                .property("contextConfig", context)
                .register(resource)
                .register(JacksonFeature.class)
                .register(PlatformApiDataValidationExceptionMapper.class)
                .register(PlatformResourceNotFoundExceptionMapper.class)
                .register(AccessDeniedExceptionMapper.class)
                .register(UnAuthenticatedUserExceptionMapper.class));
    application = app;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/",
        exchange -> {
          try {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null && auth.startsWith("Basic "))
              identity.set(
                  new String(
                          java.util.Base64.getDecoder().decode(auth.substring(6)),
                          StandardCharsets.UTF_8)
                      .split(":", 2)[0]);
            String tenant = exchange.getRequestHeaders().getFirst("Fineract-Platform-TenantId");
            if ("one".equals(tenant) || "two".equals(tenant)) rateTenant(tenant, "UTC");
            var request =
                new ContainerRequest(
                    URI.create(base + "/"),
                    URI.create(base + exchange.getRequestURI()),
                    exchange.getRequestMethod(),
                    null,
                    new MapPropertiesDelegate());
            exchange
                .getRequestHeaders()
                .forEach((key, values) -> request.getHeaders().put(key, values));
            request.setEntityStream(exchange.getRequestBody());
            var body = new ByteArrayOutputStream();
            var response = app.apply(request, body).get();
            response
                .getStringHeaders()
                .forEach((key, values) -> exchange.getResponseHeaders().put(key, values));
            byte[] bytes = body.toByteArray();
            exchange.sendResponseHeaders(response.getStatus(), bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (Exception e) {
            throw new java.io.IOException(e);
          } finally {
            exchange.close();
            identity.remove();
            ThreadLocalContextUtil.reset();
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
    if (application != null) application.getInjectionManager().shutdown();
    if (context != null) context.close();
  }

  Response call(String user, String tenant, String method, String path, String body, int expected) {
    var request =
        given()
            .baseUri(base)
            .header("Fineract-Platform-TenantId", tenant)
            .contentType("application/json");
    if (user != null) request.auth().preemptive().basic(user, "synthetic-test-secret");
    if (body != null) request.body(body);
    var response = request.request(method, ROOT + product + "/rates" + path);
    assertEquals(expected, response.statusCode(), response::asString);
    return response;
  }

  @Test
  void authorizedLifecycleAndHistoricalSchedulesOverHttp() {
    call("reader", "one", "GET", "/template", null, 200);
    call("reader", "one", "GET", "/draft", null, 404);
    call("reader", "one", "GET", "", null, 404);
    var missing = call("reader", "one", "GET", "/selection?amount=5000&termDays=90", null, 200);
    assertEquals("NO_APPLICABLE_RATE", missing.jsonPath().getString("status"));
    var validated = call("admin", "one", "POST", "/validate", InvestmentRateFixtures.BASE, 200);
    assertTrue(validated.jsonPath().getBoolean("valid"));
    assertTrue(
        call("reader", "one", "GET", "/history", null, 200).jsonPath().getList("$").isEmpty());
    call("admin", "one", "PUT", "/draft", InvestmentRateFixtures.BASE, 200);
    call("admin", "one", "POST", "/publish", "{\"expectedRevision\":1}", 403);
    var published = call("checker", "one", "POST", "/publish", "{\"expectedRevision\":1}", 200);
    assertEquals("APPROVED_EFFECTIVE", published.jsonPath().getString("status"));
    assertFalse(published.jsonPath().getBoolean("capabilities.financialExecutionSupported"));
    call("reader", "one", "GET", "", null, 200);
    assertEquals(
        10,
        call("reader", "one", "GET", "/selection?amount=5000.00&termDays=90", null, 200)
            .jsonPath()
            .getInt("annualRatePercent"));
    call(
        "admin",
        "one",
        "PUT",
        "/draft",
        InvestmentRateFixtures.request(1, "2026-11-18", "11.123456"),
        200);
    assertEquals(
        10,
        call("reader", "one", "GET", "/selection?amount=5000&termDays=90", null, 200)
            .jsonPath()
            .getInt("annualRatePercent"));
    call("checker", "one", "POST", "/publish", "{\"expectedRevision\":2}", 200);
    assertEquals(
        1, call("reader", "one", "GET", "/schedule", null, 200).jsonPath().getList("$").size());
    var selected =
        call(
            "reader",
            "one",
            "GET",
            "/selection?amount=5000&termDays=90&asOfInstant=2026-11-18T00:00:00Z",
            null,
            200);
    assertTrue(selected.asString().contains("11.123456"));
    assertEquals(
        1, call("reader", "one", "GET", "/versions/1", null, 200).jsonPath().getInt("version"));
    assertEquals(
        2, call("reader", "one", "GET", "/versions", null, 200).jsonPath().getList("$").size());
    assertEquals(
        4, call("reader", "one", "GET", "/history", null, 200).jsonPath().getList("$").size());
    assertEquals(
        "NO_APPLICABLE_RATE",
        call("reader", "two", "GET", "/selection?amount=5000&termDays=90", null, 200)
            .jsonPath()
            .getString("status"));
  }

  @Test
  void permissionsValidationConflictsAndPrecisePublicationBlockersOverHttp() {
    call(null, "one", "GET", "/template", null, 401);
    call("denied", "one", "GET", "/template", null, 403);
    call("reader", "one", "GET", "/history?limit=201", null, 400);
    call("reader", "one", "PUT", "/draft", InvestmentRateFixtures.BASE, 403);
    call("reader", "one", "POST", "/validate", InvestmentRateFixtures.BASE, 403);
    call("reader", "one", "POST", "/publish", "{\"expectedRevision\":1}", 403);
    call("admin", "one", "PUT", "/draft", "{", 400);
    call(
        "admin",
        "one",
        "POST",
        "/validate",
        InvestmentRateFixtures.BASE.replace("7.123456", "-1"),
        400);
    call(
        "reader",
        "one",
        "GET",
        "/selection?amount=1000&termDays=90&asOfInstant=invalid",
        null,
        400);
    call("reader", "one", "GET", "/selection?amount=1000&termDays=90&campaignId=draft", null, 400);
    call("reader", "one", "GET", "/versions/99999", null, 404);
    call(
        "admin", "one", "PUT", "/draft", InvestmentRateFixtures.request(0, "2026-11-16", "9"), 200);
    var blocked = call("checker", "one", "POST", "/publish", "{\"expectedRevision\":1}", 422);
    assertTrue(
        blocked.jsonPath().getList("blockers").contains("SCHEDULE_MUST_BE_FUTURE_TENANT_DATE"));
    call("admin", "one", "PUT", "/draft", InvestmentRateFixtures.BASE, 409);
    call("checker", "one", "POST", "/publish", "{\"expectedRevision\":2}", 409);
    assertTrue(
        call("reader", "one", "GET", "/versions", null, 200).jsonPath().getList("$").isEmpty());
    long real = product;
    try {
      product = ordinary;
      call("admin", "one", "GET", "/template", null, 404);
    } finally {
      product = real;
    }
  }
}
