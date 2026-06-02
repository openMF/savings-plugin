/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.kyc.data.KycVerificationSummaryData;
import org.apache.fineract.kyc.data.KycWebhookPayload;
import org.apache.fineract.kyc.data.KycWebhookPayload.AmlScreening;
import org.apache.fineract.kyc.data.KycWebhookPayload.Decision;
import org.apache.fineract.kyc.data.KycWebhookPayload.FaceMatch;
import org.apache.fineract.kyc.data.KycWebhookPayload.IdVerification;
import org.apache.fineract.kyc.domain.KycAmlHit;
import org.apache.fineract.kyc.domain.KycAmlScreening;
import org.apache.fineract.kyc.domain.KycDecision;
import org.apache.fineract.kyc.domain.KycDecisionFeature;
import org.apache.fineract.kyc.domain.KycFaceMatch;
import org.apache.fineract.kyc.domain.KycIdVerification;
import org.apache.fineract.kyc.domain.KycVerification;
import org.apache.fineract.kyc.repository.KycVerificationRepository;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;

@Service
public class KycVerificationServiceImpl implements KycVerificationService {

    private final KycVerificationRepository kycVerificationRepository;
    private final ObjectMapper objectMapper;
    private final ClientRepositoryWrapper clientRepositoryWrapper;
    //TODO ADD a SYSTEM GENERATED USER FOR THIS PLUGIN
    // System user ID for automated webhook processing; adjust per your auth setup
    private static final Long SYSTEM_USER_ID = 1L;

    public KycVerificationServiceImpl(final KycVerificationRepository kycVerificationRepository,
                                    final ObjectMapper objectMapper,
                                    final ClientRepositoryWrapper clientRepositoryWrapper) {
        this.kycVerificationRepository = kycVerificationRepository;
        this.objectMapper = objectMapper;
        this.clientRepositoryWrapper = clientRepositoryWrapper;
    }

