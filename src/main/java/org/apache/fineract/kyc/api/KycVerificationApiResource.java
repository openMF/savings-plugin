/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Path;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.kyc.data.KycWebhookPayload;
import org.apache.fineract.kyc.domain.KycVerification;
import org.apache.fineract.kyc.service.KycVerificationService;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@Path("/v2/kyc")
@Component
@Tag(name = "KYC Verification", description = "Manage external KYC verification data")
@RequiredArgsConstructor
public class KycVerificationApiResource {

    private final KycVerificationService kycVerificationService;

    @Operation(summary = "Receive KYC webhook from external provider")
    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> receiveWebhook(
            @RequestHeader("X-Client-Id") final Long clientId,
            @RequestBody final KycWebhookPayload payload) {

        final KycVerification verification = kycVerificationService.processWebhook(clientId, payload);

        return ResponseEntity.ok(Map.of(
                "id", verification.getId(),
                "sessionId", verification.getSessionId(),
                "status", verification.getKycStatus(),
                "clientId", verification.getClientId()
        ));
    }

    @Operation(summary = "Get KYC verification by ID with full details")
    @GetMapping(value = "/verifications/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<KycVerification> getVerification(@PathVariable final Long id) {
        return kycVerificationService.findByIdWithDetails(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "List KYC verifications for a client")
    @GetMapping(value = "/clients/{clientId}/verifications", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<KycVerification>> listByClient(
            @PathVariable final Long clientId,
            @RequestParam(value = "status", required = false) final String status) {

        final List<KycVerification> verifications;
        if (status != null) {
            verifications = kycVerificationService.findByClientIdAndStatus(clientId, status);
        } else {
            verifications = kycVerificationService.findByClientId(clientId);
        }
        return ResponseEntity.ok(verifications);
    }
}