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
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Staff metadata/review APIs only. No payroll, transfer, release, or closure command is exposed.
 */
@Component
@Path("/v1/savings/planned-savings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Planned Savings Configuration and Pockets")
@RequiredArgsConstructor
public class PlannedSavingsApiResource {
  private final PlannedSavingsService arrangements;
  private final SavingsPocketService pockets;

  @GET
  @Path("/template")
  public Response template() {
    return invoke(arrangements::template);
  }

  @GET
  @Path("/clients/{clientId}/arrangements")
  public Response list(
      @PathParam("clientId") long client,
      @QueryParam("afterId") @DefaultValue("") String after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> arrangements.list(client, after, limit));
  }

  @GET
  @Path("/clients/{clientId}/arrangements/{id}")
  public Response get(@PathParam("clientId") long client, @PathParam("id") String id) {
    return invoke(() -> arrangements.get(client, id));
  }

  @PUT
  @Path("/clients/{clientId}/arrangements/{id}")
  public Response save(
      @PathParam("clientId") long client, @PathParam("id") String id, String json) {
    return invoke(() -> arrangements.save(client, id, json));
  }

  @PUT
  @Path("/clients/{clientId}/arrangements/{id}/native-account")
  public Response link(
      @PathParam("clientId") long client, @PathParam("id") String id, String json) {
    return invoke(() -> arrangements.link(client, id, json));
  }

  @GET
  @Path("/clients/{clientId}/arrangements/{id}/contributions")
  public Response schedule(
      @PathParam("clientId") long client,
      @PathParam("id") String id,
      @QueryParam("revision") @DefaultValue("0") long revision,
      @QueryParam("afterSequence") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> arrangements.schedule(client, id, revision, after, limit));
  }

  @GET
  @Path("/clients/{clientId}/arrangements/{id}/maturity")
  public Response maturity(@PathParam("clientId") long client, @PathParam("id") String id) {
    return invoke(() -> arrangements.maturity(client, id));
  }

  @POST
  @Path("/clients/{clientId}/arrangements/{id}/reviews")
  public Response review(
      @PathParam("clientId") long client, @PathParam("id") String id, String json) {
    return invoke(() -> arrangements.review(client, id, json), 422);
  }

  @GET
  @Path("/clients/{clientId}/arrangements/{id}/audit")
  public Response arrangementAudit(
      @PathParam("clientId") long client,
      @PathParam("id") String id,
      @QueryParam("afterId") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    PlannedSavingsValidation.id(id);
    return invoke(() -> arrangements.history(client, "ARRANGEMENT", id, after, limit));
  }

  @GET
  @Path("/clients/{clientId}/pockets")
  public Response pockets(
      @PathParam("clientId") long client,
      @QueryParam("afterId") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> pockets.list(client, after, limit));
  }

  @GET
  @Path("/clients/{clientId}/pockets/{accountId}")
  public Response pocket(@PathParam("clientId") long client, @PathParam("accountId") long account) {
    return invoke(() -> pockets.get(client, account));
  }

  @PUT
  @Path("/clients/{clientId}/pockets/{accountId}")
  public Response savePocket(
      @PathParam("clientId") long client, @PathParam("accountId") long account, String json) {
    return invoke(() -> pockets.save(client, account, json));
  }

  @POST
  @Path("/clients/{clientId}/pockets/{accountId}/retire")
  public Response retirePocket(
      @PathParam("clientId") long client, @PathParam("accountId") long account, String json) {
    return invoke(() -> pockets.retire(client, account, json));
  }

  @GET
  @Path("/clients/{clientId}/pockets/{accountId}/transfer-preview")
  public Response preview(
      @PathParam("clientId") long client,
      @PathParam("accountId") long account,
      @QueryParam("amount") BigDecimal amount) {
    return invoke(() -> pockets.transferPreview(client, account, amount));
  }

  @GET
  @Path("/clients/{clientId}/pockets/{accountId}/audit")
  public Response pocketAudit(
      @PathParam("clientId") long client,
      @PathParam("accountId") long account,
      @QueryParam("afterId") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(
        () -> arrangements.history(client, "POCKET", Long.toString(account), after, limit));
  }

  @GET
  @Path("/clients/{clientId}/accounts/{accountId}")
  public Response account(
      @PathParam("clientId") long client, @PathParam("accountId") long account) {
    return invoke(() -> pockets.account(client, account));
  }

  @GET
  @Path("/clients/{clientId}/accounts/{accountId}/closure-eligibility")
  public Response closure(
      @PathParam("clientId") long client, @PathParam("accountId") long account) {
    return invoke(() -> pockets.closure(client, account));
  }

  private static Response invoke(Supplier<?> action) {
    return invoke(action, 200);
  }

  private static Response invoke(Supplier<?> action, int status) {
    try {
      return Response.status(status).entity(RatePropagationJson.JSON.toJson(action.get())).build();
    } catch (PlannedSavingsService.Conflict
        | ConcurrencyFailureException
        | DuplicateKeyException e) {
      return Response.status(409)
          .entity(Map.of("reasonCode", "REQUEST_CONFLICT", "financialRetryAllowed", false))
          .build();
    }
  }
}
