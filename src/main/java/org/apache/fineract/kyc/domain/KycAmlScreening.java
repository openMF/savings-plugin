/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.domain;


import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "m_client_kyc_aml_screening")
public class KycAmlScreening {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ✅ @ManyToOne OWNS the FK — no separate Long kycDecisionId field
    @JsonBackReference
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "kyc_decision_id", nullable = false)
    private KycDecision kycDecision;

    @Column(name = "node_id", length = 255)
    private String nodeId;

    @Column(name = "screening_status", length = 50)
    private String screeningStatus;

    @Column(name = "total_hits")
    private Integer totalHits = 0;

    @Column(name = "screened_nationality", length = 50)
    private String screenedNationality;

    @Column(name = "screened_full_name", length = 500)
    private String screenedFullName;

    @Column(name = "screened_document_number", length = 255)
    private String screenedDocumentNumber;

    @Column(name = "screened_date_of_birth")
    private LocalDate screenedDateOfBirth;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_on_utc", nullable = false)
    private OffsetDateTime createdOnUtc;

    @Column(name = "last_modified_by", nullable = false)
    private Long lastModifiedBy;

    @Column(name = "last_modified_on_utc", nullable = false)
    private OffsetDateTime lastModifiedOnUtc;

    @OneToMany(mappedBy = "amlScreening", cascade = CascadeType.ALL,
               fetch = FetchType.LAZY, orphanRemoval = true)
    private List<KycAmlHit> hits = new ArrayList<>();

    protected KycAmlScreening() {}

    public static KycAmlScreening create(final String nodeId,
                                         final String screeningStatus,
                                         final Integer totalHits,
                                         final String screenedNationality,
                                         final String screenedFullName,
                                         final String screenedDocumentNumber,
                                         final LocalDate screenedDateOfBirth,
                                         final Long createdBy) {
        final KycAmlScreening s = new KycAmlScreening();
        s.nodeId = nodeId;
        s.screeningStatus = screeningStatus;
        s.totalHits = totalHits;
        s.screenedNationality = screenedNationality;
        s.screenedFullName = screenedFullName;
        s.screenedDocumentNumber = screenedDocumentNumber;
        s.screenedDateOfBirth = screenedDateOfBirth;
        s.createdBy = createdBy;
        s.lastModifiedBy = createdBy;
        final OffsetDateTime now = OffsetDateTime.now();
        s.createdOnUtc = now;
        s.lastModifiedOnUtc = now;
        return s;
    }

    public void addHit(final KycAmlHit hit) {
        hits.add(hit);
        hit.setAmlScreening(this);
    }

    public void setKycDecision(final KycDecision kycDecision) {
        this.kycDecision = kycDecision;
    }

    public Long getId() { return id; }
    public Long getKycDecisionId() {
        return kycDecision != null ? kycDecision.getId() : null;
    }
    public String getScreeningStatus() { return screeningStatus; }
    public Integer getTotalHits() { return totalHits; }
    public List<KycAmlHit> getHits() { return hits; }
}