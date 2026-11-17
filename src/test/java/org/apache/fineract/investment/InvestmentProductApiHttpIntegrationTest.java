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
import java.util.Map;
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
class InvestmentProductApiHttpIntegrationTest extends InvestmentProductDatabaseSupport {
  static final String ROOT = "/v1/investment-products/configuration";
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
        .validateHasPermissionTo(InvestmentProductService.MANAGE);
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
                default -> denied;
              };
            });
    context = new org.springframework.context.support.GenericApplicationContext();
    var resource = new InvestmentProductApiResource(service);
    context.registerBean(InvestmentProductApiResource.class, () -> resource);
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
            if ("one".equals(tenant) || "two".equals(tenant)) tenant(tenant);
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
    var response = request.request(method, ROOT + path);
    assertEquals(expected, response.statusCode(), response::asString);
    return response;
  }

  @Test
  void templateReadCreateUpdateValidateHistoryAndConflictOverHttp() {
    var template = call("reader", "one", "GET", "/template", null, 200);
    assertEquals(2, template.jsonPath().getList("investmentTypes").size());
    assertFalse(template.jsonPath().getBoolean("capabilities.financialExecutionSupported"));
    call("reader", "one", "GET", "/" + product, null, 200);
    call("admin", "one", "POST", "/" + product + "/validate", JSON, 200);
    assertEquals(
        0,
        call("reader", "one", "GET", "/" + product + "/history", null, 200)
            .jsonPath()
            .getList("$")
            .size());
    assertEquals(
        1, call("admin", "one", "PUT", "/" + product, JSON, 200).jsonPath().getInt("revision"));
    call("admin", "one", "PUT", "/" + product, JSON, 409);
    call("admin", "one", "POST", "/" + product + "/validate", JSON, 409);
    assertEquals(
        2,
        call(
                "admin",
                "one",
                "PUT",
                "/" + product,
                request(1, Map.of("capitalizationEligible", true)),
                200)
            .jsonPath()
            .getInt("revision"));
    assertEquals(
        2,
        call("reader", "one", "GET", "/" + product + "/history", null, 200)
            .jsonPath()
            .getList("$")
            .size());
    assertEquals(
        0, call("reader", "two", "GET", "/" + product, null, 200).jsonPath().getInt("revision"));
    call("reader", "one", "GET", "/" + product + "/history?limit=201", null, 400);
  }

  @Test
  void authenticationAuthorizationInputErrorsAndNativeProductBoundaryOverHttp() {
    call(null, "one", "GET", "/template", null, 401);
    call("denied", "one", "GET", "/template", null, 403);
    call("reader", "one", "PUT", "/" + product, JSON, 403);
    call("reader", "one", "POST", "/" + product + "/validate", JSON, 403);
    call("denied", "one", "GET", "/" + product + "/history", null, 403);
    call("admin", "one", "GET", "/99999999", null, 404);
    call("admin", "one", "PUT", "/" + ordinary, JSON, 404);
    call("admin", "one", "PUT", "/" + recurring, JSON, 404);
    for (String body :
        new String[] {
          "{", request(0, Map.of("active", true)), request(0, Map.of("maximumTermDays", 1826))
        }) {
      var response = call("admin", "one", "PUT", "/" + product, body, 400);
      assertNotNull(response.jsonPath().getString("developerMessage"));
      assertFalse(response.jsonPath().getList("errors").isEmpty());
    }
  }
}
