/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.data;

import java.math.BigDecimal;

/** Immutable data transfer object representing an office's geographic coordinates. */
public final class OfficeGeolocationData {

  private final Long id;
  private final Long officeId;
  private final BigDecimal latitude;
  private final BigDecimal longitude;

  private OfficeGeolocationData(
      final Long id, final Long officeId, final BigDecimal latitude, final BigDecimal longitude) {
    this.id = id;
    this.officeId = officeId;
    this.latitude = latitude;
    this.longitude = longitude;
  }

  /**
   * Creates a new instance.
   *
   * @param id the geolocation record identifier
   * @param officeId the parent office identifier
   * @param latitude the latitude in decimal degrees (-90 to 90)
   * @param longitude the longitude in decimal degrees (-180 to 180)
   * @return a new {@code OfficeGeolocationData}
   */
  public static OfficeGeolocationData instance(
      final Long id, final Long officeId, final BigDecimal latitude, final BigDecimal longitude) {
    return new OfficeGeolocationData(id, officeId, latitude, longitude);
  }

  /**
   * Returns the geolocation record identifier.
   *
   * @return the record id, never {@code null}
   */
  public Long getId() {
    return id;
  }

  /**
   * Returns the associated office identifier.
   *
   * @return the office id, never {@code null}
   */
  public Long getOfficeId() {
    return officeId;
  }

  /**
   * Returns the latitude in decimal degrees.
   *
   * @return the latitude value (-90 to 90), never {@code null}
   */
  public BigDecimal getLatitude() {
    return latitude;
  }

  /**
   * Returns the longitude in decimal degrees.
   *
   * @return the longitude value (-180 to 180), never {@code null}
   */
  public BigDecimal getLongitude() {
    return longitude;
  }
}