    @Override
    @Transactional
    public KycVerification processWebhook(final Long clientId, final KycWebhookPayload payload) {
    
        this.clientRepositoryWrapper.findOneWithNotFoundDetection(clientId);

        // Idempotency
        if (payload.getSessionId() != null) {
            final Optional<KycVerification> existing =
                    kycVerificationRepository.findBySessionId(payload.getSessionId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // 1. Build the root verification entity (NOT persisted yet)
        final KycVerification verification = KycVerification.create(
                clientId,
                payload.getSessionId(),
                payload.getWorkflowId(),
                payload.getWorkflowVersion(),
                payload.getWebhookType(),
                payload.getStatus(),
                payload.getTimestamp(),
                payload.getCreatedAt(),
                serializeMetadata(payload.getMetadata()),
                SYSTEM_USER_ID
        );

        // 2. Build the decision aggregate
        if (payload.getDecision() != null) {
            final Decision decisionDto = payload.getDecision();

            final OffsetDateTime decisionCreatedAt = parseOffsetDateTime(decisionDto.getCreatedAt());
            final KycDecision decision = KycDecision.create(
                    decisionDto.getStatus(),
                    decisionDto.getWorkflowId(),
                    decisionCreatedAt,
                    SYSTEM_USER_ID
            );

            // 2a. Features
            if (decisionDto.getFeatures() != null) {
                for (final String featureName : decisionDto.getFeatures()) {
                    decision.addFeature(KycDecisionFeature.create(featureName, SYSTEM_USER_ID));
                }
            }

            // 2b. Face matches
            if (decisionDto.getFaceMatches() != null) {
                for (final FaceMatch fmDto : decisionDto.getFaceMatches()) {
                    decision.addFaceMatch(KycFaceMatch.create(
                            fmDto.getNodeId(),
                            fmDto.getScore(),
                            fmDto.getStatus(),
                            fmDto.getSourceImage(),
                            fmDto.getTargetImage(),
                            SYSTEM_USER_ID
                    ));
                }
            }

            // 2c. ID verifications
            if (decisionDto.getIdVerifications() != null) {
                for (final IdVerification idDto : decisionDto.getIdVerifications()) {
                    decision.addIdVerification(KycIdVerification.create(
                            idDto.getNodeId(),
                            idDto.getStatus(),
                            idDto.getFirstName(),
                            idDto.getLastName(),
                            idDto.getFullName(),
                            parseLocalDate(idDto.getDateOfBirth()),
                            idDto.getGender(),
                            idDto.getAge(),
                            idDto.getNationality(),
                            idDto.getDocumentNumber(),
                            idDto.getDocumentType(),
                            idDto.getIssuingState(),
                            idDto.getIssuingStateName(),
                            idDto.getPersonalNumber(),
                            parseLocalDate(idDto.getExpirationDate()),
                            idDto.getFormattedAddress(),
                            serializeParsedAddress(idDto.getParsedAddress()),
                            SYSTEM_USER_ID
                    ));
                }
            }

            // 2d. AML screenings
            if (decisionDto.getAmlScreenings() != null) {
                for (final AmlScreening amlDto : decisionDto.getAmlScreenings()) {
                    final LocalDate screenedDob = amlDto.getScreenedData() != null
                            ? parseLocalDate(amlDto.getScreenedData().getDateOfBirth())
                            : null;

                    final KycAmlScreening screening = KycAmlScreening.create(
                            amlDto.getNodeId(),
                            amlDto.getStatus(),
                            amlDto.getTotalHits(),
                            amlDto.getScreenedData() != null ? amlDto.getScreenedData().getNationality() : null,
                            amlDto.getScreenedData() != null ? amlDto.getScreenedData().getFullName() : null,
                            amlDto.getScreenedData() != null ? amlDto.getScreenedData().getDocumentNumber() : null,
                            screenedDob,
                            SYSTEM_USER_ID
                    );

                    if (amlDto.getHits() != null) {
                        for (final Object hitObj : amlDto.getHits()) {
                            screening.addHit(KycAmlHit.create(
                                    serializeToJson(hitObj),
                                    SYSTEM_USER_ID
                            ));
                        }
                    }

                    decision.addAmlScreening(screening);
                }
            }

            // ✅ Single point: setDecision calls decision.setKycVerification(this) internally
            verification.setDecision(decision);
        }

        // ✅ ONE saveAndFlush — EclipseLink cascades the entire tree
        // Insert order: verification → decision → features/faceMatches/idVerifications/amlScreenings → hits
        // EclipseLink fills FK columns automatically from the @ManyToOne relationships
        return kycVerificationRepository.saveAndFlush(verification);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<KycVerification> findById(final Long id) {
        return kycVerificationRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<KycVerification> findByIdWithDetails(final Long id) {
        return kycVerificationRepository.findByIdWithAllDetails(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<KycVerification> findByClientId(final Long clientId) {
        return kycVerificationRepository.findByClientIdOrderByCreatedOnUtcDesc(clientId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<KycVerification> findByClientIdAndStatus(final Long clientId, final Optional<String> status) {
        return kycVerificationRepository.findByClientIdAndKycStatus(clientId, status);
    }

    // Helpers

    private OffsetDateTime parseOffsetDateTime(final String isoDateTime) {
        if (isoDateTime == null) return null;
        try {
            return OffsetDateTime.parse(isoDateTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            return ZonedDateTime.parse(isoDateTime, DateTimeFormatter.ISO_ZONED_DATE_TIME)
                    .toOffsetDateTime();
        }
    }

    private LocalDate parseLocalDate(final String dateStr) {
        if (dateStr == null) return null;
        return LocalDate.parse(dateStr);
    }

    private String serializeMetadata(final Object metadata) {
        if (metadata == null) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return metadata.toString();
        }
    }

    private String serializeParsedAddress(final Object parsedAddress) {
        if (parsedAddress == null) return null;
        try {
            return objectMapper.writeValueAsString(parsedAddress);
        } catch (Exception e) {
            return parsedAddress.toString();
        }
    }

    private String serializeToJson(final Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<KycVerificationSummaryData> findSummaryByClientId(final Long clientId) {
        return kycVerificationRepository.findByClientIdOrderByCreatedOnUtcDesc(clientId)
                .stream()
                .map(this::toSummaryData)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<KycVerificationSummaryData> findSummaryByClientIdAndStatus(final Long clientId, final Optional<String> status) {
        return kycVerificationRepository.findByClientIdAndKycStatus(clientId, status)
                .stream()
                .map(this::toSummaryData)
                .toList();
    }

    private KycVerificationSummaryData toSummaryData(final KycVerification v) {
        return new KycVerificationSummaryData(
                v.getId(),
                v.getClientId(),
                v.getSessionId(),
                v.getWorkflowId(),
                v.getWorkflowVersion(),
                v.getWebhookType(),
                v.getKycStatus(),
                v.getKycTimestamp(),
                v.getDecision() != null ? v.getDecision().getDecisionStatus() : null,
                v.getDecision() != null ? v.getDecision().getDecisionCreatedAt() : null,
                v.getCreatedOnUtc()
        );
    }
}