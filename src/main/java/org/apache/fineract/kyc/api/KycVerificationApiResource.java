/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.kyc.data.KycWebhookPayload;
import org.apache.fineract.kyc.domain.KycVerification;
import org.apache.fineract.kyc.service.KycVerificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Path("/v2/kyc")
@Component
@Tag(name = "KYC Verification", description = "Manage external KYC verification data")
@RequiredArgsConstructor
public class KycVerificationApiResource {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(KycVerificationApiResource.class);

    private final KycVerificationService kycVerificationService;

    @Operation(summary = "Receive KYC webhook from external provider")    
    @POST
    @Path("/webhook")
    @Consumes({MediaType.APPLICATION_JSON})
    @Produces({MediaType.APPLICATION_JSON})
    public ResponseEntity<Map<String, Object>> receiveWebhook(
            @HeaderParam("X-Client-Id") final Long clientId,
            final KycWebhookPayload payload) {

        final KycVerification verification = kycVerificationService.processWebhook(clientId, payload);

        return ResponseEntity.ok(Map.of(
                "id", verification.getId(),
                "sessionId", verification.getSessionId(),
                "status", verification.getKycStatus(),
                "clientId", verification.getClientId()
        ));
    }

    @Operation(summary = "Get KYC verification by ID with full details")    
    @GET
    @Path("/verifications/{id}")
    @Produces({MediaType.APPLICATION_JSON})
    public ResponseEntity<KycVerification> getVerification(@PathParam("id") @Parameter(description = "id") final Long id) {
        return kycVerificationService.findByIdWithDetails(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "List KYC verifications for a client")    
    @GET
    @Path("/clients/{clientId}/verifications")
    @Produces({MediaType.APPLICATION_JSON})
    public ResponseEntity<List<KycVerification>> listByClient(
            @PathParam("clientId") @Parameter(description = "clientId") final Long clientId,
            @QueryParam("status") final Optional<String> status) {

        final List<KycVerification> verifications;
        if (status != null) {
            verifications = kycVerificationService.findByClientIdAndStatus(clientId, status);
        } else {
            verifications = kycVerificationService.findByClientId(clientId);
        }
        return ResponseEntity.ok(verifications);
    }
}