/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.ApiGlobalErrorResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

@Component
@Path("/v2/base-teller/vault-cash-deposits")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "Vault Cash Deposit Execution",
    description = "Fail-closed until deposit policies are approved")
@RequiredArgsConstructor
public class VaultDepositsApiResource {
  private final VaultDepositExecutionService service;

  @POST
  @Operation(summary = "Revalidate a vault deposit and persist the authoritative blocked outcome")
  @ApiResponse(
      responseCode = "409",
      description = "Policy blocked or idempotency conflict",
      content = @Content(schema = @Schema(implementation = VaultDepositExecutionData.class)))
  @ApiResponse(responseCode = "400", description = "Invalid execution or denomination fields")
  @ApiResponse(responseCode = "401", description = "Authentication required")
  @ApiResponse(responseCode = "403", description = "Permission or branch custody denied")
  @ApiResponse(responseCode = "404", description = "Office vault not found")
  @ApiResponse(responseCode = "500", description = "Configuration or mandatory audit unavailable")
  public Response execute(
      @RequestBody(
              required = true,
              content =
                  @Content(schema = @Schema(implementation = VaultDepositExecutionRequest.class)))
          String json) {
    VaultDepositExecutionService.Outcome result;
    try {
      result = service.execute(json);
    } catch (DataAccessException | TransactionException e) {
      // A transaction interceptor can fail while committing, after the service method returns.
      throw VaultDepositExecutionService.unavailable();
    }
    Object entity = result.data();
    if (result.idempotencyConflict()) {
      String message = "Idempotency key already belongs to a different vault deposit request.";
      entity =
          ApiGlobalErrorResponse.create(
              409, "error.msg.base.teller.vault.deposit.idempotency.conflict", message, message);
    }
    return Response.status(Response.Status.CONFLICT).entity(entity).build();
  }
}
