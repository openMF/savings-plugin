/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.creditapplication.data.CreditApplicationData;
import org.apache.fineract.creditapplication.data.CreditApplicationSearchRequest;
import org.apache.fineract.creditapplication.service.CreditApplicationReadPlatformService;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

/** Back-office search resource for Fineract credit/loan applications. */
@Path("/v2/credit-applications")
@Component
@Tag(
    name = "Credit Applications",
    description = "Server-side search over authoritative Fineract loan applications")
@RequiredArgsConstructor
public class CreditApplicationApiResource {

  private static final String RESOURCE_NAME_FOR_PERMISSIONS = "LOAN";

  private final PlatformSecurityContext context;
  private final CreditApplicationReadPlatformService readPlatformService;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Search credit applications",
      description =
          "Returns an office-scoped, filtered and deterministically sorted page of loan"
              + " applications. Dates use yyyy-MM-dd and amounts use proposed/requested principal.")
  public Page<CreditApplicationData> search(
      @BeanParam final CreditApplicationSearchRequest request) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    return readPlatformService.search(request);
  }
}
