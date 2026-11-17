/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

/** Administrative investment configuration; deliberately outside native fixeddepositproducts. */
@Component
@Path("/v1/investment-products/configuration")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "Investment Product Configuration",
    description = "Versioned draft metadata; no financial execution")
@RequiredArgsConstructor
public class InvestmentProductApiResource {
  private final InvestmentProductService service;

  @GET
  @Path("/template")
  @ApiResponse(
      responseCode = "200",
      content = @Content(schema = @Schema(implementation = InvestmentProductData.Template.class)))
  public Response template() {
    return invoke(service::template);
  }

  @GET
  @Path("/{productId}")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(schema = @Schema(implementation = InvestmentProductData.Configuration.class)))
  public Response get(@PathParam("productId") long product) {
    return invoke(() -> service.get(product));
  }

  @PUT
  @Path("/{productId}")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(schema = @Schema(implementation = InvestmentProductData.Configuration.class)))
  public Response save(
      @PathParam("productId") long product,
      @RequestBody(
              required = true,
              content =
                  @Content(
                      schema = @Schema(implementation = InvestmentProductData.WriteRequest.class)))
          String json) {
    return invoke(() -> service.save(product, json));
  }

  @POST
  @Path("/{productId}/validate")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(schema = @Schema(implementation = InvestmentProductData.ValidationResult.class)))
  public Response validate(
      @PathParam("productId") long product,
      @RequestBody(
              required = true,
              content =
                  @Content(
                      schema = @Schema(implementation = InvestmentProductData.WriteRequest.class)))
          String json) {
    return invoke(() -> service.validate(product, json));
  }

  @GET
  @Path("/{productId}/history")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(
              array =
                  @ArraySchema(
                      schema = @Schema(implementation = InvestmentProductData.Audit.class))))
  public Response history(
      @PathParam("productId") long product,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("50") int limit) {
    return invoke(() -> service.history(product, offset, limit));
  }

  private Response invoke(Supplier<?> action) {
    try {
      // Explicit codec preserves monetary notation regardless of the deployment JSON provider.
      return Response.ok(
              InvestmentProductJson.JSON.toJson(action.get()), MediaType.APPLICATION_JSON)
          .build();
    } catch (InvestmentProductService.Conflict | ConcurrencyFailureException e) {
      return Response.status(Response.Status.CONFLICT)
          .entity(
              Map.of(
                  "developerMessage",
                  "Configuration changed; retrieve the latest revision before retrying.",
                  "httpStatusCode",
                  "409",
                  "defaultUserMessage",
                  "Investment policy revision conflict.",
                  "userMessageGlobalisationCode",
                  "error.msg.investment.product.policy.revision.conflict",
                  "errors",
                  List.of()))
          .build();
    }
  }
}
