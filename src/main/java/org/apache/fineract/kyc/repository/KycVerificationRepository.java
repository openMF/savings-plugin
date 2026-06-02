/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import org.apache.fineract.kyc.domain.KycVerification;

public interface KycVerificationRepository extends JpaRepository<KycVerification, Long> {

    Optional<KycVerification> findBySessionId(String sessionId);

    List<KycVerification> findByClientIdOrderByCreatedOnUtcDesc(Long clientId);

    List<KycVerification> findByClientIdAndKycStatus(Long clientId, Optional<String> kycStatus);

    @Query("SELECT v FROM KycVerification v " +
           "LEFT JOIN FETCH v.decision d " +
           "LEFT JOIN FETCH d.features " +
           "LEFT JOIN FETCH d.faceMatches " +
           "LEFT JOIN FETCH d.idVerifications " +
           "LEFT JOIN FETCH d.amlScreenings " +
           "WHERE v.id = :id")
    Optional<KycVerification> findByIdWithAllDetails(@Param("id") Long id);

    boolean existsBySessionId(String sessionId);
}