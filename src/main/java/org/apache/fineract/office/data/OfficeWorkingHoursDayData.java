/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.data;

import java.time.LocalTime;

/** Immutable data transfer object representing one weekday in an office's weekly schedule. */
public final class OfficeWorkingHoursDayData {

  private final Long officeId;
  private final String weekday;
  private final Boolean enabled;
  private final LocalTime openingTime;
  private final LocalTime closingTime;

  private OfficeWorkingHoursDayData(
      final Long officeId,
      final String weekday,
      final Boolean enabled,
      final LocalTime openingTime,
      final LocalTime closingTime) {
    this.officeId = officeId;
    this.weekday = weekday;
    this.enabled = enabled;
    this.openingTime = openingTime;
    this.closingTime = closingTime;
  }

  public static OfficeWorkingHoursDayData instance(
      final Long officeId,
      final String weekday,
      final Boolean enabled,
      final LocalTime openingTime,
      final LocalTime closingTime) {
    return new OfficeWorkingHoursDayData(
        officeId, weekday, enabled, openingTime, closingTime);
  }

  public Long getOfficeId() {
    return officeId;
  }

  public String getWeekday() {
    return weekday;
  }

  public Boolean getEnabled() {
    return enabled;
  }

  public LocalTime getOpeningTime() {
    return openingTime;
  }

  public LocalTime getClosingTime() {
    return closingTime;
  }
}
