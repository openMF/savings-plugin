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
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Administrative matrix metadata; BE-02 endpoints provide approval, versions, dates and audit. */
@Component
@Path("/v1/savingsproducts/policy/{productId}/interest-matrix")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
    name = "Savings Interest Matrix",
    description = "Non-operational versioned matrix configuration")
@RequiredArgsConstructor
public class InterestMatrixApiResource {
  private final InterestMatrixService service;

  @GET
  public InterestMatrixData.Configuration draft(@PathParam("productId") long product) {
    return service.draft(product);
  }

  @PUT
  public Response save(@PathParam("productId") long product, String json) {
    return invoke(() -> service.save(product, json));
  }

  @POST
  @Path("/validate")
  public Response validate(@PathParam("productId") long product, String json) {
    return invoke(() -> service.validate(product, json));
  }

  @GET
  @Path("/selection")
  public InterestMatrixData.Selection select(
      @PathParam("productId") long product,
      @QueryParam("balance") String balance,
      @QueryParam("businessDate") String date) {
    BigDecimal amount;
    LocalDate businessDate;
    try {
      if (balance == null || !balance.matches("(0|[1-9][0-9]{0,12})(\\.[0-9]{1,6})?"))
        throw new IllegalArgumentException();
      amount = new BigDecimal(balance);
      businessDate = date == null ? null : LocalDate.parse(date);
    } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
      throw ProductPolicyValidation.invalid(
          "selection", "Plain nonnegative balance and ISO yyyy-MM-dd businessDate required.");
    }
    return service.selectForReview(product, amount, businessDate);
  }

  private Response invoke(java.util.function.Supplier<?> action) {
    try {
      return Response.ok(action.get()).build();
    } catch (ProductPolicyService.Conflict
        | org.springframework.dao.ConcurrencyFailureException e) {
      return Response.status(Response.Status.CONFLICT)
          .entity(
              java.util.Map.of(
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
  }
}
