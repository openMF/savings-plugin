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
     * Traverses the kycVerification relationship and queries by its id field.
     * Underscore notation: KycVerification_Id → kycVerification.id
     */
    Optional<KycFeatureStatus> findByKycVerification_Id(Long kycVerificationId);

    /**
     * Latest feature status by client ID.
     * Traverses: kycVerification.clientId, orders by kycVerification.id DESC
     */
    Optional<KycFeatureStatus> findFirstByKycVerification_ClientIdOrderByKycVerification_IdDesc(Long clientId);

    /**
     * Latest APPROVED feature status by client ID.
     * Traverses: kycVerification.clientId AND kycVerification.kycStatus
     */
    Optional<KycFeatureStatus> findFirstByKycVerification_ClientIdAndKycVerification_KycStatusOrderByKycVerification_IdDesc(
            Long clientId, String kycStatus);
}