/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.apache.fineract.kyc.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "m_client_kyc_aml_hit")
public class KycAmlHit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aml_screening_id", nullable = false)
    private Long amlScreeningId;

    @Column(name = "hit_data", columnDefinition = "TEXT")
    private String hitData;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_on_utc", nullable = false)
    private OffsetDateTime createdOnUtc;

    @Column(name = "last_modified_by", nullable = false)
    private Long lastModifiedBy;

    @Column(name = "last_modified_on_utc", nullable = false)
    private OffsetDateTime lastModifiedOnUtc;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "aml_screening_id", insertable = false, updatable = false)
    private KycAmlScreening amlScreening;

    protected KycAmlHit() {}

    public static KycAmlHit create(final String hitData, final Long createdBy) {
        final KycAmlHit h = new KycAmlHit();
        h.hitData = hitData;
        h.createdBy = createdBy;
        h.lastModifiedBy = createdBy;
        final OffsetDateTime now = OffsetDateTime.now();
        h.createdOnUtc = now;
        h.lastModifiedOnUtc = now;
        return h;
    }

    void setAmlScreening(final KycAmlScreening amlScreening) {
        this.amlScreening = amlScreening;
        if (amlScreening != null) {
            this.amlScreeningId = amlScreening.getId();
        }
    }

    public Long getId() { return id; }
    public String getHitData() { return hitData; }
}