/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class KycVerificationSummaryData {

  private Long id;
  private Long clientId;
  private String sessionId;
  private String workflowId;
  private Integer workflowVersion;
  private String webhookType;
  private String kycStatus;
  private Long kycTimestamp;
  private String decisionStatus;
  private OffsetDateTime decisionCreatedAt;
  private OffsetDateTime createdOnUtc;

  public KycVerificationSummaryData() {}

  public KycVerificationSummaryData(
      final Long id,
      final Long clientId,
      final String sessionId,
      final String workflowId,
      final Integer workflowVersion,
      final String webhookType,
      final String kycStatus,
      final Long kycTimestamp,
      final String decisionStatus,
      final OffsetDateTime decisionCreatedAt,
      final OffsetDateTime createdOnUtc) {
    this.id = id;
    this.clientId = clientId;
    this.sessionId = sessionId;
    this.workflowId = workflowId;
    this.workflowVersion = workflowVersion;
    this.webhookType = webhookType;
    this.kycStatus = kycStatus;
    this.kycTimestamp = kycTimestamp;
    this.decisionStatus = decisionStatus;
    this.decisionCreatedAt = decisionCreatedAt;
    this.createdOnUtc = createdOnUtc;
  }

  // Getters
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

  public String getDecisionStatus() {
    return decisionStatus;
  }

  public OffsetDateTime getDecisionCreatedAt() {
    return decisionCreatedAt;
  }

  public OffsetDateTime getCreatedOnUtc() {
    return createdOnUtc;
  }
}
