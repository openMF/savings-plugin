/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.fastpayment.sinpe.service;

import java.util.Map;

public interface SinpeParticipantWritePlatformService {

    Long create(Map<String, Object> request);

    void update(String code, Map<String, Object> request);

    void deactivate(String code);
}