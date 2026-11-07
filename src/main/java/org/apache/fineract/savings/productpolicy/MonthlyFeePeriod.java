/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/** Explicit diagnostic calendar window, not an approved production financial period rule. */
final class MonthlyFeePeriod {
  private MonthlyFeePeriod() {}

  static YearMonth parse(String value) {
    if (value == null || !value.matches("[0-9]{4}-[0-9]{2}"))
      throw ProductPolicyValidation.invalid("period", "Use an explicit YYYY-MM period.");
    try {
      YearMonth period = YearMonth.parse(value);
      if (period.getYear() < 1900 || period.getYear() > 9998)
        throw new DateTimeParseException("Year out of range", value, 0);
      return period;
    } catch (DateTimeParseException e) {
      throw ProductPolicyValidation.invalid("period", "Invalid assessment month.");
    }
  }

  static YearMonth forCreation(String value, LocalDate businessDate) {
    var period = parse(value);
    if (businessDate == null)
      throw ProductPolicyValidation.invalid("businessDate", "Tenant business date required.");
    if (!period.equals(YearMonth.from(businessDate).minusMonths(1)))
      throw ProductPolicyValidation.invalid(
          "period",
          "Only the preceding completed diagnostic month is supported; financial catch-up is"
              + " unapproved.");
    return period;
  }
}
