/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.creditapplication.data.CreditOriginationAgingData;
import org.apache.fineract.creditapplication.data.CreditOriginationBoardData;
import org.apache.fineract.creditapplication.data.CreditOriginationStageCode;
import org.apache.fineract.creditapplication.data.CreditOriginationStageData;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.ApplicationContext;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.EventHistory;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.EventRow;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.StageEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class CreditOriginationBoardAssemblerTest {

  private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 1, 20);

  @Test
  void returnsAllNineStagesInBusinessOrderForANewApplication() {
    final CreditOriginationBoardData board = assemble(null, Map.of(), new EventHistory());

    assertEquals(9, board.stages().size());
    assertEquals(
        java.util.Arrays.stream(CreditOriginationStageCode.values()).map(Enum::name).toList(),
        board.stages().stream().map(stage -> stage.code()).toList());
    assertEquals("ONBOARDING", board.currentStage());
    assertEquals("CURRENT", board.stages().getFirst().status());
    assertEquals(8, board.stages().stream().filter(s -> "PENDING".equals(s.status())).count());
    assertEquals("UNKNOWN", board.currentStageAging().status());
  }

  @Test
  void partialWorkflowHasOneCurrentAndPreservesNonlinearCompletion() {
    final EnumMap<CreditOriginationStageCode, StageEvidence> evidence =
        new EnumMap<>(CreditOriginationStageCode.class);
    evidence.put(CreditOriginationStageCode.ONBOARDING, completed("2026-01-01T00:00:00Z"));
    evidence.put(CreditOriginationStageCode.COMPLIANCE, completed("2026-01-02T00:00:00Z"));
    evidence.put(CreditOriginationStageCode.CREDIT_ANALYSIS, completed("2026-01-04T00:00:00Z"));

    final CreditOriginationBoardData board = assemble(null, evidence, new EventHistory());

    assertEquals("PARAMETRIC_SCORE", board.currentStage());
    assertEquals("CURRENT", board.stages().get(2).status());
    assertEquals("COMPLETED", board.stages().get(4).status());
    assertEquals(1, board.stages().stream().filter(s -> "CURRENT".equals(s.status())).count());
    // A phase completed ahead of its prerequisites has no established entry moment.
    assertNull(board.stages().get(4).enteredOn());
    assertEquals(OffsetDateTime.parse("2026-01-02T00:00:00Z"), board.stages().get(2).enteredOn());
  }

  @Test
  void rejectedStageIsPreservedAndStopsProgressionWithoutACurrentMarker() {
    final EnumMap<CreditOriginationStageCode, StageEvidence> evidence =
        completedThrough(CreditOriginationStageCode.CREDIT_ANALYSIS);
    evidence.put(
        CreditOriginationStageCode.APPROVAL, new StageEvidence(false, null, "REJECTED", null));

    final CreditOriginationBoardData board = assemble(null, evidence, new EventHistory());

    assertEquals("REJECTED", board.stages().get(5).status());
    assertEquals("APPROVAL", board.currentStage());
    assertEquals(0, board.stages().stream().filter(s -> "CURRENT".equals(s.status())).count());
    assertEquals("NOT_APPLICABLE", board.currentStageAging().status());
  }

  @Test
  void fullyCompletedWorkflowHasNoCurrentAndKeepsCompletionTimestamps() {
    final EnumMap<CreditOriginationStageCode, StageEvidence> evidence =
        completedThrough(CreditOriginationStageCode.RECOVERY);

    final CreditOriginationBoardData board = assemble(null, evidence, new EventHistory());

    assertNull(board.currentStage());
    assertNull(board.currentStageAging());
    assertEquals(9, board.stages().stream().filter(s -> "COMPLETED".equals(s.status())).count());
    assertEquals(
        OffsetDateTime.parse("2026-01-01T00:00:00Z"), board.stages().getFirst().completedOn());
  }

  @ParameterizedTest
  @CsvSource({
    "2026-01-13T23:59:59Z, 7, ON_TIME",
    "2026-01-12T00:00:00Z, 8, STALLED",
    "2026-01-20T08:00:00Z, 0, ON_TIME"
  })
  void stalledMeansStrictlyMoreThanSevenCalendarDays(
      final String completedOn, final long days, final String status) {
    final EnumMap<CreditOriginationStageCode, StageEvidence> evidence =
        completedThrough(CreditOriginationStageCode.ONBOARDING);
    evidence.put(CreditOriginationStageCode.COMPLIANCE, completed(completedOn));

    final CreditOriginationAgingData aging =
        assemble(null, evidence, new EventHistory()).currentStageAging();

    assertEquals("PARAMETRIC_SCORE", aging.stage());
    assertEquals(days, aging.daysInStage());
    assertEquals(status, aging.status());
    assertEquals("PRIOR_STAGE_COMPLETION", aging.enteredOnSource());
    assertEquals(7, aging.thresholdDays());
    assertEquals("CALENDAR_DAYS", aging.unit());
  }

  @Test
  void missingOrFutureEntryEvidenceStaysUnknownInsteadOfOnTime() {
    final EnumMap<CreditOriginationStageCode, StageEvidence> withoutTimestamp =
        new EnumMap<>(CreditOriginationStageCode.class);
    withoutTimestamp.put(
        CreditOriginationStageCode.ONBOARDING, new StageEvidence(true, null, "COMPLETED", null));
    final CreditOriginationAgingData missing =
        assemble(null, withoutTimestamp, new EventHistory()).currentStageAging();

    final CreditOriginationAgingData future =
        assemble(OffsetDateTime.parse("2026-02-01T00:00:00Z"), Map.of(), new EventHistory())
            .currentStageAging();

    assertEquals("UNKNOWN", missing.status());
    assertNull(missing.enteredOn());
    assertNull(missing.daysInStage());
    assertEquals("UNKNOWN", future.status());
    assertNull(future.daysInStage());
  }

  @Test
  void onboardingIsEnteredAtTheEarliestRegistrationEvidence() {
    final EventHistory history = new EventHistory();
    history.accept(event("ONBOARDING", null, "IN_PROGRESS", "2026-01-11T09:00:00Z"));
    history.accept(event("ONBOARDING", null, "IN_PROGRESS", "2026-01-09T09:00:00Z"));

    final CreditOriginationAgingData aging =
        assemble(OffsetDateTime.parse("2026-01-10T00:00:00Z"), Map.of(), history)
            .currentStageAging();

    assertEquals(OffsetDateTime.parse("2026-01-09T09:00:00Z"), aging.enteredOn());
    assertEquals("m_credit_origination_stage_event", aging.enteredOnSource());
    assertEquals(11L, aging.daysInStage());
  }

  @Test
  void repeatedEventsKeepTheEntryAndAReopenedPhaseIsReEntered() {
    final EnumMap<CreditOriginationStageCode, StageEvidence> evidence =
        completedThrough(CreditOriginationStageCode.COMPLIANCE);
    final EventHistory repeated = new EventHistory();
    // Newest first, as the SQL orders them.
    repeated.accept(event("PARAMETRIC_SCORE", null, "IN_PROGRESS", "2026-01-15T00:00:00Z"));
    repeated.accept(event("PARAMETRIC_SCORE", null, "IN_PROGRESS", "2026-01-12T00:00:00Z"));
    final EventHistory reopened = new EventHistory();
    reopened.accept(event("PARAMETRIC_SCORE", null, "IN_PROGRESS", "2026-01-16T00:00:00Z"));
    reopened.accept(event("PARAMETRIC_SCORE", null, "IN_PROGRESS", "2026-01-14T00:00:00Z"));
    reopened.accept(event("PARAMETRIC_SCORE", null, "COMPLETED", "2026-01-05T00:00:00Z"));

    final CreditOriginationAgingData repeatedAging =
        assemble(null, withLatest(evidence, repeated), repeated).currentStageAging();
    final CreditOriginationAgingData reopenedAging =
        assemble(null, withLatest(evidence, reopened), reopened).currentStageAging();

    assertEquals(OffsetDateTime.parse("2026-01-01T00:00:00Z"), repeatedAging.enteredOn());
    assertEquals(19L, repeatedAging.daysInStage());
    assertEquals(OffsetDateTime.parse("2026-01-14T00:00:00Z"), reopenedAging.enteredOn());
    assertEquals("STAGE_REOPENED", reopenedAging.enteredOnSource());
    assertEquals(6L, reopenedAging.daysInStage());
  }

  @Test
  void recoveryIsServicingAndNeverClassifiedAsStalled() {
    final CreditOriginationAgingData aging =
        assemble(
                null, completedThrough(CreditOriginationStageCode.DISBURSEMENT), new EventHistory())
            .currentStageAging();

    assertEquals("RECOVERY", aging.stage());
    assertEquals(19L, aging.daysInStage());
    assertEquals("NOT_APPLICABLE", aging.status());
  }

  @Test
  void scoringPhaseListsAllComponentsAndOnlyMandatoryOnesArePending() {
    final EventHistory history = new EventHistory();
    history.accept(event("PARAMETRIC_SCORE", "SAT", "COMPLETED", "2026-01-03T00:00:00Z"));
    history.accept(event("PARAMETRIC_SCORE", "CREDIT_BUREAU", "FAILED", "2026-01-03T00:00:00Z"));
    history.accept(event("PARAMETRIC_SCORE", "CREDIT_BUREAU", "COMPLETED", "2026-01-02T00:00:00Z"));
    // Component evidence never leaks into stage-level evidence.
    history.accept(event("ONBOARDING", "SAT", "COMPLETED", "2026-01-02T00:00:00Z"));

    final CreditOriginationStageData scoring = assemble(null, Map.of(), history).stages().get(2);

    assertNull(history.latest(CreditOriginationStageCode.PARAMETRIC_SCORE));
    assertNull(history.latest(CreditOriginationStageCode.ONBOARDING));
    assertEquals(List.of("CREDIT_BUREAU", "SYNTAGE"), scoring.pendingRequirements());
    assertEquals(
        List.of("COMPLETED", "FAILED", "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE"),
        scoring.scoreComponents().stream().map(c -> c.status()).toList());
    assertEquals(
        List.of(true, true, true, false, false),
        scoring.scoreComponents().stream().map(c -> c.required()).toList());
    assertEquals("sat-ref", scoring.scoreComponents().getFirst().evidenceReference());
    assertNull(assemble(null, Map.of(), history).stages().get(3).scoreComponents());
  }

  @Test
  void boardSerializesWithStableCodesAndAllStages() throws Exception {
    final CreditOriginationBoardData board = assemble(null, Map.of(), new EventHistory());
    final ObjectMapper mapper = new ObjectMapper();

    final JsonNode json = mapper.readTree(mapper.writeValueAsString(board));

    assertEquals(11L, json.get("creditApplicationId").asLong());
    assertEquals("ONBOARDING", json.get("currentStage").asText());
    assertEquals(9, json.get("stages").size());
    assertEquals("RECOVERY", json.get("stages").get(8).get("code").asText());
    assertEquals(5, json.get("stages").get(2).get("scoreComponents").size());
    assertEquals("UNKNOWN", json.get("currentStageAging").get("status").asText());
    assertEquals("Ana Officer", json.get("responsibleStaffName").asText());
  }

  private static CreditOriginationBoardData assemble(
      final OffsetDateTime prospectCreatedOn,
      final Map<CreditOriginationStageCode, StageEvidence> evidence,
      final EventHistory history) {
    return CreditOriginationBoardAssembler.assemble(
        new ApplicationContext(11L, 22L, 33L, prospectCreatedOn, 1L, 2L, 7L, "Ana Officer"),
        evidence,
        history,
        BUSINESS_DATE);
  }

  private static Map<CreditOriginationStageCode, StageEvidence> withLatest(
      final EnumMap<CreditOriginationStageCode, StageEvidence> evidence,
      final EventHistory history) {
    final EnumMap<CreditOriginationStageCode, StageEvidence> copy = new EnumMap<>(evidence);
    copy.put(
        CreditOriginationStageCode.PARAMETRIC_SCORE,
        history.latest(CreditOriginationStageCode.PARAMETRIC_SCORE));
    return copy;
  }

  private static EventRow event(
      final String stage, final String component, final String status, final String occurredOn) {
    final OffsetDateTime occurred = OffsetDateTime.parse(occurredOn);
    return new EventRow(
        stage,
        component,
        status,
        occurred,
        occurred,
        5L,
        "reviewer",
        "SCORING_ENGINE",
        component == null ? null : component.toLowerCase(java.util.Locale.ROOT) + "-ref",
        null);
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
