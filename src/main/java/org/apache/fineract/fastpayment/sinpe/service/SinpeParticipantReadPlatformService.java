/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.fastpayment.sinpe.service;

import org.apache.fineract.fastpayment.sinpe.data.SinpeParticipantData;

import java.util.Collection;

public interface SinpeParticipantReadPlatformService {

    Collection<SinpeParticipantData> retrieveAll(String category, boolean activeOnly);

    SinpeParticipantData retrieveByCode(String code);

    SinpeParticipantData retrieveByLegalId(String legalId);

    SinpeParticipantData retrieveOne(Long id);
}