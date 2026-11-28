/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.fineract.infrastructure.core.service.Page;

/**
 * Office-scoped origination dashboard. The population is every active client credit application
 * (Fineract loan pending approval, approved or active) whose current phase is not inactive. Counts
 * named *Applications count credit applications; counts named *Prospects count distinct clients,
 * each of which resolves to at most one prospect registration (its latest).
 */
public record CreditOriginationDashboardData(
    LocalDate businessDate,
    int stalledThresholdDays,
    String agingUnit,
    Long officeId,
    Long productId,
    Summary summary,
    List<StageDistribution> stages,
    Page<StalledApplication> stalledApplications) {

  /** progressRate stays null until the business defines its formula (availability UNDEFINED). */
  public record Summary(
      long activeProspects,
      long activeApplications,
      long stalledProspects,
      long stalledApplications,
      long unknownAgingApplications,
      long parametricScoreProspects,
      long parametricScoreApplications,
      BigDecimal progressRate,
      String progressRateAvailability) {}

  /** statusAvailability is DERIVED or EVENTS_ONLY (needs published stage events). */
  public record StageDistribution(
      String code,
      String displayName,
      int order,
      long prospectCount,
      long applicationCount,
      long stalledProspectCount,
      long stalledApplicationCount,
      long unknownAgingApplicationCount,
      String statusAvailability) {}

  public record StalledApplication(
      Long prospectId,
      Long creditApplicationId,
      Long clientId,
      String customerName,
      String currentStage,
      String currentStageStatus,
      OffsetDateTime enteredOn,
      long daysInStage,
      Long responsibleStaffId,
      String responsibleStaffName,
      Long officeId,
      Long productId) {}
}
