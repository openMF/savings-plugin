/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static io.restassured.RestAssured.given;
import static org.apache.fineract.investment.InvestmentSimulationFixtures.*;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** Actual loopback HTTP and PostgreSQL with a test identity adapter, not native authentication. */
class InvestmentSimulationApiHttpIntegrationTest extends InvestmentSimulationDatabaseSupport {
  static final String ROOT = "/v1/investment-products/simulations";
  final ThreadLocal<String> identity = new ThreadLocal<>();
  HttpServer server;
  org.springframework.context.support.GenericApplicationContext context;
  ApplicationHandler app;
  String base;

  @BeforeEach
  void startHttp() throws Exception {
    publish("8.123456");
    var reader = mock(AppUser.class);
    when(reader.getId()).thenReturn(1L);
    doAnswer(
            i -> {
              if (!InvestmentSimulationService.READ.equals(i.getArgument(0)))
                throw new AccessDeniedException("Read only");
              return null;
            })
        .when(reader)
        .validateHasPermissionTo(anyString());
    var unrelated = mock(AppUser.class);
    when(unrelated.getId()).thenReturn(2L);
    var denied = mock(AppUser.class);
    doThrow(new AccessDeniedException("Forbidden"))
        .when(denied)
        .validateHasPermissionTo(anyString());
    when(security.authenticatedUser())
        .thenAnswer(
            i -> {
              String who = identity.get();
              if (who == null) throw new UnAuthenticatedUserException();
              return switch (who) {
                case "operator" -> actor;
                case "reader" -> reader;
                case "unrelated" -> unrelated;
                default -> denied;
              };
            });
    context = new org.springframework.context.support.GenericApplicationContext();
    var resource = new InvestmentSimulationApiResource(simulations);
    context.registerBean(InvestmentSimulationApiResource.class, () -> resource);
    context.refresh();
    app =
        new ApplicationHandler(
            new ResourceConfig()
                .property("contextConfig", context)
                .register(resource)
                .register(JacksonFeature.class)
                .register(PlatformApiDataValidationExceptionMapper.class)
                .register(PlatformResourceNotFoundExceptionMapper.class)
                .register(AccessDeniedExceptionMapper.class)
                .register(UnAuthenticatedUserExceptionMapper.class));
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
  void stopHttp() {
    if (server != null) server.stop(0);
    if (app != null) app.getInjectionManager().shutdown();
    if (context != null) context.close();
  }

  Response call(String user, String tenant, String method, String path, String body, int status) {
    var request =
        given()
            .baseUri(base)
            .header("Fineract-Platform-TenantId", tenant)
            .contentType("application/json");
    if (user != null) request.auth().preemptive().basic(user, "synthetic-test-secret");
    if (body != null) request.body(body);
    var response = request.request(method, ROOT + path);
    assertEquals(status, response.statusCode(), response::asString);
    return response;
  }

  @Test
  void actualRoutesProvideProjectionValidationImmutableRetrievalAndScopedHistory() {
    var before = financialSnapshot();
    var template = call("reader", "one", "GET", "/template", null, 200);
    assertFalse(template.jsonPath().getBoolean("capabilities.financialExecutionSupported"));
    assertFalse(template.jsonPath().getBoolean("capabilities.netYieldAvailable"));
    var valid = call("operator", "one", "POST", "/validate", request(product, "api"), 200);
    assertNull(valid.jsonPath().get("simulationId"));
    assertEquals("COMPLETE_GROSS_PROJECTION", valid.jsonPath().getString("status"));
    assertTrue(
        call("operator", "one", "GET", "/history?productId=" + product, null, 200)
            .jsonPath()
            .getList("$")
            .isEmpty());
    var created = call("operator", "one", "POST", "", request(product, "api"), 200);
    String id = created.jsonPath().getString("simulationId");
    assertEquals(created.asString(), call("reader", "one", "GET", "/" + id, null, 200).asString());
    assertEquals(
        id,
        call("operator", "one", "POST", "", request(product, "api"), 200)
            .jsonPath()
            .getString("simulationId"));
    assertEquals(
        1,
        call("operator", "one", "GET", "/history?productId=" + product, null, 200)
            .jsonPath()
            .getList("$")
            .size());
    assertEquals(before, financialSnapshot());
  }

  @Test
  void httpAuthorizationFieldErrorsIdempotencyAndBlockedResponsesAreExplicit() {
    call(null, "one", "GET", "/template", null, 401);
    call("denied", "one", "GET", "/template", null, 403);
    call("operator", "missing", "GET", "/template", null, 403);
    call("reader", "one", "POST", "", request(product, null), 403);
    call("reader", "one", "POST", "/validate", request(product, null), 403);
    call("reader", "one", "GET", "/history", null, 403);
    call("operator", "one", "POST", "", "{", 400);
    call(
        "operator",
        "one",
        "POST",
        "",
        edit(product, null, r -> r.addProperty("annualRatePercent", 999)),
        400);
    call(
        "operator",
        "one",
        "POST",
        "",
        edit(product, null, r -> r.addProperty("customerId", 1)),
        400);
    call("operator", "one", "GET", "/history?limit=101", null, 400);
    call("operator", "one", "POST", "", request(ordinary, null), 404);
    String id =
        call("operator", "one", "POST", "", request(product, "conflict"), 200)
            .jsonPath()
            .getString("simulationId");
    call(
        "operator",
        "one",
        "POST",
        "",
        edit(product, "conflict", r -> r.addProperty("termDays", 91)),
        409);
    call("unrelated", "one", "GET", "/" + id, null, 404);
    call("operator", "two", "GET", "/" + id, null, 404);
    var blocked = call("operator", "two", "POST", "", request(product, null), 200);
    assertEquals("NO_APPLICABLE_RATE", blocked.jsonPath().getString("status"));
    assertNull(blocked.jsonPath().get("grossInterest"));
    assertFalse(blocked.jsonPath().getList("blockers").isEmpty());
  }
}
