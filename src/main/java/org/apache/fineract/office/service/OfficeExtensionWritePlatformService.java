/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.service;

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
   * Creates a new address for an office.
   *
   * @param officeId the parent office identifier
   * @param jsonBody the JSON request body
   * @return the result containing the new address id
   */
  CommandProcessingResult createOfficeAddress(Long officeId, String jsonBody);
  
  /**
   * Updates an existing office address, scoped to the given office.
   *
   * @param officeId the parent office identifier (used to scope the update)
   * @param serviceId the address identifier
   * @param jsonBody the JSON request body
   * @return the result containing the updated address id
   */
  CommandProcessingResult updateOfficeAddress(Long officeId, Long addressId, String jsonBody);

  /**
   * Deletes an office address, scoped to the given office.
   *
   * @param officeId the parent office identifier (used to scope the delete)
   * @param serviceId the address identifier
   * @return the result containing the deleted address id
   */
  CommandProcessingResult deleteOfficeAddress(Long officeId, Long addressId);

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
