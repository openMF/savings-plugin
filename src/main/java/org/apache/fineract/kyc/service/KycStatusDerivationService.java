package org.apache.fineract.kyc.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class KycStatusDerivationService {

  /**
   * Derives the overall KYC status that will be returned in the self-service
   * authentication response under {@code kycValidations.status}.
   *
   * <p>Priority:
   * <ol>
   *   <li>Explicit decision status from the provider (Approved, Declined, In Review, …)</li>
   *   <li>Fallback based on feature flags when no decision status is present</li>
   * </ol>
   */
  public String deriveStatus(
      final boolean faceMatchesApproved,
      final boolean idVerificationsApproved,
      final boolean amlScreeningsApproved,
      final boolean hasDecision,
      final String decisionStatus) {

    if (StringUtils.hasText(decisionStatus)) {
      // Normalise well-known Didit values
      if ("Approved".equalsIgnoreCase(decisionStatus)) {
        return "Approved";
      }
      if ("Declined".equalsIgnoreCase(decisionStatus)
          || "Rejected".equalsIgnoreCase(decisionStatus)) {
        return "Declined";
      }
      if ("In Review".equalsIgnoreCase(decisionStatus)
          || "In Progress".equalsIgnoreCase(decisionStatus)) {
        return "In Review";
      }
      if ("Pending".equalsIgnoreCase(decisionStatus)) {
        return "Pending";
      }
      // Pass through any other provider value (e.g. "Awaiting User", "Resubmitted")
      return decisionStatus;
    }

    // No decision status → derive from feature flags
    if (faceMatchesApproved && idVerificationsApproved && amlScreeningsApproved) {
      return "Approved";
    }
    if (hasDecision) {
      return "Declined";
    }
    return "Pending";
  }
}