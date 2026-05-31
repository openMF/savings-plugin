/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.data;

/** Immutable data transfer object representing a service offered by an office. */
public final class OfficeServiceData {

  private final Long id;
  private final Long officeId;
  private final String serviceName;
  private final String serviceExternalId;
  private final String workingHours;

  private OfficeServiceData(
      final Long id,
      final Long officeId,
      final String serviceName,
      final String serviceExternalId,
      final String workingHours) {
    this.id = id;
    this.officeId = officeId;
    this.serviceName = serviceName;
    this.serviceExternalId = serviceExternalId;
    this.workingHours = workingHours;
  }

  /**
   * Creates a new instance.
   *
   * @param id the service identifier
   * @param officeId the parent office identifier
   * @param serviceName the human-readable service name
   * @param serviceExternalId the external identifier for the service
   * @param workingHours the working hours description
   * @return a new {@code OfficeServiceData}
   */
  public static OfficeServiceData instance(
      final Long id,
      final Long officeId,
      final String serviceName,
      final String serviceExternalId,
      final String workingHours) {
    return new OfficeServiceData(id, officeId, serviceName, serviceExternalId, workingHours);
  }

  /**
   * Returns the service identifier.
   *
   * @return the service id, never {@code null}
   */
  public Long getId() {
    return id;
  }

  /**
   * Returns the parent office identifier.
   *
   * @return the office id, never {@code null}
   */
  public Long getOfficeId() {
    return officeId;
  }

  /**
   * Returns the human-readable service name.
   *
   * @return the service name, may be {@code null}
   */
  public String getServiceName() {
    return serviceName;
  }

  /**
   * Returns the external identifier for the service.
   *
   * @return the external id, may be {@code null}
   */
  public String getServiceExternalId() {
    return serviceExternalId;
  }

  /**
   * Returns the working hours description.
   *
   * @return the working hours text, may be {@code null}
   */
  public String getWorkingHours() {
    return workingHours;
  }
}
