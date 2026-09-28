/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.service;

import java.util.List;
import org.apache.fineract.baseteller.data.CatalogUpdateCategory;
import org.apache.fineract.baseteller.data.CatalogUpdateData;

public interface CatalogUpdatePlatformService {

  List<CatalogUpdateData> retrieveAll();

  CatalogUpdateData retrieve(CatalogUpdateCategory category);

  CatalogUpdateData synchronize(CatalogUpdateCategory category);
}
