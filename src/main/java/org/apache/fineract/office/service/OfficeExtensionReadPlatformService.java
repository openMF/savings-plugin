/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.service;

import java.util.Collection;
import org.apache.fineract.office.data.OfficeGeolocationData;
import org.apache.fineract.office.data.OfficeServiceData;

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
