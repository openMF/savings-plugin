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
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** Administrative metadata endpoints. Permissions and office scope are enforced in the service. */
@Component
@Path("/v1/savings/account-relationships")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Savings Account Relationships")
@RequiredArgsConstructor
public class MainAccountApiResource {
  private final MainAccountService service;

  @GET
  @Path("/template")
  public MainAccountData.Capabilities template() {
    return service.template();
  }

  @GET
  @Path("/clients/{clientId}/main")
  public Response get(@PathParam("clientId") long client) {
    var result = service.get(client);
    return Response.ok(
            Map.of(
                "clientId", client, "relationships", result == null ? List.of() : List.of(result)))
        .build();
  }

  @PUT
  @Path("/clients/{clientId}/main")
  public Response bind(@PathParam("clientId") long client, String json) {
    try {
      return Response.ok(service.bind(client, json)).build();
    } catch (ConcurrencyFailureException | DuplicateKeyException e) {
      return Response.status(Response.Status.CONFLICT)
          .entity(
              Map.of(
                  "httpStatusCode",
                  "409",
                  "defaultUserMessage",
                  "Relationship changed; retrieve and retry.",
                  "userMessageGlobalisationCode",
                  "error.msg.savings.main.account.conflict",
                  "errors",
                  List.of()))
          .build();
    }
  }

  @GET
  @Path("/clients/{clientId}/audit")
  public List<MainAccountData.Audit> audit(
      @PathParam("clientId") long client,
      @QueryParam("afterId") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return service.audit(client, after, limit);
  }
}
