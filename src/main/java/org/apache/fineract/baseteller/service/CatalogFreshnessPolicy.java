/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.service;

import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class CatalogFreshnessPolicy {

  /** Returns true at and after the freshness boundary. */
  public boolean needsUpdate(
      final Instant lastSuccessfulRefresh, final Instant now, final Duration freshness) {
    return lastSuccessfulRefresh == null
        || !now.isBefore(lastSuccessfulRefresh.plus(freshness));
  }
}
