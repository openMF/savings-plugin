/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.domain;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "m_client_kyc_face_match")
public class KycFaceMatch {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @JsonBackReference
  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "kyc_decision_id", nullable = false)
  private KycDecision kycDecision;

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

  protected KycFaceMatch() {}

  public static KycFaceMatch create(
      final String nodeId,
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

  public void setKycDecision(final KycDecision kycDecision) {
    this.kycDecision = kycDecision;
  }

  public Long getId() {
    return id;
  }

  public Long getKycDecisionId() {
    return kycDecision != null ? kycDecision.getId() : null;
  }

  public BigDecimal getMatchScore() {
    return matchScore;
  }

  public String getMatchStatus() {
    return matchStatus;
  }

  public String getSourceImageUrl() {
    return sourceImageUrl;
  }

  public String getTargetImageUrl() {
    return targetImageUrl;
  }
}
