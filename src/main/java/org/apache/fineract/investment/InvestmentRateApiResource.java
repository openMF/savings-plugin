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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** Administrative pricing metadata; the service enforces authorization on every operation. */
@Component
@Path("/v1/investment-products/{productId}/rates")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "Investment Rates",
    description =
        "Versioned pricing metadata, independent review and historical lookup; no financial execution")
@RequiredArgsConstructor
public class InvestmentRateApiResource {
  private final InvestmentRateService service;

  @GET
  @Path("/template")
  public Response template(@PathParam("productId") long product) {
    return invoke(() -> service.template(product));
  }

  @GET
  public Response current(@PathParam("productId") long product) {
    return invoke(() -> service.current(product));
  }

  @GET
  @Path("/draft")
  public Response draft(@PathParam("productId") long product) {
    return invoke(() -> service.draft(product));
  }

  @PUT
  @Path("/draft")
  public Response save(
      @PathParam("productId") long product,
      @RequestBody(
              required = true,
              content =
                  @Content(
                      schema = @Schema(implementation = InvestmentRateData.WriteRequest.class)))
          String json) {
    return invoke(() -> service.save(product, json));
  }

  @POST
  @Path("/validate")
  public Response validate(
      @PathParam("productId") long product,
      @RequestBody(
              required = true,
              content =
                  @Content(
                      schema = @Schema(implementation = InvestmentRateData.WriteRequest.class)))
          String json) {
    return invoke(() -> service.validate(product, json));
  }

  @POST
  @Path("/publish")
  public Response publish(
      @PathParam("productId") long product,
      @RequestBody(
              required = true,
              content =
                  @Content(
                      schema = @Schema(implementation = InvestmentRateData.PublishRequest.class)))
          String json) {
    return invoke(() -> service.publish(product, json));
  }

  @GET
  @Path("/versions")
  public Response versions(
      @PathParam("productId") long product,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("50") int limit) {
    return invoke(() -> service.versions(product, false, offset, limit));
  }

  @GET
  @Path("/versions/{version}")
  public Response version(
      @PathParam("productId") long product, @PathParam("version") long version) {
    return invoke(() -> service.version(product, version));
  }

  @GET
  @Path("/schedule")
  public Response schedule(
      @PathParam("productId") long product,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("50") int limit) {
    return invoke(() -> service.versions(product, true, offset, limit));
  }

  @GET
  @Path("/history")
  public Response history(
      @PathParam("productId") long product,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("50") int limit) {
    return invoke(() -> service.history(product, offset, limit));
  }

  @GET
  @Path("/selection")
  public Response selection(
      @PathParam("productId") long product,
      @QueryParam("amount") String amount,
      @QueryParam("termDays") int termDays,
      @QueryParam("asOfInstant") String instant,
      @QueryParam("campaignId") String campaign) {
    return invoke(() -> service.selection(product, amount, termDays, instant, campaign));
  }

  private Response invoke(Supplier<?> action) {
    try {
      return Response.ok(
              InvestmentProductJson.JSON.toJson(action.get()), MediaType.APPLICATION_JSON)
          .build();
    } catch (InvestmentRateService.Conflict e) {
      return failure(409, e.getMessage(), List.of());
    } catch (ConcurrencyFailureException | DuplicateKeyException e) {
      return failure(409, "CONCURRENT_RATE_CHANGE_RETRY_LATEST_REVISION", List.of());
    } catch (InvestmentRateService.PublicationBlocked e) {
      return failure(422, "RATE_PUBLICATION_BLOCKED", e.blockers);
    }
  }

  private Response failure(int status, String code, List<String> blockers) {
    return Response.status(status)
        .entity(
            Map.of(
                "httpStatusCode",
                status,
                "developerMessage",
                code,
                "defaultUserMessage",
                code,
                "userMessageGlobalisationCode",
                "error.msg.investment.rate.conflict",
                "errors",
                List.of(),
                "blockers",
                blockers))
        .build();
  }
}
