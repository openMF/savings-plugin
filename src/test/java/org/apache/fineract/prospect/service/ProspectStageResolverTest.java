/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.prospect.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.fineract.prospect.data.ProspectStageSummary;
import org.junit.jupiter.api.Test;

class ProspectStageResolverTest {

  @Test
  void derivesStoppedStageOnlyWhenLatestEventIsNotCompleted() {
    final ProspectStageSummary summary =
        ProspectStageResolver.resolve("KYC_LEVEL_1", "IN_PROGRESS", "COMMERCIAL_REGISTRATION");

    assertEquals("KYC_LEVEL_1", summary.currentStage());
    assertEquals("COMMERCIAL_REGISTRATION", summary.lastCompletedStage());
    assertEquals("KYC_LEVEL_1", summary.stoppedAtStage());
  }

  @Test
  void doesNotInventNextStoppedStageAfterCompletion() {
    final ProspectStageSummary summary =
        ProspectStageResolver.resolve("KYC_LEVEL_1", "COMPLETED", "KYC_LEVEL_1");

    assertEquals("KYC_LEVEL_1", summary.currentStage());
    assertEquals("KYC_LEVEL_1", summary.lastCompletedStage());
    assertEquals("UNKNOWN", summary.stoppedAtStage());
  }

  @Test
  void unsupportedNullStateReturnsUnknowns() {
    final ProspectStageSummary summary = ProspectStageResolver.resolve(null, null, null);

    assertEquals("UNKNOWN", summary.currentStage());
    assertEquals("UNKNOWN", summary.lastCompletedStage());
    assertEquals("UNKNOWN", summary.stoppedAtStage());
  }
}
