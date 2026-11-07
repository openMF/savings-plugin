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
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Tenant-wide administrative diagnostics; no financial commands or caller-provided balances. */
@Component
@Path("/v1/savings/monthly-fees")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Savings Monthly Fee Diagnostics")
@RequiredArgsConstructor
public class MonthlyFeeApiResource {
  private final MonthlyFeeAssessmentService service;

  @GET
  @Path("/template")
  public Response template() {
    return invoke(service::template);
  }

  @POST
  @Path("/runs")
  public Response create(@QueryParam("period") String period) {
    return invoke(() -> service.create(period));
  }

  @GET
  @Path("/runs/{id}")
  public Response get(@PathParam("id") String id) {
    return invoke(() -> service.get(id));
  }

  @POST
  @Path("/runs/{id}/assess")
  public Response assess(
      @PathParam("id") String id, @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> service.processChunk(id, limit));
  }

  @GET
  @Path("/runs/{id}/assessments")
  public Response assessments(
      @PathParam("id") String id,
      @QueryParam("afterId") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> service.assessments(id, after, limit));
  }

  @GET
  @Path("/runs/{id}/reconciliation")
  public Response reconciliation(@PathParam("id") String id) {
    return invoke(() -> service.reconciliation(id));
  }

  @GET
  @Path("/runs")
  public Response history(
      @QueryParam("beforePeriod") String before,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> service.history(before, limit));
  }

  private static Response invoke(Supplier<?> call) {
    return Response.ok(RatePropagationJson.JSON.toJson(call.get())).build();
  }
}
