/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import org.apache.fineract.creditapplication.data.CreditOriginationBoardData;
import org.apache.fineract.creditapplication.data.CreditOriginationStageCode;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.StageEvidence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class CreditOriginationBoardAssemblerTest {

  @Test
  void returnsAllNineStagesInBusinessOrderForANewApplication() {
    final CreditOriginationBoardData board =
        CreditOriginationBoardAssembler.assemble(11L, 22L, null, Map.of());

    assertEquals(9, board.stages().size());
    assertEquals(
        java.util.Arrays.stream(CreditOriginationStageCode.values()).map(Enum::name).toList(),
        board.stages().stream().map(stage -> stage.code()).toList());
    assertEquals("ONBOARDING", board.currentStage());
    assertEquals("CURRENT", board.stages().getFirst().status());
    assertEquals(8, board.stages().stream().filter(s -> "PENDING".equals(s.status())).count());
  }

  @Test
  void partialWorkflowHasOneCurrentAndPreservesNonlinearCompletion() {
    final EnumMap<CreditOriginationStageCode, StageEvidence> evidence =
        new EnumMap<>(CreditOriginationStageCode.class);
    evidence.put(CreditOriginationStageCode.ONBOARDING, completed("2026-01-01T00:00:00Z"));
    evidence.put(CreditOriginationStageCode.COMPLIANCE, completed("2026-01-02T00:00:00Z"));
    evidence.put(CreditOriginationStageCode.CREDIT_ANALYSIS, completed("2026-01-04T00:00:00Z"));

    final CreditOriginationBoardData board =
        CreditOriginationBoardAssembler.assemble(11L, 22L, 33L, evidence);

    assertEquals("PARAMETRIC_SCORE", board.currentStage());
    assertEquals("CURRENT", board.stages().get(2).status());
    assertEquals("COMPLETED", board.stages().get(4).status());
    assertEquals(1, board.stages().stream().filter(s -> "CURRENT".equals(s.status())).count());
  }

  @Test
  void rejectedStageIsPreservedAndStopsProgressionWithoutACurrentMarker() {
    final EnumMap<CreditOriginationStageCode, StageEvidence> evidence =
        completedThrough(CreditOriginationStageCode.CREDIT_ANALYSIS);
    evidence.put(
        CreditOriginationStageCode.APPROVAL, new StageEvidence(false, null, "REJECTED", null));

    final CreditOriginationBoardData board =
        CreditOriginationBoardAssembler.assemble(11L, 22L, null, evidence);

    assertEquals("REJECTED", board.stages().get(5).status());
    assertEquals("APPROVAL", board.currentStage());
    assertEquals(0, board.stages().stream().filter(s -> "CURRENT".equals(s.status())).count());
  }

  @Test
  void fullyCompletedWorkflowHasNoCurrentAndKeepsCompletionTimestamps() {
    final EnumMap<CreditOriginationStageCode, StageEvidence> evidence =
        completedThrough(CreditOriginationStageCode.RECOVERY);

    final CreditOriginationBoardData board =
        CreditOriginationBoardAssembler.assemble(11L, 22L, 33L, evidence);

    assertNull(board.currentStage());
    assertEquals(9, board.stages().stream().filter(s -> "COMPLETED".equals(s.status())).count());
    assertEquals(
        OffsetDateTime.parse("2026-01-01T00:00:00Z"), board.stages().getFirst().completedOn());
  }

  @Test
  void boardSerializesWithStableCodesAndAllStages() throws Exception {
    final CreditOriginationBoardData board =
        CreditOriginationBoardAssembler.assemble(11L, 22L, null, Map.of());
    final ObjectMapper mapper = new ObjectMapper();

    final JsonNode json = mapper.readTree(mapper.writeValueAsString(board));

    assertEquals(11L, json.get("creditApplicationId").asLong());
    assertEquals("ONBOARDING", json.get("currentStage").asText());
    assertEquals(9, json.get("stages").size());
    assertEquals("RECOVERY", json.get("stages").get(8).get("code").asText());
  }

  private static EnumMap<CreditOriginationStageCode, StageEvidence> completedThrough(
      final CreditOriginationStageCode last) {
    final EnumMap<CreditOriginationStageCode, StageEvidence> evidence =
        new EnumMap<>(CreditOriginationStageCode.class);
    for (CreditOriginationStageCode code : CreditOriginationStageCode.values()) {
      if (code.ordinal() > last.ordinal()) {
        break;
      }
      evidence.put(code, completed("2026-01-01T00:00:00Z"));
    }
    return evidence;
  }

  private static StageEvidence completed(final String dateTime) {
    return new StageEvidence(true, OffsetDateTime.parse(dateTime), "COMPLETED", null);
  }
}
