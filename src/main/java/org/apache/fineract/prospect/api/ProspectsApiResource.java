/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.prospect.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.prospect.data.ProspectData;
import org.apache.fineract.prospect.data.ProspectSearchRequest;
import org.apache.fineract.prospect.service.ProspectReadPlatformService;
import org.springframework.stereotype.Component;

@Path("/v2/prospects")
@Component
@Tag(
    name = "Prospects",
    description = "Server-side search over explicit pending prospect registration records")
@RequiredArgsConstructor
public class ProspectsApiResource {

  private static final String RESOURCE_NAME_FOR_PERMISSIONS = "PROSPECT";

  private final PlatformSecurityContext context;
  private final ProspectReadPlatformService readPlatformService;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Search pending prospects",
      description =
          "Returns an office-scoped, filtered and deterministically sorted page of explicit"
              + " prospect registration records. This endpoint does not create or infer prospects"
              + " from clients, KYC, loans, documents or identifiers. Stage fields are derived"
              + " only from append-only prospect stage events.")
  public Page<ProspectData> search(@BeanParam final ProspectSearchRequest request) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    return readPlatformService.search(request);
  }
}
