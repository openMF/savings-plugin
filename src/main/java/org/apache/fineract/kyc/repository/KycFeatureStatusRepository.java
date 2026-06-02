/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.kyc.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import org.apache.fineract.kyc.domain.KycFeatureStatus;

@Repository
public interface KycFeatureStatusRepository extends JpaRepository<KycFeatureStatus, Long> {

    /**
     * Finds the feature status for the latest KYC verification of a given client.
     * Uses Spring Data JPA method naming:
     *   findFirst     → LIMIT 1
     *   ByKycVerification_ClientId → JOIN on kycVerification WHERE client_id = ?
     *   OrderByKycVerification_IdDesc → ORDER BY kyc_verification_id DESC
     */
    Optional<KycFeatureStatus> findFirstByKycVerification_ClientIdOrderByKycVerification_IdDesc(Long clientId);

    /**
     * Finds the feature status for the latest APPROVED KYC verification.
     */
    Optional<KycFeatureStatus> findFirstByKycVerification_ClientIdAndKycVerification_KycStatusOrderByKycVerification_IdDesc(
            Long clientId, String kycStatus);

    Optional<KycFeatureStatus> findByKycVerificationId(Long kycVerificationId);
}