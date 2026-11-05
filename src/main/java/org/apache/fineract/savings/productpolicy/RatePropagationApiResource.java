/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.RatePropagationJson.JSON;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Administrative planning and review only; start is a fail-closed diagnostic boundary. */
@Component
@Path("/v1/savingsproducts/policy/rate-propagation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
    name = "Savings Rate Propagation",
    description =
        "Approved-source planning, independent batch review, and blocked financial execution")
@RequiredArgsConstructor
public class RatePropagationApiResource {
  private final RatePropagationService service;

  @GET
  @Path("/template")
  public Response template() {
    return invoke(service::template);
  }

  @POST
  @Path("/{productId}/preview")
  public Response preview(@PathParam("productId") long product, String json) {
    return invoke(() -> service.preview(product, json));
  }

  @POST
  @Path("/{productId}/batches")
  public Response create(@PathParam("productId") long product, String json) {
    return invoke(() -> service.create(product, json));
  }

  @GET
  @Path("/batches/{batchId}")
  public Response get(@PathParam("batchId") String id) {
    return invoke(() -> service.get(id));
  }

  @POST
  @Path("/batches/{batchId}/discover")
  public Response discover(
      @PathParam("batchId") String id,
      String json,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> service.discover(id, json, limit));
  }

  @POST
  @Path("/batches/{batchId}/submit")
  public Response submit(@PathParam("batchId") String id, String json) {
    return invoke(() -> service.submit(id, json));
  }

  @POST
  @Path("/batches/{batchId}/approve")
  public Response approve(@PathParam("batchId") String id, String json) {
    return invoke(() -> service.decide(id, true, json));
  }

  @POST
  @Path("/batches/{batchId}/reject")
  public Response reject(@PathParam("batchId") String id, String json) {
    return invoke(() -> service.decide(id, false, json));
  }

  @POST
  @Path("/batches/{batchId}/assess")
  public Response assess(
      @PathParam("batchId") String id,
      String json,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> service.assess(id, json, limit));
  }

  @POST
  @Path("/batches/{batchId}/start")
  public Response start(@PathParam("batchId") String id, String json) {
    return invoke(() -> service.start(id, json));
  }

  @POST
  @Path("/batches/{batchId}/cancel")
  public Response cancel(@PathParam("batchId") String id, String json) {
    return invoke(() -> service.cancel(id, json));
  }

  @GET
  @Path("/batches/{batchId}/accounts")
  public Response accounts(
      @PathParam("batchId") String id,
      @QueryParam("afterId") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> service.accounts(id, after, limit));
  }

  @GET
  @Path("/batches/{batchId}/audit")
  public Response audit(
      @PathParam("batchId") String id,
      @QueryParam("afterId") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> service.audit(id, after, limit));
  }

  @GET
  @Path("/batches/{batchId}/attempts")
  public Response attempts(
      @PathParam("batchId") String id,
      @QueryParam("afterId") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> service.attempts(id, after, limit));
  }

  static Response invoke(Supplier<?> operation) {
    try {
      Object result = operation.get();
      if (result instanceof RatePropagationData.Blocked b)
        return conflict(b.code(), b.message(), b);
      // Explicit serialization supports immutable JsonObject snapshots and ISO business dates.
      return Response.ok(JSON.toJson(result)).build();
    } catch (RatePropagationService.NotReady e) {
      return conflict(
          "EFFECTIVE_DATE_NOT_REACHED",
          "Assessment is unavailable before the approved tenant business date.",
          null);
    } catch (ProductPolicyService.Conflict
        | org.springframework.dao.ConcurrencyFailureException
        | org.springframework.dao.DuplicateKeyException e) {
      return conflict(
          "REVISION_OR_SOURCE_CONFLICT",
          "Stale revision, invalid lifecycle, source drift, or concurrent operation. Retrieve the"
              + " batch before retrying.",
          null);
    }
  }

  private static Response conflict(
      String code, String message, RatePropagationData.Blocked blocked) {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("httpStatusCode", "409");
    body.put("developerMessage", message);
    body.put("defaultUserMessage", message);
    body.put("userMessageGlobalisationCode", "error.msg.savings.rate.propagation.blocked");
    body.put("code", code);
    body.put("errors", List.of());
    if (blocked != null) body.put("blocked", blocked);
    return Response.status(Response.Status.CONFLICT).entity(JSON.toJson(body)).build();
  }
}
