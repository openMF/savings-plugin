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

import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;

/** Write platform service for managing office services and geolocation data. */
public interface OfficeExtensionWritePlatformService {

  /**
   * Creates a new service for an office.
   *
   * @param officeId the parent office identifier
   * @param jsonBody the JSON request body
   * @return the result containing the new service id
   */
  CommandProcessingResult createOfficeService(Long officeId, String jsonBody);

  /**
   * Updates an existing office service, scoped to the given office.
   *
   * @param officeId the parent office identifier (used to scope the update)
   * @param serviceId the service identifier
   * @param jsonBody the JSON request body
   * @return the result containing the updated service id
   */
  CommandProcessingResult updateOfficeService(Long officeId, Long serviceId, String jsonBody);

  /**
   * Deletes an office service, scoped to the given office.
   *
   * @param officeId the parent office identifier (used to scope the delete)
   * @param serviceId the service identifier
   * @return the result containing the deleted service id
   */
  CommandProcessingResult deleteOfficeService(Long officeId, Long serviceId);

  /**
   * Creates or updates the geolocation for an office (1:1 relationship).
   *
   * @param officeId the office identifier
   * @param jsonBody the JSON request body
   * @return the result containing the geolocation record id
   */
  CommandProcessingResult saveOfficeGeolocation(Long officeId, String jsonBody);

  /**
   * Deletes the geolocation for an office.
   *
   * @param officeId the office identifier
   * @return the result containing the deleted record id
   */
  CommandProcessingResult deleteOfficeGeolocation(Long officeId);
}
