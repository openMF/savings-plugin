/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.onboarding.data.AcquisitionBoardData;
import org.apache.fineract.onboarding.data.AcquisitionStageCode;
import org.apache.fineract.onboarding.service.AcquisitionBoardAssembler.StageEvidence;
import org.junit.jupiter.api.Test;

class AcquisitionBoardAssemblerTest {

  @Test
  void noHistoryReturnsSixUniqueStagesInBusinessOrderWithOnboardingCurrent() {
    final AcquisitionBoardData board = AcquisitionBoardAssembler.assemble(10L, 20L, null, Map.of());

    assertEquals(
        List.of("ONBOARDING", "COMPLIANCE", "APPROVAL", "ACTIVATION", "DEPOSIT", "WITHDRAWAL"),
        board.stages().stream().map(stage -> stage.code()).toList());
    assertEquals(6, board.stages().stream().map(stage -> stage.code()).distinct().count());
    assertEquals("ONBOARDING", board.currentStage());
    assertEquals("CURRENT", board.stages().getFirst().status());
    assertEquals(
        List.of("PENDING", "PENDING", "PENDING", "PENDING", "PENDING"),
        board.stages().subList(1, 6).stream().map(stage -> stage.status()).toList());
  }

  @Test
  void partialHistoryCalculatesFirstIncompleteStageWithoutErasingLaterFacts() {
    final Map<AcquisitionStageCode, StageEvidence> evidence =
        new EnumMap<>(AcquisitionStageCode.class);
    evidence.put(
        AcquisitionStageCode.ONBOARDING,
        new StageEvidence(true, "2026-09-01T08:00:00Z", "COMPLETED", null));
    evidence.put(
        AcquisitionStageCode.DEPOSIT, new StageEvidence(true, "2026-09-05", "POSTED", null));

    final AcquisitionBoardData board = AcquisitionBoardAssembler.assemble(10L, 20L, 30L, evidence);

    assertEquals("COMPLIANCE", board.currentStage());
    assertEquals("COMPLETED", board.stages().get(0).status());
    assertEquals("CURRENT", board.stages().get(1).status());
    assertEquals("PENDING", board.stages().get(2).status());
    assertEquals("COMPLETED", board.stages().get(4).status());
    assertEquals("2026-09-05", board.stages().get(4).completedOn());
  }

  @Test
  void fullyCompletedHistoryHasNoCurrentStage() {
    final Map<AcquisitionStageCode, StageEvidence> evidence =
        new EnumMap<>(AcquisitionStageCode.class);
    for (AcquisitionStageCode code : AcquisitionStageCode.values()) {
      evidence.put(code, new StageEvidence(true, "2026-09-01", "COMPLETED", null));
    }

    final AcquisitionBoardData board = AcquisitionBoardAssembler.assemble(10L, 20L, 30L, evidence);

    assertNull(board.currentStage());
    assertEquals(
        6, board.stages().stream().filter(stage -> "COMPLETED".equals(stage.status())).count());
  }

  @Test
  void terminalSourceStateIsPreserved() {
    final Map<AcquisitionStageCode, StageEvidence> evidence =
        Map.of(
            AcquisitionStageCode.ONBOARDING,
            new StageEvidence(true, "2026-09-01", "COMPLETED", null),
            AcquisitionStageCode.COMPLIANCE,
            new StageEvidence(false, null, "REJECTED", null));

    final AcquisitionBoardData board = AcquisitionBoardAssembler.assemble(10L, 20L, 30L, evidence);

    assertEquals("COMPLIANCE", board.currentStage());
    assertEquals("REJECTED", board.stages().get(1).status());
  }

  @Test
  void legacyCommercialRegistrationEventMapsToStableOnboardingCode() {
    assertEquals(
        AcquisitionStageCode.ONBOARDING,
        AcquisitionBoardReadPlatformServiceImpl.canonicalStage("COMMERCIAL_REGISTRATION"));
  }
}
