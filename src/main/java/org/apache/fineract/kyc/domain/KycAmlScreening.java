/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.apache.fineract.kyc.domain;

import jakarta.persistence.*;
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

    @Column(name = "kyc_decision_id", nullable = false)
    private Long kycDecisionId;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kyc_decision_id", insertable = false, updatable = false)
    private KycDecision kycDecision;

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

    void setKycDecision(final KycDecision kycDecision) {
        this.kycDecision = kycDecision;
        if (kycDecision != null) {
            this.kycDecisionId = kycDecision.getId();
        }
    }

    public Long getId() { return id; }
    public String getScreeningStatus() { return screeningStatus; }
    public Integer getTotalHits() { return totalHits; }
    public List<KycAmlHit> getHits() { return hits; }
}