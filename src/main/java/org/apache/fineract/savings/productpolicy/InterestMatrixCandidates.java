/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.math.BigDecimal;

/**
 * Unapproved mathematical examples for policy review. No Spring bean, API or posting caller. Annual
 * simple amounts exclude day counts, rounding and compounding; they are NOT account interest.
 */
final class InterestMatrixCandidates {
  private InterestMatrixCandidates() {}

  enum Strategy {
    WHOLE_BALANCE,
    MARGINAL
  }

  static BigDecimal annualSimpleAmount(
      InterestMatrixData.Matrix matrix,
      int digits,
      BigDecimal maximum,
      BigDecimal balance,
      Strategy strategy) {
    InterestMatrixValidation.validate(matrix, digits, maximum);
    InterestMatrixValidation.amount(balance, digits, "balance");
    if (balance.compareTo(maximum) > 0 || strategy == null)
      throw ProductPolicyValidation.invalid(
          "candidate", "Covered balance and explicit candidate strategy required.");
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal previousUpper = BigDecimal.ZERO;
    for (var bracket : matrix.brackets()) {
      if (strategy == Strategy.WHOLE_BALANCE) {
        if (balance.compareTo(bracket.lowerBalance()) >= 0
            && (bracket.upperBalance() == null || balance.compareTo(bracket.upperBalance()) <= 0))
          return balance.multiply(bracket.annualRatePercent()).movePointLeft(2);
      } else {
        BigDecimal ceiling =
            bracket.upperBalance() == null ? balance : balance.min(bracket.upperBalance());
        BigDecimal portion = ceiling.subtract(previousUpper).max(BigDecimal.ZERO);
        total = total.add(portion.multiply(bracket.annualRatePercent()).movePointLeft(2));
        if (bracket.upperBalance() == null || balance.compareTo(bracket.upperBalance()) <= 0)
          return total;
        previousUpper = bracket.upperBalance();
      }
    }
    throw ProductPolicyValidation.invalid("balance", "No candidate coverage.");
  }
}
