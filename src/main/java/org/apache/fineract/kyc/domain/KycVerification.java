/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "m_client_kyc_verification")
public class KycVerification {

  /**
   * @return the createdOnUtc
   */
  public OffsetDateTime getCreatedOnUtc() {
    return createdOnUtc;
  }

  /**
   * @param createdOnUtc the createdOnUtc to set
   */
  public void setCreatedOnUtc(OffsetDateTime createdOnUtc) {
    this.createdOnUtc = createdOnUtc;
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "client_id", nullable = false)
  private Long clientId;

  @Column(name = "session_id", nullable = false, length = 255)
  private String sessionId;

  @Column(name = "workflow_id", nullable = false, length = 255)
  private String workflowId;

  @Column(name = "workflow_version")
  private Integer workflowVersion;

  @Column(name = "webhook_type", length = 100)
  private String webhookType;

  @Column(name = "kyc_status", nullable = false, length = 50)
  private String kycStatus;

  @Column(name = "kyc_timestamp")
  private Long kycTimestamp;

  @Column(name = "kyc_created_at")
  private Long kycCreatedAt;

  @Column(name = "metadata_text", columnDefinition = "TEXT")
  private String metadata;

  @Column(name = "created_by", nullable = false)
  private Long createdBy;

  @Column(name = "created_on_utc", nullable = false)
  private OffsetDateTime createdOnUtc;

  @Column(name = "last_modified_by", nullable = false)
  private Long lastModifiedBy;

  @Column(name = "last_modified_on_utc", nullable = false)
  private OffsetDateTime lastModifiedOnUtc;

  // ✅ EAGER: always load decision with verification, no weaving warning
  @OneToOne(
      mappedBy = "kycVerification",
      cascade = CascadeType.ALL,
      fetch = FetchType.EAGER,
      orphanRemoval = true,
      optional = false)
  private KycDecision decision;

  // Forward reference — serialize this
  @OneToOne(
      mappedBy = "kycVerification",
      cascade = CascadeType.ALL,
      fetch = FetchType.EAGER,
      orphanRemoval = true)
  private KycFeatureStatus featureStatus;

  public KycFeatureStatus getFeatureStatus() {
    return featureStatus;
  }

  public void setFeatureStatus(final KycFeatureStatus featureStatus) {
    this.featureStatus = featureStatus;
    if (featureStatus != null) {
      featureStatus.setKycVerification(this);
    }
  }

  protected KycVerification() {}

  public static KycVerification create(
      final Long clientId,
      final String sessionId,
      final String workflowId,
      final Integer workflowVersion,
      final String webhookType,
      final String kycStatus,
      final Long kycTimestamp,
      final Long kycCreatedAt,
      final String metadata,
      final Long createdBy) {
    final KycVerification v = new KycVerification();
    v.clientId = clientId;
    v.sessionId = sessionId;
    v.workflowId = workflowId;
    v.workflowVersion = workflowVersion;
    v.webhookType = webhookType;
    v.kycStatus = kycStatus;
    v.kycTimestamp = kycTimestamp;
    v.kycCreatedAt = kycCreatedAt;
    v.metadata = metadata;
    v.createdBy = createdBy;
    v.lastModifiedBy = createdBy;
    final OffsetDateTime now = OffsetDateTime.now();
    v.setCreatedOnUtc(now);
    v.lastModifiedOnUtc = now;
    return v;
  }

  public Long getId() {
    return id;
  }

  public Long getClientId() {
    return clientId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getWorkflowId() {
    return workflowId;
  }

  public Integer getWorkflowVersion() {
    return workflowVersion;
  }

  public String getWebhookType() {
    return webhookType;
  }

  public String getKycStatus() {
    return kycStatus;
  }

  public Long getKycTimestamp() {
    return kycTimestamp;
  }

  public Long getKycCreatedAt() {
    return kycCreatedAt;
  }

  public String getMetadata() {
    return metadata;
  }

  public KycDecision getDecision() {
    return decision;
  }

  public void setDecision(final KycDecision decision) {
    this.decision = decision;
    if (decision != null) {
      decision.setKycVerification(this);
    }
  }
}
