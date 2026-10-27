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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@Path("/v2/base-teller/vault-cash-deposits/preview")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "Vault Cash Deposit Preview",
    description = "Read-only proposed cash movements; financial execution is disabled")
@RequiredArgsConstructor
public class VaultDepositPreviewsApiResource {
  private final VaultDepositPreviewService service;

  @POST
  @ApiResponse(
      responseCode = "200",
      description = "Valid mathematical preview; financial execution remains blocked")
  @ApiResponse(responseCode = "400", description = "Malformed or invalid preview fields")
  @ApiResponse(responseCode = "401", description = "Authentication required")
  @ApiResponse(responseCode = "403", description = "Permission or branch custody denied")
  @ApiResponse(responseCode = "404", description = "Office vault not found")
  @ApiResponse(responseCode = "500", description = "Configuration or infrastructure unavailable")
  @Operation(
      summary =
          "Validate quantities and calculate an authorized General Transaction preview without"
              + " posting")
  public VaultDepositPreviewData preview(
      @RequestBody(
              required = true,
              content =
                  @Content(schema = @Schema(implementation = VaultDepositPreviewRequest.class)))
          String json) {
    return service.preview(json);
  }
}
