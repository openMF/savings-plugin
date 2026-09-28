/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.CatalogUpdateCategory;
import org.apache.fineract.baseteller.data.CatalogUpdateData;
import org.apache.fineract.baseteller.service.CatalogUpdatePlatformService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/catalog-updates")
@Component
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Base Teller Catalog Updates")
@RequiredArgsConstructor
public class CatalogUpdatesApiResource {

  private static final String RESOURCE = "BASE_TELLER_CATALOG_UPDATE";

  private final PlatformSecurityContext context;
  private final CatalogUpdatePlatformService service;

  @GET
  @Operation(
      summary = "Retrieve all Base Teller catalog refresh statuses",
      description =
          "needsUpdate is a freshness-policy recommendation, not a claim that source data changed.")
  public List<CatalogUpdateData> retrieveAll() {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return service.retrieveAll();
  }

  @GET
  @Path("/{category}")
  @Operation(summary = "Retrieve one Base Teller catalog refresh status")
  public CatalogUpdateData retrieve(@PathParam("category") final String category) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return service.retrieve(CatalogUpdateCategory.parse(category));
  }

  @POST
  @Path("/{category}/sync")
  @Operation(summary = "Validate and refresh one Base Teller catalog category")
  public CatalogUpdateData synchronize(@PathParam("category") final String category) {
    context.authenticatedUser().validateHasUpdatePermission(RESOURCE);
    return service.synchronize(CatalogUpdateCategory.parse(category));
  }
}
