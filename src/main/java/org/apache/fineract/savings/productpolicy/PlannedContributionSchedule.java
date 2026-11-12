/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** Explicit calendar projections only. No scheduler bean or native posting dependency exists. */
final class PlannedContributionSchedule {
  static final int MAX_CONTRIBUTIONS = 1000;

  private PlannedContributionSchedule() {}

  static List<LocalDate> dates(PlannedSavingsData.Configuration c) {
    List<LocalDate> dates = new ArrayList<>();
    // Start is the first projected due date, maturity is exclusive. Each month uses the original
    // anchor, avoiding Jan 31 -> Feb 28 -> Mar 28 drift. Short-month handling is caller-selected.
    for (int index = 0; ; index++) {
      long offset = (long) index * c.frequencyInterval();
      LocalDate due;
      switch (c.frequencyUnit()) {
        case DAYS -> due = c.startDate().plusDays(offset);
        case WEEKS -> due = c.startDate().plusWeeks(offset);
        case MONTHS -> {
          YearMonth month = YearMonth.from(c.startDate()).plusMonths(offset);
          if (month.atDay(1).isAfter(c.maturityDate())) return List.copyOf(dates);
          int anchor = c.startDate().getDayOfMonth();
          if (anchor > month.lengthOfMonth()
              && c.shortMonthRule() == PlannedSavingsData.ShortMonthRule.SKIP) continue;
          due = month.atDay(Math.min(anchor, month.lengthOfMonth()));
        }
        default -> throw new IllegalStateException("Unsupported frequency.");
      }
      if (!due.isBefore(c.maturityDate())) return List.copyOf(dates);
      if (dates.size() == MAX_CONTRIBUTIONS)
        throw ProductPolicyValidation.invalid(
            "frequencyInterval", "Projection exceeds 1000 contributions.");
      dates.add(due);
    }
  }

  static String status(
      LocalDate due, LocalDate today, PlannedSavingsData.ArrangementStatus status) {
    if (status == PlannedSavingsData.ArrangementStatus.CANCELLED) return "CANCELLED_CONFIGURATION";
    if (due.isBefore(today)) return "OVERDUE_UNVERIFIED";
    return due.equals(today) ? "DUE_UNVERIFIED" : "PROJECTED_UNAPPROVED";
  }
}
