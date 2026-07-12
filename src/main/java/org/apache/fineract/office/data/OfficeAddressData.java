/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.data;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class OfficeAddressData {

  // Mapping fields (m_office_address)
  private final Long officeAddressId;
  private final Long officeId;
  private final Long addressId;
  private final Long addressTypeId;
  private final Boolean isActive;

  // Physical Address fields (m_address)
  private final String street;
  private final String addressLine1;
  private final String addressLine2;
  private final String addressLine3;
  private final String townVillage;
  private final String city;
  private final String countyDistrict;
  private final Long stateProvinceId;
  private final Long countryId;
  private final String postalCode;
  private final BigDecimal latitude;
  private final BigDecimal longitude;

  // Audit fields
  private final String createdBy;
  private final LocalDate createdOn;
  private final String updatedBy;
  private final LocalDate updatedOn;

  private OfficeAddressData(
      final Long officeAddressId,
      final Long officeId,
      final Long addressId,
      final Long addressTypeId,
      final Boolean isActive,
      final String street,
      final String addressLine1,
      final String addressLine2,
      final String addressLine3,
      final String townVillage,
      final String city,
      final String countyDistrict,
      final Long stateProvinceId,
      final Long countryId,
      final String postalCode,
      final BigDecimal latitude,
      final BigDecimal longitude,
      final String createdBy,
      final LocalDate createdOn,
      final String updatedBy,
      final LocalDate updatedOn) {

    this.officeAddressId = officeAddressId;
    this.officeId = officeId;
    this.addressId = addressId;
    this.addressTypeId = addressTypeId;
    this.isActive = isActive;

    this.street = street;
    this.addressLine1 = addressLine1;
    this.addressLine2 = addressLine2;
    this.addressLine3 = addressLine3;
    this.townVillage = townVillage;
    this.city = city;
    this.countyDistrict = countyDistrict;
    this.stateProvinceId = stateProvinceId;
    this.countryId = countryId;
    this.postalCode = postalCode;
    this.latitude = latitude;
    this.longitude = longitude;

    this.createdBy = createdBy;
    this.createdOn = createdOn;
    this.updatedBy = updatedBy;
    this.updatedOn = updatedOn;
  }

  public static OfficeAddressData instance(
      final Long officeAddressId,
      final Long officeId,
      final Long addressId,
      final Long addressTypeId,
      final Boolean isActive,
      final String street,
      final String addressLine1,
      final String addressLine2,
      final String addressLine3,
      final String townVillage,
      final String city,
      final String countyDistrict,
      final Long stateProvinceId,
      final Long countryId,
      final String postalCode,
      final BigDecimal latitude,
      final BigDecimal longitude,
      final String createdBy,
      final LocalDate createdOn,
      final String updatedBy,
      final LocalDate updatedOn) {

    return new OfficeAddressData(
        officeAddressId,
        officeId,
        addressId,
        addressTypeId,
        isActive,
        street,
        addressLine1,
        addressLine2,
        addressLine3,
        townVillage,
        city,
        countyDistrict,
        stateProvinceId,
        countryId,
        postalCode,
        latitude,
        longitude,
        createdBy,
        createdOn,
        updatedBy,
        updatedOn);
  }
}
