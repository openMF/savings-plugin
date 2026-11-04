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
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Administrative benchmark capture/review; no endpoint executes financial transactions. */
@Component
@Path("/v1/savingsproducts/reference-rates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
    name = "Savings Reference Rates",
    description = "Manual benchmark configuration and independent approval")
@RequiredArgsConstructor
public class ReferenceRateApiResource {
  private final ReferenceRateService service;

  @GET
  @Path("/template")
  public ReferenceRateData.Template template() {
    return service.template();
  }

  @GET
  public List<ReferenceRateData.Definition> definitions() {
    return service.definitions();
  }

  @POST
  public Response define(String json) {
    return invoke(() -> service.define(json));
  }

  @GET
  @Path("/{referenceId}")
  public ReferenceRateData.Definition definition(@PathParam("referenceId") String reference) {
    return service.definition(reference);
  }

  @GET
  @Path("/pending")
  public List<ReferenceRateData.Fixing> pending() {
    return service.pending();
  }

  @GET
  @Path("/{referenceId}/fixings")
  public List<ReferenceRateData.Fixing> history(@PathParam("referenceId") String reference) {
    return service.history(reference);
  }

  @GET
  @Path("/{referenceId}/fixings/{version}")
  public ReferenceRateData.Fixing fixing(
      @PathParam("referenceId") String reference, @PathParam("version") long version) {
    return service.get(reference, version);
  }

  @POST
  @Path("/{referenceId}/fixings")
  public Response capture(@PathParam("referenceId") String reference, String json) {
    return invoke(() -> service.capture(reference, json));
  }

  @POST
  @Path("/{referenceId}/fixings/{version}/submit")
  public Response submit(
      @PathParam("referenceId") String reference, @PathParam("version") long version, String json) {
    return invoke(() -> service.submit(reference, version, json));
  }

  @POST
  @Path("/{referenceId}/fixings/{version}/approve")
  public Response approve(
      @PathParam("referenceId") String reference, @PathParam("version") long version, String json) {
    return invoke(() -> service.decide(reference, version, true, json));
  }

  @POST
  @Path("/{referenceId}/fixings/{version}/reject")
  public Response reject(
      @PathParam("referenceId") String reference, @PathParam("version") long version, String json) {
    return invoke(() -> service.decide(reference, version, false, json));
  }

  @GET
  @Path("/{referenceId}/effective")
  public ReferenceRateData.Fixing effective(
      @PathParam("referenceId") String reference,
      @QueryParam("tenor") String tenor,
      @QueryParam("businessDate") String date) {
    return service.resolve(
        reference, tenor, date == null ? null : ReferenceRateValidation.date(date, "businessDate"));
  }

  @GET
  @Path("/{referenceId}/audit")
  public List<ReferenceRateData.Audit> audit(@PathParam("referenceId") String reference) {
    return service.audit(reference);
  }

  static Response invoke(java.util.function.Supplier<?> action) {
    try {
      return Response.ok(action.get()).build();
    } catch (ProductPolicyService.Conflict
        | org.springframework.dao.ConcurrencyFailureException
        | org.springframework.dao.DuplicateKeyException e) {
      return Response.status(Response.Status.CONFLICT)
          .entity(
              Map.of(
                  "httpStatusCode",
                  "409",
                  "defaultUserMessage",
                  "Reference rate revision, duplicate or lifecycle conflict.",
                  "userMessageGlobalisationCode",
                  "error.msg.savings.reference.rate.conflict",
                  "errors",
                  List.of()))
          .build();
    }
  }
}
