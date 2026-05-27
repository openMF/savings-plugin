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
