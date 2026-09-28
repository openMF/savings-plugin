/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CatalogFreshnessPolicyTest {

  private final CatalogFreshnessPolicy policy = new CatalogFreshnessPolicy();
  private final Instant lastSuccess = Instant.parse("2026-09-27T10:00:00Z");
  private final Duration freshness = Duration.ofHours(24);

  @Test
  void firstUseNeedsUpdate() {
    assertTrue(policy.needsUpdate(null, lastSuccess, freshness));
  }

  @Test
  void refreshWithinIntervalDoesNotNeedUpdate() {
    assertFalse(
        policy.needsUpdate(lastSuccess, lastSuccess.plus(freshness).minusMillis(1), freshness));
  }

  @Test
  void exactFreshnessBoundaryNeedsUpdate() {
    assertTrue(policy.needsUpdate(lastSuccess, lastSuccess.plus(freshness), freshness));
  }

  @Test
  void refreshOlderThanIntervalNeedsUpdate() {
    assertTrue(
        policy.needsUpdate(lastSuccess, lastSuccess.plus(freshness).plusMillis(1), freshness));
  }
}
