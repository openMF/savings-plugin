/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.fastpayment.sinpe.service;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.fastpayment.sinpe.domain.SinpeParticipant;
import org.apache.fineract.fastpayment.sinpe.domain.SinpeParticipantRepository;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class SinpeParticipantWritePlatformServiceImpl implements SinpeParticipantWritePlatformService {

    private final SinpeParticipantRepository repository;

    @Override
    @Transactional
    public Long create(final Map<String, Object> request) {
        final String code = requiredString(request, "code");
        final String description = requiredString(request, "description");
        final String legalId = requiredString(request, "legalId");
        final String category = (String) request.get("category");

        if (repository.existsByCode(code)) {
            throw new PlatformDataIntegrityException(
                    "error.msg.sinpe.participant.duplicate.code",
                    "A SINPE Participant with code " + code + " already exists",
                    "code", code);
        }
        if (repository.existsByLegalId(legalId)) {
            throw new PlatformDataIntegrityException(
                    "error.msg.sinpe.participant.duplicate.legalid",
                    "A SINPE Participant with legal id " + legalId + " already exists",
                    "legalId", legalId);
        }

        try {
            final SinpeParticipant participant = SinpeParticipant.create(code, description, legalId, category);
            final SinpeParticipant saved = repository.saveAndFlush(participant);
            return saved.getId();
        } catch (final DataIntegrityViolationException e) {
            throw new PlatformDataIntegrityException(
                    "error.msg.sinpe.participant.unknown.data.integrity.issue",
                    "Unknown data integrity issue while creating SINPE Participant",
                    e.getMostSpecificCause().getMessage());
        }
    }

    @Override
    @Transactional
    public void update(final String code, final Map<String, Object> request) {
        final SinpeParticipant participant = repository.findByCode(code)
                .orElseThrow(() -> new PlatformResourceNotFoundException(
                        "error.msg.sinpe.participant.not.found",
                        "SINPE Participant with code " + code + " does not exist",
                        code));

        final String description = (String) request.get("description");
        final String legalId = (String) request.get("legalId");
        final String category = (String) request.get("category");
        final Boolean active = request.containsKey("active") ? (Boolean) request.get("active") : null;

        if (legalId != null && !legalId.equals(participant.getLegalId())
                && repository.existsByLegalId(legalId)) {
            throw new PlatformDataIntegrityException(
                    "error.msg.sinpe.participant.duplicate.legalid",
                    "A SINPE Participant with legal id " + legalId + " already exists",
                    "legalId", legalId);
        }

        participant.update(description, legalId, category, active);
        repository.saveAndFlush(participant);
    }

    @Override
    @Transactional
    public void deactivate(final String code) {
        final SinpeParticipant participant = repository.findByCode(code)
                .orElseThrow(() -> new PlatformResourceNotFoundException(
                        "error.msg.sinpe.participant.not.found",
                        "SINPE Participant with code " + code + " does not exist",
                        code));
        participant.deactivate();
        repository.saveAndFlush(participant);
    }

    private String requiredString(final Map<String, Object> request, final String key) {
        final Object value = request.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new PlatformDataIntegrityException(
                    "error.msg.sinpe.participant.parameter.required",
                    "Parameter " + key + " is required",
                    key);
        }
        return value.toString().trim();
    }
}