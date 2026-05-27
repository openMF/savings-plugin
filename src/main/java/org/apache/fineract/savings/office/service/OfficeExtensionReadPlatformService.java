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
package org.apache.fineract.savings.office.service;

import java.util.Collection;
import org.apache.fineract.savings.office.data.OfficeGeolocationData;
import org.apache.fineract.savings.office.data.OfficeServiceData;

/** Read-only platform service for retrieving office services and geolocation data. */
public interface OfficeExtensionReadPlatformService {

  /**
   * Retrieves all services associated with the given office.
   *
   * @param officeId the office identifier
   * @return a collection of services; empty if none are configured
   */
  Collection<OfficeServiceData> retrieveOfficeServices(Long officeId);

  /**
   * Retrieves a single office service by its identifier.
   *
   * @param serviceId the service identifier
   * @return the service data
   */
  OfficeServiceData retrieveOfficeService(Long serviceId);

  /**
   * Retrieves the geolocation data for the given office.
   *
   * @param officeId the office identifier
   * @return the geolocation data, or {@code null} if none exists
   */
  OfficeGeolocationData retrieveOfficeGeolocation(Long officeId);
}
