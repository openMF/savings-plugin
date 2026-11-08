/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * ILLUSTRATIVE CALCULATION — NOT APPROVED PRODUCTION POLICY. Exact arithmetic over caller-provided,
 * complete daily observations. This class is deliberately not a Spring bean and is never invoked by
 * the assessment job or an API. It does not retrieve history, assign a ledger basis, or authorize a
 * fee.
 */
public final class MonthlyAverageBalanceCalculator {
  public record Observation(LocalDate date, BigDecimal balance) {}

  public record Average(BigDecimal sum, int eligibleDays) {
    public Average {
      Objects.requireNonNull(sum);
      if (eligibleDays <= 0 || eligibleDays > 31)
        throw new IllegalArgumentException("Invalid denominator");
    }

    /** Compare the exact rational average without first rounding a recurring decimal. */
    public int compareTo(BigDecimal threshold) {
      if (threshold == null || threshold.signum() < 0)
        throw new IllegalArgumentException("Invalid threshold");
      return sum.compareTo(threshold.multiply(BigDecimal.valueOf(eligibleDays)));
    }

    /** Explicit display rounding only; never used to decide threshold applicability. */
    public BigDecimal display(int scale, RoundingMode rounding) {
      if (scale < 0 || scale > 12) throw new IllegalArgumentException("Invalid display scale");
      return sum.divide(BigDecimal.valueOf(eligibleDays), scale, Objects.requireNonNull(rounding));
    }
  }

  public Average illustrate(YearMonth month, List<Observation> observations) {
    Objects.requireNonNull(month);
    if (observations == null || observations.size() != month.lengthOfMonth())
      throw new IllegalArgumentException(
          "Complete daily history required; partial periods unsupported");
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = 0; i < observations.size(); i++) {
      var observation = observations.get(i);
      if (observation == null
          || !month.atDay(i + 1).equals(observation.date())
          || observation.balance() == null
          || observation.balance().scale() > 6
          || (long) observation.balance().precision() - observation.balance().scale() > 13)
        throw new IllegalArgumentException(
            "Ordered, unique daily balances with supported precision required");
      sum = sum.add(observation.balance()); // Negative balances are preserved in this illustration.
    }
    return new Average(sum, month.lengthOfMonth());
  }
}
