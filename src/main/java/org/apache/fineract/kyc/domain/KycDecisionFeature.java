/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "m_client_kyc_decision_feature")
public class KycDecisionFeature {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // ✅ @ManyToOne OWNS the FK — no separate Long kycDecisionId field
  @JsonIgnore
  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "kyc_decision_id", nullable = false)
  private KycDecision kycDecision;

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

  public void setKycDecision(final KycDecision kycDecision) {
    this.kycDecision = kycDecision;
  }

  public Long getId() {
    return id;
  }

  public Long getKycDecisionId() {
    return kycDecision != null ? kycDecision.getId() : null;
  }

  public String getFeatureName() {
    return featureName;
  }
}
