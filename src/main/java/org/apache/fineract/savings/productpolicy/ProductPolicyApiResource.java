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
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
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
@Tag(name = "Savings Product Policy", description = "Administrative draft metadata only")
@RequiredArgsConstructor
public class ProductPolicyApiResource {
  private final ProductPolicyService service;

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

  @PUT
  @Path("/{productId}")
  public Response save(@PathParam("productId") long productId, String json) {
    try {
      return Response.ok(service.save(productId, json)).build();
    } catch (ProductPolicyService.Conflict e) {
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
