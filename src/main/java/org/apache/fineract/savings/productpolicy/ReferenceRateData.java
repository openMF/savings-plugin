/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Administrative metadata and nominal percentage previews; none authorizes financial posting. */
public final class ReferenceRateData {
  private ReferenceRateData() {}

  public enum ReferenceType {
    CETES
  }

  public enum FormulaMode {
    ADDITIVE_SPREAD,
    MULTIPLICATIVE_FACTOR
  }

  public enum ReviewFrequency {
    DAILY,
    END_OF_MONTH
  }

  public enum Status {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED
  }

  public record Formula(
      String referenceId,
      ReferenceType referenceType,
      String tenor,
      FormulaMode formulaMode,
      BigDecimal spreadPercentagePoints,
      BigDecimal multiplier,
      ReviewFrequency reviewFrequency) {}

  public record Definition(
      String referenceId,
      ReferenceType referenceType,
      String tenor,
      String sourceDescription,
      long revision,
      long makerId,
      Instant createdAt) {}

  public record Capture(
      long expectedRevision,
      String tenor,
      BigDecimal annualRatePercent,
      LocalDate publicationDate,
      LocalDate effectiveDate,
      String sourceReference,
      String note) {}

  public record Action(long expectedRevision, String reason) {}

  public record Fixing(
      String referenceId,
      String tenor,
      long version,
      long revision,
      Status status,
      BigDecimal annualRatePercent,
      LocalDate publicationDate,
      LocalDate effectiveDate,
      String sourceReference,
      String note,
      Long baseVersion,
      long makerId,
      Long submitterId,
      Long checkerId,
      Instant capturedAt,
      Instant submittedAt,
      Instant decidedAt,
      String reason) {}

  public record Audit(
      long id,
      String referenceId,
      long version,
      String action,
      long actorId,
      Instant recordedAt,
      String previousState,
      String newState,
      String reason) {}

  public record Configuration(
      long savingsProductId,
      Long revision,
      String status,
      Formula formula,
      boolean financialOperational,
      List<String> blockers) {}

  public record Calculation(
      long savingsProductId,
      long policyVersion,
      LocalDate policyEffectiveDate,
      String referenceId,
      ReferenceType referenceType,
      String tenor,
      long benchmarkVersion,
      LocalDate publicationDate,
      LocalDate benchmarkEffectiveDate,
      LocalDate businessDate,
      BigDecimal benchmarkAnnualRatePercent,
      FormulaMode formulaMode,
      BigDecimal spreadPercentagePoints,
      BigDecimal multiplier,
      ReviewFrequency reviewFrequency,
      BigDecimal calculatedAnnualRatePercent,
      String calculationStatus,
      boolean financialOperational,
      List<String> blockers) {}

  public record Template(
      List<ReferenceType> referenceTypes,
      List<FormulaMode> formulaModes,
      List<ReviewFrequency> reviewFrequencies,
      String fixingSelection,
      boolean reviewSchedulingOperational,
      boolean financialOperational,
      List<String> blockers) {}

  public static List<String> blockers() {
    return List.of(
        "UNAPPROVED_TENOR_AND_AUTHORITATIVE_SOURCE_POLICY",
        "UNAPPROVED_OBSERVATION_EFFECTIVE_DATE_AND_HOLIDAY_RULES",
        "UNAPPROVED_PRECISION_ROUNDING_AND_RATE_BOUNDARIES",
        "UNAPPROVED_REFIXING_STALENESS_AND_CARRY_FORWARD_RULES",
        "FINERACT_POSTING_INTEGRATION_UNVERIFIED",
        "RATE_CHANGE_NOTIFICATION_NOT_IMPLEMENTED");
  }
}
