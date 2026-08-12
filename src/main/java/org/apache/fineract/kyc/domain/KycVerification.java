/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "m_client_kyc_verification")
public class KycVerification {

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
    v.createdOnUtc = now;
    v.lastModifiedOnUtc = now;
    return v;
  }

  /**
   * Updates root-level fields from a later webhook for the same sessionId
   * (e.g. status.updated: In Review → Declined / Approved).
   * Does not touch decision / featureStatus; those are rebuilt by the service.
   */
  public void updateFromWebhook(
      final String kycStatus,
      final Long kycTimestamp,
      final String webhookType,
      final String workflowId,
      final Integer workflowVersion,
      final String metadata) {

    if (kycStatus != null) {
      this.kycStatus = kycStatus;
    }
    if (kycTimestamp != null) {
      this.kycTimestamp = kycTimestamp;
    }
    if (webhookType != null) {
      this.webhookType = webhookType;
    }
    if (workflowId != null) {
      this.workflowId = workflowId;
    }
    if (workflowVersion != null) {
      this.workflowVersion = workflowVersion;
    }
    if (metadata != null) {
      this.metadata = metadata;
    }
    this.lastModifiedOnUtc = OffsetDateTime.now();
  }

  /**
   * Convenience overload when only status / timestamp / webhook type change.
   */
  public void updateFromWebhook(
      final String kycStatus, final Long kycTimestamp, final String webhookType) {
    updateFromWebhook(kycStatus, kycTimestamp, webhookType, null, null, null);
  }

  // ── getters / setters ──────────────────────────────────────────────────

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

  public void setKycStatus(final String kycStatus) {
    this.kycStatus = kycStatus;
    this.lastModifiedOnUtc = OffsetDateTime.now();
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

  public Long getCreatedBy() {
    return createdBy;
  }

  public OffsetDateTime getCreatedOnUtc() {
    return createdOnUtc;
  }

  public void setCreatedOnUtc(final OffsetDateTime createdOnUtc) {
    this.createdOnUtc = createdOnUtc;
  }

  public Long getLastModifiedBy() {
    return lastModifiedBy;
  }

  public void setLastModifiedBy(final Long lastModifiedBy) {
    this.lastModifiedBy = lastModifiedBy;
  }

  public OffsetDateTime getLastModifiedOnUtc() {
    return lastModifiedOnUtc;
  }

  public void setLastModifiedOnUtc(final OffsetDateTime lastModifiedOnUtc) {
    this.lastModifiedOnUtc = lastModifiedOnUtc;
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

  public KycFeatureStatus getFeatureStatus() {
    return featureStatus;
  }

  public void setFeatureStatus(final KycFeatureStatus featureStatus) {
    this.featureStatus = featureStatus;
    if (featureStatus != null) {
      featureStatus.setKycVerification(this);
    }
  }
}