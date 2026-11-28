/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.fineract.creditapplication.data.CreditOriginationBoardData;
import org.apache.fineract.creditapplication.data.CreditOriginationScoreComponent;
import org.apache.fineract.creditapplication.data.CreditOriginationScoreComponentEventCommand;
import org.apache.fineract.creditapplication.data.CreditOriginationStageCode;
import org.apache.fineract.creditapplication.data.CreditOriginationStageData;
import org.apache.fineract.creditapplication.data.CreditOriginationStageEventCommand;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.loanaccount.exception.LoanNotFoundException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CreditOriginationBoardIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  private static final OffsetDateTime EVIDENCE_ON = OffsetDateTime.parse("2026-01-04T10:00:00Z");
  private static final OffsetDateTime COMPLETED_ON = OffsetDateTime.parse("2026-01-05T10:00:00Z");

  private static DriverManagerDataSource dataSource;
  private static JdbcTemplate jdbc;
  private CreditOriginationBoardReadPlatformService boardService;
  private CreditOriginationStageEventService eventService;

  @BeforeAll
  static void createSchema() {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    CreditOriginationTestSchema.create(jdbc);
  }

  @BeforeEach
  void setUp() {
    CreditOriginationTestSchema.truncate(jdbc);
    CreditOriginationTestSchema.businessDate(LocalDate.of(2026, 1, 20));
    jdbc.update("INSERT INTO m_office VALUES (1, '.1.'), (2, '.2.')");
    jdbc.update("INSERT INTO m_appuser VALUES (5, 'reviewer')");
    jdbc.update("INSERT INTO m_staff VALUES (7, 'Ana Officer')");
    jdbc.update("INSERT INTO m_client VALUES (10, 1, NULL, 300, DATE '2026-01-01', 'Acme')");
    jdbc.update("INSERT INTO m_client VALUES (20, 2, NULL, 300, DATE '2026-01-01', 'Hidden')");
    jdbc.update(
        "INSERT INTO m_loan VALUES (100, 10, 1, 100, 'USD', NULL, NULL, NULL, NULL, NULL, 7)");
    jdbc.update(
        "INSERT INTO m_loan VALUES (200, 20, 1, 100, 'USD', NULL, NULL, NULL, NULL, NULL, NULL)");
    jdbc.update(
        "INSERT INTO m_prospect_registration VALUES"
            + " (30, 10, 'PENDING', TIMESTAMP '2026-01-01 00:00:00',"
            + " TIMESTAMP '2026-01-01 00:00:00')");

    final PlatformSecurityContext context = CreditOriginationTestSchema.userIn(".1.");
    final NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
    boardService = new CreditOriginationBoardReadPlatformServiceImpl(named, context);
    eventService = new CreditOriginationStageEventServiceImpl(named, context);
  }

  @Test
  void newApplicationStartsAtOnboardingAndNeverLeaksAnotherOffice() {
    final CreditOriginationBoardData board = boardService.retrieve(100L);

    assertEquals(9, board.stages().size());
    assertEquals(
        Arrays.stream(CreditOriginationStageCode.values()).map(Enum::name).toList(),
        board.stages().stream().map(CreditOriginationStageData::code).toList());
    assertEquals("ONBOARDING", board.currentStage());
    assertEquals("CURRENT", board.stages().getFirst().status());
    assertEquals(30L, board.prospectId());
    assertEquals(1L, board.officeId());
    assertEquals(7L, board.responsibleStaffId());
    assertEquals("Ana Officer", board.responsibleStaffName());
    assertEquals("STALLED", board.currentStageAging().status());
    assertEquals(19L, board.currentStageAging().daysInStage());
    final CreditOriginationStageData scoring = board.stages().get(2);
    assertEquals(List.of("SAT", "CREDIT_BUREAU", "SYNTAGE"), scoring.pendingRequirements());
    assertTrue(scoring.scoreComponents().stream().allMatch(c -> "UNAVAILABLE".equals(c.status())));
    assertNull(scoring.completedOn());
    assertThrows(LoanNotFoundException.class, () -> boardService.retrieve(200L));
    assertThrows(LoanNotFoundException.class, () -> boardService.retrieve(999L));
  }

  @Test
  void automaticAndExplicitEvidenceBuildsTheCompleteLifecycle() {
    jdbc.update(
        "UPDATE m_prospect_registration SET registration_status = 'COMPLETED',"
            + " last_modified_on_utc = TIMESTAMP '2026-01-02 09:00:00' WHERE id = 30");
    jdbc.update(
        "INSERT INTO m_client_kyc_verification VALUES"
            + " (40, 10, 'Approved', 1767348000000, 1767348000000, 1767348000000)");
    jdbc.update("INSERT INTO m_client_kyc_decision VALUES (41, 40, 'Approved')");
    publishMandatoryEvidence();
    record(CreditOriginationStageCode.PARAMETRIC_SCORE, "score-1");
    record(CreditOriginationStageCode.FILE_INTEGRATION, "file-1");
    record(CreditOriginationStageCode.CREDIT_ANALYSIS, "analysis-1");

    CreditOriginationBoardData board = boardService.retrieve(100L);
    assertEquals("APPROVAL", board.currentStage());
    assertEquals(5, completedCount(board));
    assertEquals(List.of(), board.stages().get(2).pendingRequirements());
    assertEquals(COMPLETED_ON, board.currentStageAging().enteredOn());
    assertEquals(15L, board.currentStageAging().daysInStage());

    jdbc.update(
        "UPDATE m_loan SET loan_status_id = 200, approvedon_date = DATE '2026-01-06',"
            + " approvedon_userid = 5 WHERE id = 100");
    record(CreditOriginationStageCode.LEGAL_INSTRUMENTATION, "legal-1");
    board = boardService.retrieve(100L);
    assertEquals("DISBURSEMENT", board.currentStage());

    jdbc.update(
        "UPDATE m_loan SET loan_status_id = 300, disbursedon_date = DATE '2026-01-08',"
            + " disbursedon_userid = 5 WHERE id = 100");
    jdbc.update(
        "INSERT INTO m_loan_transaction VALUES" + " (50, 100, 2, DATE '2026-02-01', 25.00, false)");
    board = boardService.retrieve(100L);
    assertEquals("RECOVERY", board.currentStage());
    assertEquals("CURRENT", board.stages().get(8).status());
    assertEquals(50L, board.stages().get(8).details().transactionId());
    assertEquals("NOT_APPLICABLE", board.currentStageAging().status());

    jdbc.update(
        "UPDATE m_loan SET loan_status_id = 600, closedon_date = DATE '2026-03-01'"
            + " WHERE id = 100");
    board = boardService.retrieve(100L);
    assertNull(board.currentStage());
    assertEquals(9, completedCount(board));
    assertEquals(OffsetDateTime.parse("2026-03-01T00:00:00Z"), board.stages().get(8).completedOn());
  }

  @Test
  void explicitEventsAreIdempotentAndRejectClientLoanMismatch() {
    assertTrue(record(CreditOriginationStageCode.COMPLIANCE, "same-key"));
    assertFalse(record(CreditOriginationStageCode.COMPLIANCE, "same-key"));
    assertEquals(1, eventCount());

    final CreditOriginationStageEventCommand mismatched =
        command(CreditOriginationStageCode.COMPLIANCE, "bad", 20L, COMPLETED_ON);
    assertThrows(RuntimeException.class, () -> eventService.recordStageEvent(mismatched));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            eventService.recordScoreComponentEvent(
                component(CreditOriginationScoreComponent.SAT, "COMPLETED", "sat-x", 20L)));
  }

  @Test
  void latestTerminalEventIsPreservedWithoutDuplicateCurrentStages() {
    jdbc.update(
        "UPDATE m_prospect_registration SET registration_status = 'COMPLETED' WHERE id = 30");
    record(CreditOriginationStageCode.COMPLIANCE, "compliance-1");
    publishMandatoryEvidence();
    record(CreditOriginationStageCode.PARAMETRIC_SCORE, "score-1");
    final CreditOriginationStageEventCommand blocked =
        new CreditOriginationStageEventCommand(
            100L,
            10L,
            CreditOriginationStageCode.FILE_INTEGRATION,
            "BLOCKED",
            COMPLETED_ON,
            5L,
            "DOCUMENT_SERVICE",
            "missing-id",
            "blocked-1",
            "Identity document is missing");
    assertTrue(eventService.recordStageEvent(blocked));

    final CreditOriginationBoardData board = boardService.retrieve(100L);
    assertEquals("BLOCKED", board.stages().get(3).status());
    assertEquals("FILE_INTEGRATION", board.currentStage());
    assertEquals("Identity document is missing", board.stages().get(3).details().reason());
    assertEquals(0, board.stages().stream().filter(s -> "CURRENT".equals(s.status())).count());
  }

  @ParameterizedTest
  @EnumSource(
      value = CreditOriginationScoreComponent.class,
      names = {"SAT", "CREDIT_BUREAU", "SYNTAGE"})
  void eachMandatoryComponentIsRequiredToCompleteScoring(
      final CreditOriginationScoreComponent missing) {
    for (CreditOriginationScoreComponent component : CreditOriginationScoreComponent.values()) {
      if (component != missing) {
        publish(component, "COMPLETED", component.name());
      }
    }

    final GeneralPlatformDomainRuleException error =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> record(CreditOriginationStageCode.PARAMETRIC_SCORE, "score-1"));

    assertEquals(
        "error.msg.credit.origination.parametric.score.requirements.missing",
        error.getGlobalisationMessageCode());
    assertEquals(List.of(missing.name()), Arrays.asList(error.getDefaultUserMessageArgs()));
    assertEquals(0, stageEventCount());
  }

  @Test
  void optionalRegistriesNeverBlockScoringCompletion() {
    publishMandatoryEvidence();
    publish(CreditOriginationScoreComponent.RUG, "FAILED", "rug-1");

    assertTrue(record(CreditOriginationStageCode.PARAMETRIC_SCORE, "score-1"));

    final CreditOriginationStageData scoring = boardService.retrieve(100L).stages().get(2);
    assertEquals("COMPLETED", scoring.status());
    assertEquals(COMPLETED_ON, scoring.completedOn());
    assertEquals(
        List.of("COMPLETED", "COMPLETED", "COMPLETED", "FAILED", "UNAVAILABLE"),
        scoring.scoreComponents().stream().map(c -> c.status()).toList());
  }

  @Test
  void failedOrLaterDatedEvidenceDoesNotSatisfyTheGate() {
    publishMandatoryEvidence();
    eventService.recordScoreComponentEvent(
        componentAt(
            CreditOriginationScoreComponent.SAT, "FAILED", "sat-2", EVIDENCE_ON.plusHours(1)));

    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> record(CreditOriginationStageCode.PARAMETRIC_SCORE, "score-1"));

    eventService.recordScoreComponentEvent(
        componentAt(
            CreditOriginationScoreComponent.SAT, "COMPLETED", "sat-3", COMPLETED_ON.plusDays(1)));
    // Evidence dated after the completion it would have to support is ignored.
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> record(CreditOriginationStageCode.PARAMETRIC_SCORE, "score-2"));
    assertEquals(0, stageEventCount());
  }

  @Test
  void duplicateAndOutOfOrderCompletionsAreRejected() {
    final GeneralPlatformDomainRuleException early =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> record(CreditOriginationStageCode.FILE_INTEGRATION, "file-early"));
    assertEquals(
        "error.msg.credit.origination.parametric.score.not.completed",
        early.getGlobalisationMessageCode());
    assertTrue(
        eventService.recordStageEvent(
            new CreditOriginationStageEventCommand(
                100L,
                10L,
                CreditOriginationStageCode.FILE_INTEGRATION,
                "IN_PROGRESS",
                COMPLETED_ON,
                5L,
                "DOCUMENT_SERVICE",
                null,
                "file-progress",
                null)));

    publishMandatoryEvidence();
    assertTrue(record(CreditOriginationStageCode.PARAMETRIC_SCORE, "score-1"));
    assertFalse(record(CreditOriginationStageCode.PARAMETRIC_SCORE, "score-1"));
    final GeneralPlatformDomainRuleException duplicate =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> record(CreditOriginationStageCode.PARAMETRIC_SCORE, "score-2"));
    assertEquals(
        "error.msg.credit.origination.parametric.score.already.completed",
        duplicate.getGlobalisationMessageCode());
    assertTrue(record(CreditOriginationStageCode.FILE_INTEGRATION, "file-1"));
  }

  @Test
  void concurrentEvidenceAndCompletionAreSerializedPerApplication() throws Exception {
    publish(CreditOriginationScoreComponent.SAT, "COMPLETED", "sat-1");
    publish(CreditOriginationScoreComponent.CREDIT_BUREAU, "COMPLETED", "buro-1");
    final TransactionTemplate transactions =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    final CountDownLatch evidenceWritten = new CountDownLatch(1);
    final CountDownLatch releaseEvidence = new CountDownLatch(1);
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final Future<Boolean> evidence =
          executor.submit(
              () ->
                  transactions.execute(
                      status -> {
                        final boolean recorded =
                            eventService.recordScoreComponentEvent(
                                component(
                                    CreditOriginationScoreComponent.SYNTAGE,
                                    "COMPLETED",
                                    "syntage-1",
                                    10L));
                        evidenceWritten.countDown();
                        await(releaseEvidence);
                        return recorded;
                      }));
      assertTrue(evidenceWritten.await(10, TimeUnit.SECONDS));
      final Future<Boolean> completion =
          executor.submit(
              () ->
                  transactions.execute(
                      status -> record(CreditOriginationStageCode.PARAMETRIC_SCORE, "score-1")));

      // The completion waits on the application lock instead of reading half-written evidence.
      assertThrows(TimeoutException.class, () -> completion.get(500, TimeUnit.MILLISECONDS));
      releaseEvidence.countDown();

      assertTrue(evidence.get(10, TimeUnit.SECONDS));
      assertTrue(completion.get(10, TimeUnit.SECONDS));
      assertEquals("COMPLETED", boardService.retrieve(100L).stages().get(2).status());
    } finally {
      releaseEvidence.countDown();
      executor.shutdownNow();
    }
  }

  private void publishMandatoryEvidence() {
    publish(CreditOriginationScoreComponent.SAT, "COMPLETED", "sat-1");
    publish(CreditOriginationScoreComponent.CREDIT_BUREAU, "COMPLETED", "buro-1");
    publish(CreditOriginationScoreComponent.SYNTAGE, "COMPLETED", "syntage-1");
  }

  private void publish(
      final CreditOriginationScoreComponent component, final String status, final String key) {
    assertTrue(eventService.recordScoreComponentEvent(component(component, status, key, 10L)));
  }

  private boolean record(final CreditOriginationStageCode stage, final String key) {
    return eventService.recordStageEvent(command(stage, key, 10L, COMPLETED_ON));
  }

  private static CreditOriginationStageEventCommand command(
      final CreditOriginationStageCode stage,
      final String key,
      final Long clientId,
      final OffsetDateTime occurredOn) {
    return new CreditOriginationStageEventCommand(
        100L,
        clientId,
        stage,
        "COMPLETED",
        occurredOn,
        5L,
        "TEST_WORKFLOW",
        stage.name(),
        key,
        null);
  }

  private static CreditOriginationScoreComponentEventCommand component(
      final CreditOriginationScoreComponent component,
      final String status,
      final String key,
      final Long clientId) {
    return new CreditOriginationScoreComponentEventCommand(
        100L, clientId, component, status, EVIDENCE_ON, 5L, "SCORING_INTEGRATION", key, key, null);
  }

  private static CreditOriginationScoreComponentEventCommand componentAt(
      final CreditOriginationScoreComponent component,
      final String status,
      final String key,
      final OffsetDateTime occurredOn) {
    return new CreditOriginationScoreComponentEventCommand(
        100L, 10L, component, status, occurredOn, 5L, "SCORING_INTEGRATION", key, key, null);
  }

  private static void await(final CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static int eventCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM m_credit_origination_stage_event", Integer.class);
  }

  private static int stageEventCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM m_credit_origination_stage_event WHERE component_code IS NULL",
        Integer.class);
  }

  private long completedCount(final CreditOriginationBoardData board) {
    return board.stages().stream().filter(s -> "COMPLETED".equals(s.status())).count();
  }
}
