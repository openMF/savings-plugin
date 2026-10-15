/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.fastpayment.sinpe.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;

public class SinpeParticipantNotFoundException extends AbstractPlatformResourceNotFoundException {

    public SinpeParticipantNotFoundException(final String code) {
        super("error.msg.sinpe.participant.not.found",
                "SINPE Participant with code " + code + " does not exist", code);
    }

    public SinpeParticipantNotFoundException(final Long id) {
        super("error.msg.sinpe.participant.not.found",
                "SINPE Participant with id " + id + " does not exist", id);
    }

    public SinpeParticipantNotFoundException(final String key, final String message, final Object... args) {
        super(key, message, args);
    }
}