/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BccrExchangeRateRepository extends JpaRepository<BccrExchangeRate, Long> {

  Optional<BccrExchangeRate> findByRateDate(LocalDate rateDate);

  Optional<BccrExchangeRate> findByLatestTrue();

  List<BccrExchangeRate> findByRateDateBetweenOrderByRateDateDesc(LocalDate from, LocalDate to);

  @Query(
      "SELECT r FROM BccrExchangeRate r WHERE r.rateDate = (SELECT MAX(r2.rateDate) FROM BccrExchangeRate r2)")
  Optional<BccrExchangeRate> findLatestRate();

  @Modifying
  @Query("UPDATE BccrExchangeRate r SET r.latest = false WHERE r.latest = true")
  void resetLatestFlags();

  boolean existsByRateDate(LocalDate rateDate);

  @Query("SELECT COUNT(r) FROM BccrExchangeRate r WHERE r.rateDate BETWEEN :from AND :to")
  long countRatesBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
