/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.domain;


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
@Table(name = "m_client_kyc_aml_hit")
public class KycAmlHit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ✅ @ManyToOne OWNS the FK — no separate Long amlScreeningId field
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "aml_screening_id", nullable = false)
    private KycAmlScreening amlScreening;

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

    public void setAmlScreening(final KycAmlScreening amlScreening) {
        this.amlScreening = amlScreening;
    }

    public Long getId() { return id; }
    public Long getAmlScreeningId() {
        return amlScreening != null ? amlScreening.getId() : null;
    }
    public String getHitData() { return hitData; }
}