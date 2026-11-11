/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

/** Administrative only; permission and current ownership checks live in the service layer. */
@Component
@Path("/v1/savings/guarantee-sweeps")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Liquid Guarantee Sweep Diagnostics")
@RequiredArgsConstructor
public class GuaranteeSweepApiResource {
  private final GuaranteeSweepExecutionService executions;
  private final GuaranteeSweepDiagnosticService diagnostics;
  private final GuaranteeSweepReconciliationService reconciliation;

  @GET
  @Path("/template")
  public Response template() {
    return invoke(executions::template);
  }

  @GET
  @Path("/clients/{clientId}/sources")
  public Response sources(
      @PathParam("clientId") long client,
      @QueryParam("afterId") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> executions.sources(client, after, limit));
  }

  @GET
  @Path("/clients/{clientId}/loans")
  public Response loans(
      @PathParam("clientId") long client,
      @QueryParam("afterId") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> executions.loans(client, after, limit));
  }

  @GET
  @Path("/clients/{clientId}/loans/{loanId}/preview")
  public Response preview(
      @PathParam("clientId") long client,
      @PathParam("loanId") long loan,
      @QueryParam("savingsAccountId") long savings,
      @QueryParam("amount") BigDecimal amount) {
    return invoke(() -> executions.preview(client, loan, savings, amount));
  }

  @POST
  @Path("/clients/{clientId}/loans/{loanId}/applications")
  public Response apply(
      @PathParam("clientId") long client, @PathParam("loanId") long loan, String json) {
    return submission(() -> executions.apply(client, loan, json));
  }

  @POST
  @Path("/clients/{clientId}/loans/{loanId}/releases")
  public Response release(
      @PathParam("clientId") long client, @PathParam("loanId") long loan, String json) {
    return submission(() -> executions.release(client, loan, json));
  }

  @GET
  @Path("/clients/{clientId}/loans/{loanId}/executions/{id}")
  public Response get(
      @PathParam("clientId") long client,
      @PathParam("loanId") long loan,
      @PathParam("id") String id) {
    return invoke(() -> executions.get(client, loan, id));
  }

  @GET
  @Path("/clients/{clientId}/loans/{loanId}/audit")
  public Response audit(
      @PathParam("clientId") long client,
      @PathParam("loanId") long loan,
      @QueryParam("afterId") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> executions.history(client, loan, after, limit, "audit"));
  }

  @GET
  @Path("/clients/{clientId}/loans/{loanId}/allocations")
  public Response allocations(
      @PathParam("clientId") long client,
      @PathParam("loanId") long loan,
      @QueryParam("afterId") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> executions.history(client, loan, after, limit, "allocations"));
  }

  @GET
  @Path("/clients/{clientId}/loans/{loanId}/release-status")
  public Response releaseStatus(
      @PathParam("clientId") long client,
      @PathParam("loanId") long loan,
      @QueryParam("afterId") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(
        () ->
            Map.of(
                "state",
                GuaranteeSweepData.State.BLOCKED_PENDING_POLICY,
                "blockers",
                GuaranteeSweepData.BLOCKERS,
                "allocations",
                executions.history(client, loan, after, limit, "allocations")));
  }

  @GET
  @Path("/clients/{clientId}/loans/{loanId}/executions/{id}/reconciliation")
  public Response reconcile(
      @PathParam("clientId") long client,
      @PathParam("loanId") long loan,
      @PathParam("id") String id) {
    return invoke(() -> reconciliation.inspect(client, loan, id));
  }

  @POST
  @Path("/diagnostic-runs")
  public Response create(@QueryParam("period") String period) {
    return invoke(() -> diagnostics.create(period));
  }

  @GET
  @Path("/diagnostic-runs/{id}")
  public Response run(@PathParam("id") String id) {
    return invoke(() -> diagnostics.get(id));
  }

  @POST
  @Path("/diagnostic-runs/{id}/assess")
  public Response assess(
      @PathParam("id") String id, @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> diagnostics.processChunk(id, limit));
  }

  @GET
  @Path("/diagnostic-runs/{id}/outcomes")
  public Response outcomes(
      @PathParam("id") String id,
      @QueryParam("afterId") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> diagnostics.outcomes(id, after, limit));
  }

  private static Response submission(Supplier<GuaranteeSweepData.Submission> call) {
    try {
      var result = call.get();
      // Both new attempts (policy blocked) and duplicates have explicit non-success HTTP outcomes.
      return Response.status(result.duplicate() ? 409 : 422)
          .entity(RatePropagationJson.JSON.toJson(result))
          .build();
    } catch (GuaranteeSweepExecutionService.Conflict | ConcurrencyFailureException e) {
      return conflict();
    }
  }

  private static Response invoke(Supplier<?> call) {
    try {
      return Response.ok(RatePropagationJson.JSON.toJson(call.get())).build();
    } catch (ConcurrencyFailureException e) {
      return conflict();
    }
  }

  private static Response conflict() {
    return Response.status(409)
        .entity(Map.of("reasonCode", "REQUEST_CONFLICT", "financialRetryAllowed", false))
        .build();
  }
}
