/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.apache.fineract.kyc.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "m_client_kyc_face_match")
public class KycFaceMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kyc_decision_id", nullable = false)
    private Long kycDecisionId;

    @Column(name = "node_id", length = 255)
    private String nodeId;

    @Column(name = "match_score", precision = 5, scale = 2)
    private BigDecimal matchScore;

    @Column(name = "match_status", length = 50)
    private String matchStatus;

    @Column(name = "source_image_url", columnDefinition = "TEXT")
    private String sourceImageUrl;

    @Column(name = "target_image_url", columnDefinition = "TEXT")
    private String targetImageUrl;

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

    protected KycFaceMatch() {}

    public static KycFaceMatch create(final String nodeId,
                                      final BigDecimal matchScore,
                                      final String matchStatus,
                                      final String sourceImageUrl,
                                      final String targetImageUrl,
                                      final Long createdBy) {
        final KycFaceMatch fm = new KycFaceMatch();
        fm.nodeId = nodeId;
        fm.matchScore = matchScore;
        fm.matchStatus = matchStatus;
        fm.sourceImageUrl = sourceImageUrl;
        fm.targetImageUrl = targetImageUrl;
        fm.createdBy = createdBy;
        fm.lastModifiedBy = createdBy;
        final OffsetDateTime now = OffsetDateTime.now();
        fm.createdOnUtc = now;
        fm.lastModifiedOnUtc = now;
        return fm;
    }

    void setKycDecision(final KycDecision kycDecision) {
        this.kycDecision = kycDecision;
        if (kycDecision != null) {
            this.kycDecisionId = kycDecision.getId();
        }
    }

    public Long getId() { return id; }
    public BigDecimal getMatchScore() { return matchScore; }
    public String getMatchStatus() { return matchStatus; }
    public String getSourceImageUrl() { return sourceImageUrl; }
    public String getTargetImageUrl() { return targetImageUrl; }
}