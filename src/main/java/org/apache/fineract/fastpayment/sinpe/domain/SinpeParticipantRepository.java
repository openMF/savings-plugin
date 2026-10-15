/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.fastpayment.sinpe.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SinpeParticipantRepository extends JpaRepository<SinpeParticipant, Long> {

    Optional<SinpeParticipant> findByCode(String code);

    Optional<SinpeParticipant> findByLegalId(String legalId);

    List<SinpeParticipant> findByActiveTrue();

    List<SinpeParticipant> findByCategoryAndActiveTrue(String category);

    @Query("SELECT p FROM SinpeParticipant p WHERE (:category IS NULL OR p.category = :category) AND (:activeOnly = false OR p.active = true)")
    List<SinpeParticipant> findAllFiltered(@Param("category") String category,
                                           @Param("activeOnly") boolean activeOnly);

    boolean existsByCode(String code);

    boolean existsByLegalId(String legalId);
}