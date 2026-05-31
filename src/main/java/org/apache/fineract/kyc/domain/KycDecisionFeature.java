/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.apache.fineract.kyc.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "m_client_kyc_decision_feature")
public class KycDecisionFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kyc_decision_id", nullable = false)
    private Long kycDecisionId;

    @Column(name = "feature_name", nullable = false, length = 100)
    private String featureName;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_on_utc", nullable = false)
    private OffsetDateTime createdOnUtc;

    @Column(name = "last_modified_by", nullable = false)
    private Long lastModifiedBy;

    @Column(name = "last_modified_on_utc", nullable = false)
    private OffsetDateTime lastModifiedOnUtc;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kyc_decision_id", insertable = false, updatable = false)
    private KycDecision kycDecision;

    protected KycDecisionFeature() {}

    public static KycDecisionFeature create(final String featureName, final Long createdBy) {
        final KycDecisionFeature f = new KycDecisionFeature();
        f.featureName = featureName;
        f.createdBy = createdBy;
        f.lastModifiedBy = createdBy;
        final OffsetDateTime now = OffsetDateTime.now();
        f.createdOnUtc = now;
        f.lastModifiedOnUtc = now;
        return f;
    }

    void setKycDecision(final KycDecision kycDecision) {
        this.kycDecision = kycDecision;
        if (kycDecision != null) {
            this.kycDecisionId = kycDecision.getId();
        }
    }

    public Long getId() { return id; }
    public String getFeatureName() { return featureName; }
}