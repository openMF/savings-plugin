/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ConsolidatedIsrData.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Pure fail-closed calculation boundary. No approved formula or allocation exists in this release.
 */
public final class ConsolidatedIsrCalculator {
  private ConsolidatedIsrCalculator() {}

  static Preview blocked(
      long client,
      LocalDate businessDate,
      Period period,
      Instant observedAt,
      Profile profile,
      Sources sources) {
    var blockers = new LinkedHashSet<>(BLOCKERS);
    blockers.addAll(sources.reasons());
    sources.accounts().forEach(a -> blockers.addAll(a.reasons()));
    return new Preview(
        client,
        businessDate,
        period,
        observedAt,
        profile,
        sources,
        "BLOCKED_PENDING_POLICY",
        null,
        null,
        null,
        List.of(),
        List.of(),
        List.copyOf(blockers));
  }
}
