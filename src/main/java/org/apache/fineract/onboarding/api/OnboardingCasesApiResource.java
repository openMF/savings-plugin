/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.onboarding.data.AcquisitionBoardData;
import org.apache.fineract.onboarding.data.EnrollmentCaseData;
import org.apache.fineract.onboarding.data.EnrollmentCasesRequest;
import org.apache.fineract.onboarding.service.AcquisitionBoardReadPlatformService;
import org.apache.fineract.onboarding.service.EnrollmentStatusReadPlatformService;
import org.springframework.stereotype.Component;

/** Back-office read model for source-qualified enrollment dashboard data. */
@Path("/v2/onboarding/cases")
@Component
@Tag(name = "Onboarding Cases", description = "Read enrollment status projections")
@RequiredArgsConstructor
public class OnboardingCasesApiResource {

  private static final String RESOURCE_NAME_FOR_PERMISSIONS = "CLIENT";
  private static final String ENROLLMENT_STATUS_RESOURCE_NAME = "ENROLLMENT_STATUS";

  private final PlatformSecurityContext context;
  private final EnrollmentStatusReadPlatformService readPlatformService;
  private final AcquisitionBoardReadPlatformService acquisitionBoardReadPlatformService;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "List enrollment status cases",
      description =
          "Returns office-scoped enrollment status projections derived from Fineract client"
              + " lifecycle and savings-plugin KYC records. Unsupported stages are returned as"
              + " UNKNOWN instead of inferred.")
  public Page<EnrollmentCaseData> retrieveEnrollmentCases(
      @BeanParam final EnrollmentCasesRequest request) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    context.authenticatedUser().validateHasReadPermission(ENROLLMENT_STATUS_RESOURCE_NAME);
    return readPlatformService.retrieveEnrollmentCases(request);
  }

  @GET
  @Path("/{clientId}/accounts/{savingsAccountId}/acquisition-board")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Retrieve the acquisition status board for a savings account",
      description =
          "Returns the six acquisition stages from explicit prospect events, Fineract savings"
              + " lifecycle state, and posted non-reversed savings transactions. The supplied"
              + " account must belong to the supplied client and the authenticated user's office"
              + " hierarchy.")
  public AcquisitionBoardData retrieveAcquisitionBoard(
      @PathParam("clientId") final Long clientId,
      @PathParam("savingsAccountId") final Long savingsAccountId) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    context.authenticatedUser().validateHasReadPermission("savingsaccount");
    context.authenticatedUser().validateHasReadPermission(ENROLLMENT_STATUS_RESOURCE_NAME);
    return acquisitionBoardReadPlatformService.retrieve(clientId, savingsAccountId);
  }
}
