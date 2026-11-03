/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Matrix metadata belongs to the policy aggregate, including its approval and currency snapshot.
 */
public final class InterestMatrixData {
  private InterestMatrixData() {}

  /** No business-approved executable method is currently available. */
  public enum CalculationMethod {
    UNAPPROVED
  }

  /** Inclusive monetary boundaries; null upperBalance denotes the last, unbounded bracket. */
  public record Bracket(
      BigDecimal lowerBalance, BigDecimal upperBalance, BigDecimal annualRatePercent) {}

  public record Matrix(CalculationMethod calculationMethod, List<Bracket> brackets) {
    public Matrix {
      if (brackets != null)
        brackets = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(brackets));
    }
  }

  public record WriteRequest(Long expectedRevision, Matrix matrix) {}

  public record Configuration(
      long savingsProductId,
      Long revision,
      String status,
      boolean financialOperational,
      Matrix matrix,
      List<String> blockers) {}

  /** A bracket match for review only, never an interest entitlement or posting instruction. */
  public record Selection(
      long savingsProductId,
      long policyVersion,
      LocalDate businessDate,
      String currencyCode,
      int currencyDigits,
      int bracketIndex,
      Bracket bracket,
      String calculationStatus,
      boolean financialOperational) {}

  public static List<String> blockers() {
    return List.of("SLAB_CALCULATION_METHOD_UNAPPROVED", "FINERACT_TIERED_EXECUTION_UNSUPPORTED");
  }
}
