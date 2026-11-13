/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** All GETs are read-only, including preview/reconciliation. POSTs save plugin evidence only. */
@Path("/v1/savings/consolidated-isr")
@Component
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "Consolidated ISR",
    description = "Policy-blocked ISR diagnostics and immutable evidence")
@RequiredArgsConstructor
public class ConsolidatedIsrApiResource {
  private final ConsolidatedIsrService service;
  private final ConsolidatedIsrRuleResolver rules;

  @GET
  @Path("/template")
  public Response template() {
    return invoke(service::template);
  }

  @GET
  @Path("/clients/{clientId}/eligibility")
  public Response eligibility(
      @PathParam("clientId") long client,
      @QueryParam("from") String from,
      @QueryParam("through") String through) {
    return invoke(() -> service.eligibility(client, period(from, through)));
  }

  @GET
  @Path("/clients/{clientId}/accounts")
  public Response accounts(
      @PathParam("clientId") long client,
      @QueryParam("from") String from,
      @QueryParam("through") String through) {
    return invoke(() -> service.accounts(client, period(from, through)));
  }

  @GET
  @Path("/clients/{clientId}/preview")
  public Response preview(
      @PathParam("clientId") long client,
      @QueryParam("from") String from,
      @QueryParam("through") String through) {
    return invoke(() -> service.preview(client, period(from, through)));
  }

  @POST
  @Path("/clients/{clientId}/calculations")
  public Response save(@PathParam("clientId") long client, String json) {
    return invoke(() -> service.save(client, json));
  }

  @GET
  @Path("/clients/{clientId}/calculations")
  public Response history(
      @PathParam("clientId") long client,
      @QueryParam("after") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> service.history(client, after, limit));
  }

  @GET
  @Path("/clients/{clientId}/calculations/{id}")
  public Response get(@PathParam("clientId") long client, @PathParam("id") String id) {
    return invoke(() -> service.get(client, id));
  }

  @GET
  @Path("/clients/{clientId}/calculations/{id}/audit")
  public Response audit(
      @PathParam("clientId") long client,
      @PathParam("id") String id,
      @QueryParam("after") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> service.audit(client, id, after, limit));
  }

  @GET
  @Path("/clients/{clientId}/calculations/{id}/reconciliation")
  public Response reconciliation(@PathParam("clientId") long client, @PathParam("id") String id) {
    return invoke(() -> service.reconciliation(client, id));
  }

  @POST
  @Path("/clients/{clientId}/calculations/{id}/reconciliations")
  public Response reconcile(
      @PathParam("clientId") long client, @PathParam("id") String id, String json) {
    return invoke(
        () -> {
          var request =
              ConsolidatedIsrValidation.parse(
                  json, ReconcileRequest.class, java.util.Set.of("idempotencyKey"));
          return service.recordReconciliation(client, id, request.idempotencyKey());
        });
  }

  record ReconcileRequest(String idempotencyKey) {}

  @POST
  @Path("/rules/{ruleId}/versions")
  public Response propose(@PathParam("ruleId") String id, String json) {
    return invoke(() -> rules.propose(id, json));
  }

  @GET
  @Path("/rules/{ruleId}/versions")
  public Response rules(
      @PathParam("ruleId") String id,
      @QueryParam("after") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> rules.versions(id, after, limit));
  }

  private static ConsolidatedIsrData.Period period(String from, String through) {
    try {
      if (from == null || through == null) throw new IllegalArgumentException();
      return new ConsolidatedIsrData.Period(LocalDate.parse(from), LocalDate.parse(through));
    } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
      throw ProductPolicyValidation.invalid("period", "ISO from and through dates required.");
    }
  }

  private static Response invoke(Supplier<?> call) {
    try {
      return Response.ok(RatePropagationJson.JSON.toJson(call.get())).build();
    } catch (ConsolidatedIsrAuditService.Conflict
        | ConcurrencyFailureException
        | DuplicateKeyException e) {
      return Response.status(409)
          .entity(Map.of("reasonCode", "REQUEST_CONFLICT", "financialRetryAllowed", false))
          .build();
    }
  }
}
