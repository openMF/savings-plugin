/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.fastpayment.sinpe.service;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.fastpayment.sinpe.data.SinpeParticipantData;
import org.apache.fineract.fastpayment.sinpe.domain.SinpeParticipant;
import org.apache.fineract.fastpayment.sinpe.domain.SinpeParticipantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.stream.Collectors;
import org.apache.fineract.fastpayment.sinpe.exception.SinpeParticipantNotFoundException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SinpeParticipantReadPlatformServiceImpl implements SinpeParticipantReadPlatformService {

    private final SinpeParticipantRepository repository;

    @Override
    public Collection<SinpeParticipantData> retrieveAll(final String category, final boolean activeOnly) {
        return repository.findAllFiltered(category, activeOnly)
                .stream()
                .map(this::mapToData)
                .collect(Collectors.toList());
    }

    @Override
    public SinpeParticipantData retrieveByCode(final String code) {
        final SinpeParticipant participant = repository.findByCode(code)
                .orElseThrow(() -> new SinpeParticipantNotFoundException(
                        "error.msg.sinpe.participant.not.found",
                        "SINPE Participant with code " + code + " does not exist",
                        code));
        return mapToData(participant);
    }

    @Override
    public SinpeParticipantData retrieveByLegalId(final String legalId) {
        final SinpeParticipant participant = repository.findByLegalId(legalId)
                .orElseThrow(() -> new SinpeParticipantNotFoundException(
                        "error.msg.sinpe.participant.not.found.by.legalid",
                        "SINPE Participant with legal id " + legalId + " does not exist",
                        legalId));
        return mapToData(participant);
    }

    @Override
    public SinpeParticipantData retrieveOne(final Long id) {
        final SinpeParticipant participant = repository.findById(id)
                .orElseThrow(() -> new SinpeParticipantNotFoundException(
                        "error.msg.sinpe.participant.not.found",
                        "SINPE Participant with id " + id + " does not exist",
                        id));
        return mapToData(participant);
    }

    private SinpeParticipantData mapToData(final SinpeParticipant entity) {
        return SinpeParticipantData.instance(
                entity.getId(),
                entity.getCode(),
                entity.getDescription(),
                entity.getLegalId(),
                entity.getCategory(),
                entity.isActive());
    }
}