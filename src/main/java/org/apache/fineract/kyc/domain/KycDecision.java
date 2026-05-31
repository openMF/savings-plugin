/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "m_client_kyc_decision")
public class KycDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kyc_verification_id", nullable = false)
    private Long kycVerificationId;

    @Column(name = "decision_status", nullable = false, length = 50)
    private String decisionStatus;

    @Column(name = "decision_workflow_id", length = 255)
    private String decisionWorkflowId;

    @Column(name = "decision_created_at")
    private OffsetDateTime decisionCreatedAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_on_utc", nullable = false)
    private OffsetDateTime createdOnUtc;

    @Column(name = "last_modified_by", nullable = false)
    private Long lastModifiedBy;

    @Column(name = "last_modified_on_utc", nullable = false)
    private OffsetDateTime lastModifiedOnUtc;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kyc_verification_id", insertable = false, updatable = false)
    private KycVerification kycVerification;

    @OneToMany(mappedBy = "kycDecision", cascade = CascadeType.ALL,
               fetch = FetchType.LAZY, orphanRemoval = true)
    private List<KycDecisionFeature> features = new ArrayList<>();

    @OneToMany(mappedBy = "kycDecision", cascade = CascadeType.ALL,
               fetch = FetchType.LAZY, orphanRemoval = true)
    private List<KycFaceMatch> faceMatches = new ArrayList<>();

    @OneToMany(mappedBy = "kycDecision", cascade = CascadeType.ALL,
               fetch = FetchType.LAZY, orphanRemoval = true)
    private List<KycIdVerification> idVerifications = new ArrayList<>();

    @OneToMany(mappedBy = "kycDecision", cascade = CascadeType.ALL,
               fetch = FetchType.LAZY, orphanRemoval = true)
    private List<KycAmlScreening> amlScreenings = new ArrayList<>();

    protected KycDecision() {}

    public static KycDecision create(final String decisionStatus,
                                     final String decisionWorkflowId,
                                     final OffsetDateTime decisionCreatedAt,
                                     final Long createdBy) {
        final KycDecision d = new KycDecision();
        d.decisionStatus = decisionStatus;
        d.decisionWorkflowId = decisionWorkflowId;
        d.decisionCreatedAt = decisionCreatedAt;
        d.createdBy = createdBy;
        d.lastModifiedBy = createdBy;
        final OffsetDateTime now = OffsetDateTime.now();
        d.createdOnUtc = now;
        d.lastModifiedOnUtc = now;
        return d;
    }

    // ── Association helpers ──────────────────────────────────

    public void addFeature(final KycDecisionFeature feature) {
        features.add(feature);
        feature.setKycDecision(this);
    }

    public void addFaceMatch(final KycFaceMatch faceMatch) {
        faceMatches.add(faceMatch);
        faceMatch.setKycDecision(this);
    }

    public void addIdVerification(final KycIdVerification idVerification) {
        idVerifications.add(idVerification);
        idVerification.setKycDecision(this);
    }

    public void addAmlScreening(final KycAmlScreening amlScreening) {
        amlScreenings.add(amlScreening);
        amlScreening.setKycDecision(this);
    }

    // ── Setters (package-private) ────────────────────────────

    void setKycVerification(final KycVerification kycVerification) {
        this.kycVerification = kycVerification;
        if (kycVerification != null) {
            this.kycVerificationId = kycVerification.getId();
        }
    }

    void setKycVerificationId(final Long kycVerificationId) {
        this.kycVerificationId = kycVerificationId;
    }

    // ── Getters ──────────────────────────────────────────────

    public Long getId() { return id; }
    public Long getKycVerificationId() { return kycVerificationId; }
    public String getDecisionStatus() { return decisionStatus; }
    public String getDecisionWorkflowId() { return decisionWorkflowId; }
    public OffsetDateTime getDecisionCreatedAt() { return decisionCreatedAt; }
    public List<KycDecisionFeature> getFeatures() { return features; }
    public List<KycFaceMatch> getFaceMatches() { return faceMatches; }
    public List<KycIdVerification> getIdVerifications() { return idVerifications; }
    public List<KycAmlScreening> getAmlScreenings() { return amlScreenings; }
}