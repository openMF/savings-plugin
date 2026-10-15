/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.fastpayment.sinpe.data;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public final class SinpeParticipantData {

    private final Long id;
    private final String code;
    private final String description;
    private final String legalId;
    private final String category;
    private final boolean active;

    public static SinpeParticipantData instance(final Long id,
                                                final String code,
                                                final String description,
                                                final String legalId,
                                                final String category,
                                                final boolean active) {
        return SinpeParticipantData.builder()
                .id(id)
                .code(code)
                .description(description)
                .legalId(legalId)
                .category(category)
                .active(active)
                .build();
    }
}