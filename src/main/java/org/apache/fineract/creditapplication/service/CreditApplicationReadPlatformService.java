/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import org.apache.fineract.creditapplication.data.CreditApplicationData;
import org.apache.fineract.creditapplication.data.CreditApplicationSearchRequest;
import org.apache.fineract.infrastructure.core.service.Page;

/** Read operations for server-side credit-application search. */
public interface CreditApplicationReadPlatformService {

  Page<CreditApplicationData> search(CreditApplicationSearchRequest request);
}
