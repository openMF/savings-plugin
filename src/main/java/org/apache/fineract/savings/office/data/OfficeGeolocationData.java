/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.savings.office.data;

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
