/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
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
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Administrative estimates: HTTP 200 domain statuses never imply financial execution. */
@Component
@Path("/v1/investment-products/simulations")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "Investment Simulations",
    description = "Audited non-binding gross estimates; no accounts, transfers, posting or tax")
@RequiredArgsConstructor
public class InvestmentSimulationApiResource {
  private final InvestmentSimulationService service;

  @GET
  @Path("/template")
  public Response template() {
    return invoke(service::template);
  }

  @POST
  @Path("/validate")
  public Response validate(
      @RequestBody(
              required = true,
              content =
                  @Content(
                      schema = @Schema(implementation = InvestmentSimulationData.Request.class)))
          String json) {
    return invoke(() -> service.validate(json));
  }

  @POST
  public Response create(
      @RequestBody(
              required = true,
              content =
                  @Content(
                      schema = @Schema(implementation = InvestmentSimulationData.Request.class)))
          String json) {
    return invoke(() -> service.create(json));
  }

  @GET
  @Path("/{simulationId}")
  public Response get(@PathParam("simulationId") String id) {
    return invoke(() -> service.get(id));
  }

  @GET
  @Path("/history")
  public Response history(
      @QueryParam("productId") Long product,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("50") int limit) {
    return invoke(() -> service.history(product, offset, limit));
  }

  private Response invoke(Supplier<?> action) {
    try {
      return Response.ok(
              InvestmentProductJson.JSON.toJson(action.get()), MediaType.APPLICATION_JSON)
          .build();
    } catch (InvestmentSimulationService.Conflict e) {
      return Response.status(409)
          .entity(
              Map.of(
                  "httpStatusCode",
                  409,
                  "developerMessage",
                  e.getMessage(),
                  "defaultUserMessage",
                  e.getMessage(),
                  "userMessageGlobalisationCode",
                  "error.msg.investment.simulation.conflict",
                  "errors",
                  List.of()))
          .build();
    }
  }
}
