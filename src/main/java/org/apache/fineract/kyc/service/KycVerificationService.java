/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
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

    List<KycVerification> findByClientIdAndStatus(Long clientId, String status);
}