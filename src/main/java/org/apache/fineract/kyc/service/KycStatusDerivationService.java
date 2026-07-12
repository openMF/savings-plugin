/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.service;

import org.springframework.stereotype.Service;

@Service
public class KycStatusDerivationService {

  /**
   * Derives the KYC status from the boolean flags and the decision status received from the
   * external KYC provider.
   *
   * <p>Rules: - All TRUE + decision "Approved" → "Approved" - Decision "Declined" → "Rejected" -
   * Decision "Approved" but missing features → "In Review" - No decision or null → "In Review"
   */
  public String deriveStatus(
      final boolean faceMatches,
      final boolean idVerifications,
      final boolean amlScreenings,
      final boolean decision,
      final String providerDecisionStatus) {

    // All features completed and provider approved
    if (faceMatches && idVerifications && amlScreenings && decision) {
      if ("Approved".equalsIgnoreCase(providerDecisionStatus)) {
        return "Approved";
      }
      if ("Declined".equalsIgnoreCase(providerDecisionStatus)) {
        return "Rejected";
      }
    }

    // Provider explicitly declined
    if ("Declined".equalsIgnoreCase(providerDecisionStatus)) {
      return "Rejected";
    }

    // Anything else is still in progress or pending
    return "In Review";
  }
}
