/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.apache.fineract.kyc.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import org.apache.fineract.kyc.data.KycWebhookPayload;
import org.apache.fineract.kyc.domain.KycVerification;
import org.apache.fineract.kyc.service.KycVerificationService;

@RestController
@RequestMapping("/v2/kyc")
@Tag(name = "KYC Verification", description = "Manage external KYC verification data")
public class KycVerificationApiResource {

    private final KycVerificationService kycVerificationService;

    public KycVerificationApiResource(final KycVerificationService kycVerificationService) {
        this.kycVerificationService = kycVerificationService;
    }

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