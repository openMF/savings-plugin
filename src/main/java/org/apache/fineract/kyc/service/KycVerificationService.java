/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.service;


import java.util.List;
import java.util.Optional;
import org.apache.fineract.kyc.data.KycWebhookPayload;
import org.apache.fineract.kyc.domain.KycVerification;

public interface KycVerificationService {

    /**
     * Processes the incoming KYC webhook and persists all verification data.
     *
     * @param clientId the m_client id to associate
     * @param payload  the deserialized webhook JSON
     * @return the persisted KycVerification
     */
    KycVerification processWebhook(Long clientId, KycWebhookPayload payload);

    Optional<KycVerification> findById(Long id);

    Optional<KycVerification> findByIdWithDetails(Long id);

    List<KycVerification> findByClientId(Long clientId);

    List<KycVerification> findByClientIdAndStatus(Long clientId, Optional<String> status);
}