/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.fastpayment.sinpe.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

import java.time.LocalDateTime;

@Entity
@Table(name = "m_sinpe_participant")
@Getter
@Setter
@NoArgsConstructor
public class SinpeParticipant extends AbstractPersistableCustom<Long> {

    @Column(name = "code", nullable = false, unique = true, length = 10)
    private String code;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "legal_id", nullable = false, length = 20)
    private String legalId;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "lastmodified_date")
    private LocalDateTime lastModifiedDate;

    public static SinpeParticipant create(final String code,
                                          final String description,
                                          final String legalId,
                                          final String category) {
        final SinpeParticipant participant = new SinpeParticipant();
        participant.code = code;
        participant.description = description;
        participant.legalId = legalId;
        participant.category = category;
        participant.active = true;
        participant.createdDate = LocalDateTime.now();
        participant.lastModifiedDate = LocalDateTime.now();
        return participant;
    }

    public void update(final String description, final String legalId, final String category, final Boolean active) {
        if (description != null) {
            this.description = description;
        }
        if (legalId != null) {
            this.legalId = legalId;
        }
        if (category != null) {
            this.category = category;
        }
        if (active != null) {
            this.active = active;
        }
        this.lastModifiedDate = LocalDateTime.now();
    }

    public void deactivate() {
        this.active = false;
        this.lastModifiedDate = LocalDateTime.now();
    }
}