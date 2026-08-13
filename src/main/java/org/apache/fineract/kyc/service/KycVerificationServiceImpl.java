/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.apache.fineract.kyc.domain.KycFeatureStatus;
import org.apache.fineract.kyc.domain.KycIdVerification;
import org.apache.fineract.kyc.domain.KycVerification;
import org.apache.fineract.kyc.repository.KycVerificationRepository;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class KycVerificationServiceImpl implements KycVerificationService {

  private final KycVerificationRepository kycVerificationRepository;
  private final ObjectMapper objectMapper;
  private final ClientRepositoryWrapper clientRepositoryWrapper;
  private static final Long SYSTEM_USER_ID = 1L;
  private final KycStatusDerivationService kycStatusDerivationService;

  public KycVerificationServiceImpl(
      final KycVerificationRepository kycVerificationRepository,
      final ObjectMapper objectMapper,
      final ClientRepositoryWrapper clientRepositoryWrapper,
      final KycStatusDerivationService kycStatusDerivationService) {
    this.kycVerificationRepository = kycVerificationRepository;
    this.objectMapper = objectMapper;
    this.clientRepositoryWrapper = clientRepositoryWrapper;
    this.kycStatusDerivationService = kycStatusDerivationService;
  }

  @Override
  @Transactional
  public KycVerification processWebhook(final Long clientId, final KycWebhookPayload payload) {

    this.clientRepositoryWrapper.findOneWithNotFoundDetection(clientId);

    // Same sessionId → update in place (Declined → Approved must overwrite, not keep stale flags)
    if (payload.getSessionId() != null) {
      final Optional<KycVerification> existing =
          kycVerificationRepository.findBySessionId(payload.getSessionId());
      if (existing.isPresent()) {
        return updateExistingVerification(existing.get(), payload);
      }
    }

    final KycVerification verification =
        KycVerification.create(
            clientId,
            payload.getSessionId(),
            payload.getWorkflowId(),
            payload.getWorkflowVersion(),
            payload.getWebhookType(),
            payload.getStatus(),
            payload.getTimestamp(),
            payload.getCreatedAt(),
            serializeMetadata(payload.getMetadata()),
            SYSTEM_USER_ID);

    applyDecisionAndFeatureStatus(verification, payload, false);

    return kycVerificationRepository.saveAndFlush(verification);
  }

    /**
   * Updates an existing verification for the same sessionId.
   * Clears the old decision (unique on kyc_verification_id) before attaching a new one,
   * and updates feature status IN PLACE so Declined → Approved is persisted.
   */
  private KycVerification updateExistingVerification(
      final KycVerification verification, final KycWebhookPayload payload) {

    verification.updateFromWebhook(
        payload.getStatus(),
        payload.getTimestamp(),
        payload.getWebhookType(),
        payload.getWorkflowId(),
        payload.getWorkflowVersion(),
        serializeMetadata(payload.getMetadata()));

    // Force-delete existing decision first (unique constraint uk_kyc_decision_verification)
    if (verification.getDecision() != null) {
      verification.setDecision(null);
      kycVerificationRepository.saveAndFlush(verification);
    }

    applyDecisionAndFeatureStatus(verification, payload, true);

    return kycVerificationRepository.saveAndFlush(verification);
  }

  /**
   * @param updateInPlace when true, mutate existing KycFeatureStatus instead of replacing it
   */
  private void applyDecisionAndFeatureStatus(
      final KycVerification verification,
      final KycWebhookPayload payload,
      final boolean updateInPlace) {

    if (payload.getDecision() == null) {
      return;
    }

    final Decision decisionDto = payload.getDecision();

    final OffsetDateTime decisionCreatedAt = parseOffsetDateTime(decisionDto.getCreatedAt());
    final KycDecision decision =
        KycDecision.create(
            decisionDto.getStatus(),
            decisionDto.getWorkflowId(),
            decisionCreatedAt,
            SYSTEM_USER_ID);

    if (decisionDto.getFeatures() != null) {
      for (final String featureName : decisionDto.getFeatures()) {
        decision.addFeature(KycDecisionFeature.create(featureName, SYSTEM_USER_ID));
      }
    }

    if (decisionDto.getFaceMatches() != null) {
      for (final FaceMatch fmDto : decisionDto.getFaceMatches()) {
        decision.addFaceMatch(
            KycFaceMatch.create(
                fmDto.getNodeId(),
                fmDto.getScore(),
                fmDto.getStatus(),
                fmDto.getSourceImage(),
                fmDto.getTargetImage(),
                SYSTEM_USER_ID));
      }
    }

    if (decisionDto.getIdVerifications() != null) {
      for (final IdVerification idDto : decisionDto.getIdVerifications()) {
        decision.addIdVerification(
            KycIdVerification.create(
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
                SYSTEM_USER_ID));
      }
    }

    if (decisionDto.getAmlScreenings() != null) {
      for (final AmlScreening amlDto : decisionDto.getAmlScreenings()) {
        final LocalDate screenedDob =
            amlDto.getScreenedData() != null
                ? parseLocalDate(amlDto.getScreenedData().getDateOfBirth())
                : null;

        final KycAmlScreening screening =
            KycAmlScreening.create(
                amlDto.getNodeId(),
                amlDto.getStatus(),
                amlDto.getTotalHits(),
                amlDto.getScreenedData() != null
                    ? amlDto.getScreenedData().getNationality()
                    : null,
                amlDto.getScreenedData() != null ? amlDto.getScreenedData().getFullName() : null,
                amlDto.getScreenedData() != null
                    ? amlDto.getScreenedData().getDocumentNumber()
                    : null,
                screenedDob,
                SYSTEM_USER_ID);

        if (amlDto.getHits() != null) {
          for (final Object hitObj : amlDto.getHits()) {
            screening.addHit(KycAmlHit.create(serializeToJson(hitObj), SYSTEM_USER_ID));
          }
        }

        decision.addAmlScreening(screening);
      }
    }

    final boolean faceMatchesApproved =
        decisionDto.getFaceMatches() != null
            && decisionDto.getFaceMatches().stream()
                .anyMatch(fm -> isApprovedStatus(fm.getStatus()));

    final boolean idVerificationsApproved =
        decisionDto.getIdVerifications() != null
            && decisionDto.getIdVerifications().stream()
                .anyMatch(id -> isApprovedStatus(id.getStatus()));

    final boolean amlScreeningsApproved =
        decisionDto.getAmlScreenings() != null
            && decisionDto.getAmlScreenings().stream()
                .anyMatch(aml -> isApprovedStatus(aml.getStatus()));

    final boolean hasDecision = StringUtils.hasText(decisionDto.getStatus());

    final String kycStatus =
        kycStatusDerivationService.deriveStatus(
            faceMatchesApproved,
            idVerificationsApproved,
            amlScreeningsApproved,
            hasDecision,
            decisionDto.getStatus());

    // Feature status: update in place on re-delivery (avoids stale Declined flags)
    if (updateInPlace && verification.getFeatureStatus() != null) {
      verification
          .getFeatureStatus()
          .update(
              faceMatchesApproved,
              idVerificationsApproved,
              amlScreeningsApproved,
              hasDecision,
              kycStatus,
              SYSTEM_USER_ID);
    } else {
      final KycFeatureStatus featureStatus =
          KycFeatureStatus.create(
              faceMatchesApproved,
              idVerificationsApproved,
              amlScreeningsApproved,
              hasDecision,
              kycStatus,
              SYSTEM_USER_ID);
      verification.setFeatureStatus(featureStatus);
    }

    // Decision: only set after any previous decision was cleared + flushed in updateExistingVerification
    verification.setDecision(decision);
    verification.setKycStatus(kycStatus);
  }

  private static boolean isApprovedStatus(final String status) {
    return status != null && "Approved".equalsIgnoreCase(status.trim());
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
  public List<KycVerification> findByClientIdAndStatus(
      final Long clientId, final Optional<String> status) {
    return kycVerificationRepository.findByClientIdAndKycStatus(clientId, status);
  }

  private OffsetDateTime parseOffsetDateTime(final String isoDateTime) {
    if (isoDateTime == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(isoDateTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    } catch (Exception e) {
      return ZonedDateTime.parse(isoDateTime, DateTimeFormatter.ISO_ZONED_DATE_TIME)
          .toOffsetDateTime();
    }
  }

  private LocalDate parseLocalDate(final String dateStr) {
    if (dateStr == null) {
      return null;
    }
    return LocalDate.parse(dateStr);
  }

  private String serializeMetadata(final Object metadata) {
    if (metadata == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (Exception e) {
      return metadata.toString();
    }
  }

  private String serializeParsedAddress(final Object parsedAddress) {
    if (parsedAddress == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(parsedAddress);
    } catch (Exception e) {
      return parsedAddress.toString();
    }
  }

  private String serializeToJson(final Object obj) {
    if (obj == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (Exception e) {
      return obj.toString();
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<KycVerificationSummaryData> findSummaryByClientId(final Long clientId) {
    return kycVerificationRepository.findByClientIdOrderByCreatedOnUtcDesc(clientId).stream()
        .map(this::toSummaryData)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<KycVerificationSummaryData> findSummaryByClientIdAndStatus(
      final Long clientId, final Optional<String> status) {
    return kycVerificationRepository.findByClientIdAndKycStatus(clientId, status).stream()
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
        v.getCreatedOnUtc());
  }
}