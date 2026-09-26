/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class KycStatusDerivationService {

  /**
   * Person KYC derivation (backward compatible).
   */
  public String deriveStatus(
      final boolean faceMatchesApproved,
      final boolean idVerificationsApproved,
      final boolean amlScreeningsApproved,
      final boolean hasDecision,
      final String decisionStatus) {
    return deriveStatus(
        faceMatchesApproved,
        idVerificationsApproved,
        amlScreeningsApproved,
        true,
        true,
        false,
        hasDecision,
        decisionStatus);
  }

  /**
   * Derives overall KYC/KYB status.
   *
   * <p>When {@code isCompanyVerification} is true, questionnaire (and email when present in the
   * workflow) flags are required in addition to legal-representative face / id / aml flags.
   *
   * <p>If a feature list was empty for the session, pass {@code true} for that flag so it does not
   * block approval (caller decides based on Didit {@code features[]} array).
   */
  public String deriveStatus(
      final boolean faceMatchesApproved,
      final boolean idVerificationsApproved,
      final boolean amlScreeningsApproved,
      final boolean questionnairesApproved,
      final boolean emailVerificationsApproved,
      final boolean isCompanyVerification,
      final boolean hasDecision,
      final String decisionStatus) {

    if (StringUtils.hasText(decisionStatus)) {
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
      return decisionStatus;
    }

    final boolean featuresOk;
    if (isCompanyVerification) {
      featuresOk =
          questionnairesApproved
              && emailVerificationsApproved
              && faceMatchesApproved
              && idVerificationsApproved
              && amlScreeningsApproved;
    } else {
      featuresOk = faceMatchesApproved && idVerificationsApproved && amlScreeningsApproved;
    }

    if (featuresOk) {
      return "Approved";
    }
    if (hasDecision) {
      return "Declined";
    }
    return "Pending";
  }
}