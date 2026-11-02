/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Administrative extension to core savings products; deliberately outside /self. */
@Component
@Path("/v1/savingsproducts/policy")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
    name = "Savings Product Policy",
    description = "Drafts, mandatory independent review, and effective policy history")
@RequiredArgsConstructor
public class ProductPolicyApiResource {
  private final ProductPolicyService service;
  private final ProductPolicyWorkflow workflow;
  private final ProductPolicyApplicationService applications;

  @GET
  @Path("/template")
  public ProductPolicyData.Capabilities template() {
    return service.template();
  }

  @GET
  @Path("/{productId}")
  public ProductPolicyData.Configuration get(@PathParam("productId") long productId) {
    return service.get(productId);
  }

  @POST
  @Path("/{productId}/applications")
  public Response apply(@PathParam("productId") long product, String json) {
    return invoke(() -> applications.apply(product, json));
  }

  @GET
  @Path("/pending")
  public java.util.List<ProductPolicyWorkflowData.Version> pending() {
    return workflow.pending();
  }

  @POST
  @Path("/{productId}/submit")
  public Response submit(
      @PathParam("productId") long productId, ProductPolicyWorkflowData.Submit request) {
    return invoke(() -> workflow.submit(productId, request));
  }

  @POST
  @Path("/{productId}/versions/{version}/approve")
  public Response approve(
      @PathParam("productId") long productId,
      @PathParam("version") long version,
      ProductPolicyWorkflowData.Decision request) {
    return invoke(() -> workflow.decide(productId, version, true, request));
  }

  @POST
  @Path("/{productId}/versions/{version}/reject")
  public Response reject(
      @PathParam("productId") long productId,
      @PathParam("version") long version,
      ProductPolicyWorkflowData.Decision request) {
    return invoke(() -> workflow.decide(productId, version, false, request));
  }

  @GET
  @Path("/{productId}/versions")
  public java.util.List<ProductPolicyWorkflowData.Version> history(
      @PathParam("productId") long productId) {
    return workflow.history(productId);
  }

  @GET
  @Path("/{productId}/versions/{version}")
  public ProductPolicyWorkflowData.Review review(
      @PathParam("productId") long productId, @PathParam("version") long version) {
    return workflow.review(productId, version);
  }

  @GET
  @Path("/{productId}/active")
  public Response active(
      @PathParam("productId") long productId, @QueryParam("businessDate") String date) {
    java.time.LocalDate parsed = null;
    if (date != null)
      try {
        parsed = java.time.LocalDate.parse(date);
      } catch (java.time.format.DateTimeParseException e) {
        throw ProductPolicyValidation.invalid("businessDate", "ISO yyyy-MM-dd date required.");
      }
    var result = workflow.applicable(productId, parsed);
    return result == null ? Response.noContent().build() : Response.ok(result).build();
  }

  @GET
  @Path("/{productId}/audit")
  public java.util.List<ProductPolicyWorkflowData.Audit> audit(
      @PathParam("productId") long productId) {
    return workflow.audit(productId);
  }

  private Response invoke(java.util.function.Supplier<?> action) {
    try {
      return Response.ok(action.get()).build();
    } catch (ProductPolicyService.Conflict
        | org.springframework.dao.ConcurrencyFailureException e) {
      return conflict();
    }
  }

  private Response conflict() {
    return Response.status(Response.Status.CONFLICT)
        .entity(
            Map.of(
                "developerMessage",
                "Stale revision, invalid transition, conflicting effective date, or core product"
                    + " drift; retrieve the draft and history before retrying.",
                "httpStatusCode",
                "409",
                "defaultUserMessage",
                "Policy revision or lifecycle conflict.",
                "userMessageGlobalisationCode",
                "error.msg.savings.product.policy.revision.conflict",
                "errors",
                java.util.List.of()))
        .build();
  }

  @PUT
  @Path("/{productId}")
  public Response save(@PathParam("productId") long productId, String json) {
    try {
      return Response.ok(service.save(productId, json)).build();
    } catch (ProductPolicyService.Conflict
        | org.springframework.dao.ConcurrencyFailureException e) {
      return Response.status(Response.Status.CONFLICT)
          .entity(
              Map.of(
                  "developerMessage",
                  "Configuration changed; retrieve it and resubmit with its revision.",
                  "httpStatusCode",
                  "409",
                  "defaultUserMessage",
                  "Draft revision conflict.",
                  "userMessageGlobalisationCode",
                  "error.msg.savings.product.policy.revision.conflict",
                  "errors",
                  java.util.List.of()))
          .build();
    }
  }
}
