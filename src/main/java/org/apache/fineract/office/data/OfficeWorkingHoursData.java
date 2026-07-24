/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.data;

import java.util.Collection;

/** Immutable data transfer object representing an office's single weekly working-hours schedule. */
public final class OfficeWorkingHoursData {

  private final Long officeId;
  private final Collection<OfficeWorkingHoursDayData> days;

  private OfficeWorkingHoursData(
      final Long officeId, final Collection<OfficeWorkingHoursDayData> days) {
    this.officeId = officeId;
    this.days = days;
  }

  public static OfficeWorkingHoursData instance(
      final Long officeId, final Collection<OfficeWorkingHoursDayData> days) {
    return new OfficeWorkingHoursData(officeId, days);
  }

  public Long getOfficeId() {
    return officeId;
  }

  public Collection<OfficeWorkingHoursDayData> getDays() {
    return days;
  }
}
