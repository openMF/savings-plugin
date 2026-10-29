/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.ApiGlobalErrorResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

@Component
@Path("/v2/base-teller/vault-cash-deposits")
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "Vault Deposit History",
    description = "Read-only authoritative blocked attempt evidence")
@RequiredArgsConstructor
public class VaultDepositHistoryApiResource {
  private final VaultDepositHistoryService service;

  @GET
  @Path("/history")
  @Operation(summary = "Search authorized persisted vault deposit attempts, newest first")
  public VaultDepositHistoryData.Page history(
      @QueryParam("officeId") String officeId,
      @QueryParam("vaultKey") String vaultKey,
      @QueryParam("currencyCode") String currencyCode,
      @QueryParam("status") String status,
      @QueryParam("fromDate") String fromDate,
      @QueryParam("toDate") String toDate,
      @QueryParam("reference") String reference,
      @QueryParam("offset") String offset,
      @QueryParam("limit") String limit) {
    return read(
        () ->
            service.history(
                new VaultDepositHistoryQuery(
                    officeId,
                    vaultKey,
                    currencyCode,
                    status,
                    fromDate,
                    toDate,
                    reference,
                    offset,
                    limit)));
  }

  @GET
  @Path("/{reference}")
  @Operation(summary = "Retrieve an authorized immutable attempt snapshot")
  public VaultDepositHistoryData.Detail detail(@PathParam("reference") String reference) {
    return read(() -> service.detail(reference));
  }

  @GET
  @Path("/{reference}/audit")
  @Operation(
      summary = "Retrieve persisted attempt evidence; no synthetic approval or posting events")
  public VaultDepositHistoryData.Audit audit(@PathParam("reference") String reference) {
    return read(() -> service.audit(reference));
  }

  @GET
  @Path("/{reference}/receipt")
  @Operation(summary = "Check authorized receipt eligibility; blocked attempts return 409")
  public Response receipt(@PathParam("reference") String reference) {
    var eligibility = read(() -> service.detail(reference)).receiptEligibility();
    // Phase 3 has no posted transaction/receipt model. Never fabricate a success DTO.
    String message = "No posted receipt exists for this policy-blocked vault deposit attempt.";
    return Response.status(Response.Status.CONFLICT)
        .entity(
            ApiGlobalErrorResponse.create(
                409,
                "error.msg.base.teller.vault.receipt."
                    + eligibility.reason().toLowerCase(java.util.Locale.ROOT),
                message,
                message))
        .build();
  }

  private static <T> T read(Supplier<T> request) {
    try {
      return request.get();
    } catch (DataAccessException | TransactionException e) {
      // Match Phase 3: failures in the transaction interceptor must also be sanitized.
      throw VaultDepositHistoryService.unavailable();
    }
  }
}
