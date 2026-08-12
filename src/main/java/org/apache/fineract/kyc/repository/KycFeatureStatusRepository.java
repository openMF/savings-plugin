/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.repository;

import java.util.Optional;
import org.apache.fineract.kyc.domain.KycFeatureStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KycFeatureStatusRepository extends JpaRepository<KycFeatureStatus, Long> {

  /**
   * Legacy: latest feature status by verification id (descending).
   * Kept for backward compatibility.
   */
  Optional<KycFeatureStatus> findFirstByKycVerification_ClientIdOrderByKycVerification_IdDesc(
      Long clientId);

  /**
   * Legacy: latest feature status for a given verification-level KYC status.
   * Kept for backward compatibility with {@code getApprovedKycFeatureStatus}.
   */
  Optional<KycFeatureStatus>
      findFirstByKycVerification_ClientIdAndKycVerification_KycStatusOrderByKycVerification_IdDesc(
          Long clientId, String kycStatus);

  /**
   * Latest feature status for the client ordered by last modification time, then id.
   * Used so a Declined → Approved update on the same session is visible on authentication.
   */
  Optional<KycFeatureStatus>
      findFirstByKycVerification_ClientIdOrderByLastModifiedOnUtcDescIdDesc(Long clientId);

  /**
   * Latest feature status for the client with the given feature-level {@code kycStatus}
   * (e.g. {@code "Approved"}), ordered by last modification time, then id.
   * Prefer this over Declined when the user re-applies and is later approved.
   */
  Optional<KycFeatureStatus>
      findFirstByKycVerification_ClientIdAndKycStatusOrderByLastModifiedOnUtcDescIdDesc(
          Long clientId, String kycStatus);
}