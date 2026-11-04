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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Product formula facade; BE-02 endpoints own submission, approval and historical snapshots. */
@Component
@Path("/v1/savingsproducts/policy/{productId}/reference-rate")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
    name = "Savings Reference Rate Formula",
    description = "Versioned formula metadata and read-only nominal rate preview")
@RequiredArgsConstructor
public class ReferenceRateFormulaApiResource {
  private final ReferenceRateFormulaService service;

  @GET
  public ReferenceRateData.Configuration draft(@PathParam("productId") long product) {
    return service.draft(product);
  }

  @PUT
  public Response save(@PathParam("productId") long product, String json) {
    return ReferenceRateApiResource.invoke(() -> service.save(product, json));
  }

  @GET
  @Path("/preview")
  public ReferenceRateData.Calculation preview(
      @PathParam("productId") long product, @QueryParam("businessDate") String date) {
    return service.preview(
        product, date == null ? null : ReferenceRateValidation.date(date, "businessDate"));
  }
}
