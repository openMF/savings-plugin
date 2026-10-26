/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/vault")
@Component
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "Base Teller Vault",
    description = "Read-only office vault context; no deposit preview or execution")
@RequiredArgsConstructor
public class VaultApiResource {
  private final VaultReadPlatformService service;

  @GET
  @Path("/context")
  @Operation(summary = "Retrieve an authorized office vault and Phase 1 capabilities")
  public VaultData.Context context(@QueryParam("officeId") String officeId) {
    return service.context(officeId);
  }

  @GET
  @Path("/currencies")
  @Operation(summary = "Retrieve tenant-configured currencies for the authorized vault")
  public VaultData.Currencies currencies(@QueryParam("officeId") String officeId) {
    return service.currencies(officeId);
  }

  @GET
  @Path("/denominations")
  @Operation(summary = "Retrieve active persisted banknotes and coins for one enabled currency")
  public VaultData.Denominations denominations(
      @QueryParam("officeId") String officeId, @QueryParam("currencyCode") String currencyCode) {
    return service.denominations(officeId, currencyCode);
  }

  @GET
  @Path("/inventory")
  @Operation(summary = "Retrieve existing recorded vault cash balances, or explicit unavailability")
  public VaultData.Inventory inventory(
      @QueryParam("officeId") String officeId, @QueryParam("currencyCode") String currencyCode) {
    return service.inventory(officeId, currencyCode);
  }
}
