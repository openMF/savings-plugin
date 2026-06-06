/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "m_client_kyc_feature_status")
public class KycFeatureStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Back-reference to parent (with @JsonIgnore to prevent serialization loops)
    @JsonIgnore
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "kyc_verification_id", nullable = false)
    private KycVerification kycVerification;

    @Column(name = "face_matches", nullable = false)
    private Boolean faceMatches;

    @Column(name = "id_verifications", nullable = false)
    private Boolean idVerifications;

    @Column(name = "aml_screenings", nullable = false)
    private Boolean amlScreenings;

    @Column(name = "decision", nullable = false)
    private Boolean decision;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_on_utc", nullable = false)
    private OffsetDateTime createdOnUtc;

    @Column(name = "last_modified_by", nullable = false)
    private Long lastModifiedBy;

    @Column(name = "last_modified_on_utc", nullable = false)
    private OffsetDateTime lastModifiedOnUtc;
    
    @Column(name = "kyc_status", nullable = false, length = 50)
    private String kycStatus;

    // ── JPA Constructor ──────────────────────────────────────

    protected KycFeatureStatus() {}

    // ── Factory Method ───────────────────────────────────────

    public static KycFeatureStatus create(final Boolean faceMatches,
                                          final Boolean idVerifications,
                                          final Boolean amlScreenings,
                                          final Boolean decision,
                                          final String kycStatus,
                                          final Long createdBy) {
        final KycFeatureStatus fs = new KycFeatureStatus();
        // Default to FALSE if null is passed
        fs.faceMatches = faceMatches != null ? faceMatches : Boolean.FALSE;
        fs.idVerifications = idVerifications != null ? idVerifications : Boolean.FALSE;
        fs.amlScreenings = amlScreenings != null ? amlScreenings : Boolean.FALSE;
        fs.decision = decision != null ? decision : Boolean.FALSE;
        fs.kycStatus = kycStatus != null ? kycStatus : "In Review";
        fs.createdBy = createdBy;
        fs.lastModifiedBy = createdBy;
        final OffsetDateTime now = OffsetDateTime.now();
        fs.createdOnUtc = now;
        fs.lastModifiedOnUtc = now;
        return fs;
    }

    // ── Relationship Setter ──────────────────────────────────

    public void setKycVerification(final KycVerification kycVerification) {
        this.kycVerification = kycVerification;
    }

    // ── Getters ──────────────────────────────────────────────

    public Long getId() { return id; }

    /**
     * Convenience getter to extract the verification ID without 
     * triggering the full parent object serialization.
     */
    public Long getKycVerificationId() {
        return kycVerification != null ? kycVerification.getId() : null;
    }

    public Boolean getFaceMatches() { return faceMatches; }
    public Boolean getIdVerifications() { return idVerifications; }
    public Boolean getAmlScreenings() { return amlScreenings; }
    public Boolean getDecision() { return decision; }
    public String getKycStatus() { return kycStatus; }
    public Long getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedOnUtc() { return createdOnUtc; }
    public Long getLastModifiedBy() { return lastModifiedBy; }
    public OffsetDateTime getLastModifiedOnUtc() { return lastModifiedOnUtc; }
}