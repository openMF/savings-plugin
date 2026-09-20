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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import org.apache.fineract.creditapplication.data.CreditOriginationBoardData;
import org.apache.fineract.creditapplication.data.CreditOriginationStageCode;
import org.apache.fineract.creditapplication.data.CreditOriginationStageEventCommand;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.loanaccount.exception.LoanNotFoundException;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CreditOriginationBoardIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  private static JdbcTemplate jdbc;
  private CreditOriginationBoardReadPlatformService boardService;
  private CreditOriginationStageEventService eventService;

  @BeforeAll
  static void createSchema() {
    final DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE TABLE m_office (id BIGINT PRIMARY KEY, hierarchy VARCHAR(100))");
    jdbc.execute("CREATE TABLE m_appuser (id BIGINT PRIMARY KEY, username VARCHAR(100))");
    jdbc.execute(
        "CREATE TABLE m_client (id BIGINT PRIMARY KEY, office_id BIGINT,"
            + " transfer_to_office_id BIGINT, status_enum INT, activation_date DATE)");
    jdbc.execute(
        "CREATE TABLE m_loan (id BIGINT PRIMARY KEY, client_id BIGINT, loan_status_id INT,"
            + " currency_code VARCHAR(3), approvedon_date DATE, approvedon_userid BIGINT,"
            + " disbursedon_date DATE, disbursedon_userid BIGINT, closedon_date DATE)");
    jdbc.execute(
        "CREATE TABLE m_prospect_registration (id BIGINT PRIMARY KEY, client_id BIGINT,"
            + " registration_status VARCHAR(50), created_on_utc TIMESTAMP,"
            + " last_modified_on_utc TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE m_client_kyc_verification (id BIGINT PRIMARY KEY, client_id BIGINT,"
            + " kyc_status VARCHAR(50), kyc_timestamp BIGINT, created_on_utc BIGINT,"
            + " last_modified_on_utc BIGINT)");
    jdbc.execute(
        "CREATE TABLE m_client_kyc_decision (id BIGINT PRIMARY KEY,"
            + " kyc_verification_id BIGINT, decision_status VARCHAR(50))");
    jdbc.execute(
        "CREATE TABLE m_loan_transaction (id BIGINT PRIMARY KEY, loan_id BIGINT,"
            + " transaction_type_enum INT, transaction_date DATE, amount DECIMAL(19,6),"
            + " is_reversed BOOLEAN)");
    jdbc.execute(
        "CREATE TABLE m_credit_origination_stage_event (id BIGSERIAL PRIMARY KEY,"
            + " loan_id BIGINT, client_id BIGINT, stage_code VARCHAR(50),"
            + " new_status VARCHAR(30), occurred_on_utc TIMESTAMP,"
            + " recorded_on_utc TIMESTAMP, actor_id BIGINT, source VARCHAR(100),"
            + " source_reference VARCHAR(255), event_key VARCHAR(150) UNIQUE,"
            + " reason VARCHAR(500))");
  }

  @BeforeEach
  void setUp() {
    jdbc.execute(
        "TRUNCATE m_credit_origination_stage_event, m_loan_transaction,"
            + " m_client_kyc_decision, m_client_kyc_verification, m_prospect_registration,"
            + " m_loan, m_client, m_appuser, m_office RESTART IDENTITY");
    jdbc.update("INSERT INTO m_office VALUES (1, '.1.'), (2, '.2.')");
    jdbc.update("INSERT INTO m_appuser VALUES (5, 'reviewer')");
    jdbc.update("INSERT INTO m_client VALUES (10, 1, NULL, 300, DATE '2026-01-01')");
    jdbc.update("INSERT INTO m_client VALUES (20, 2, NULL, 300, DATE '2026-01-01')");
    jdbc.update("INSERT INTO m_loan VALUES (100, 10, 100, 'USD', NULL, NULL, NULL, NULL, NULL)");
    jdbc.update("INSERT INTO m_loan VALUES (200, 20, 100, 'USD', NULL, NULL, NULL, NULL, NULL)");
    jdbc.update(
        "INSERT INTO m_prospect_registration VALUES"
            + " (30, 10, 'PENDING', TIMESTAMP '2026-01-01 00:00:00',"
            + " TIMESTAMP '2026-01-01 00:00:00')");

    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final Office office = mock(Office.class);
    when(context.authenticatedUser()).thenReturn(user);
    when(user.getOffice()).thenReturn(office);
    when(office.getHierarchy()).thenReturn(".1.");
    final NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
    boardService = new CreditOriginationBoardReadPlatformServiceImpl(named, context);
    eventService = new CreditOriginationStageEventServiceImpl(named, context);
  }

  @Test
  void newApplicationStartsAtOnboardingAndNeverLeaksAnotherOffice() {
    final CreditOriginationBoardData board = boardService.retrieve(100L);

    assertEquals(9, board.stages().size());
    assertEquals("ONBOARDING", board.currentStage());
    assertEquals("CURRENT", board.stages().getFirst().status());
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
    record(CreditOriginationStageCode.PARAMETRIC_SCORE, "score-1");
    record(CreditOriginationStageCode.FILE_INTEGRATION, "file-1");
    record(CreditOriginationStageCode.CREDIT_ANALYSIS, "analysis-1");

    CreditOriginationBoardData board = boardService.retrieve(100L);
    assertEquals("APPROVAL", board.currentStage());
    assertEquals(5, completedCount(board));

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
    assertTrue(record(CreditOriginationStageCode.PARAMETRIC_SCORE, "same-key"));
    assertFalse(record(CreditOriginationStageCode.PARAMETRIC_SCORE, "same-key"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_credit_origination_stage_event", Integer.class));

    final CreditOriginationStageEventCommand mismatched =
        command(CreditOriginationStageCode.FILE_INTEGRATION, "bad", 20L);
    assertThrows(RuntimeException.class, () -> eventService.recordStageEvent(mismatched));
  }

  @Test
  void latestTerminalEventIsPreservedWithoutDuplicateCurrentStages() {
    jdbc.update(
        "UPDATE m_prospect_registration SET registration_status = 'COMPLETED' WHERE id = 30");
    record(CreditOriginationStageCode.COMPLIANCE, "compliance-1");
    record(CreditOriginationStageCode.PARAMETRIC_SCORE, "score-1");
    final CreditOriginationStageEventCommand blocked =
        new CreditOriginationStageEventCommand(
            100L,
            10L,
            CreditOriginationStageCode.FILE_INTEGRATION,
            "BLOCKED",
            OffsetDateTime.parse("2026-01-05T10:00:00Z"),
            5L,
            "DOCUMENT_SERVICE",
            "missing-id",
            "blocked-1",
            "Identity document is missing");
    assertTrue(eventService.recordStageEvent(blocked));

    final CreditOriginationBoardData board = boardService.retrieve(100L);
    assertEquals("BLOCKED", board.stages().get(3).status());
    assertEquals("FILE_INTEGRATION", board.currentStage());
    assertEquals(0, board.stages().stream().filter(s -> "CURRENT".equals(s.status())).count());
  }

  private boolean record(final CreditOriginationStageCode stage, final String key) {
    return eventService.recordStageEvent(command(stage, key, 10L));
  }

  private CreditOriginationStageEventCommand command(
      final CreditOriginationStageCode stage, final String key, final Long clientId) {
    return new CreditOriginationStageEventCommand(
        100L,
        clientId,
        stage,
        "COMPLETED",
        OffsetDateTime.parse("2026-01-05T10:00:00Z"),
        5L,
        "TEST_WORKFLOW",
        stage.name(),
        key,
        null);
  }

  private long completedCount(final CreditOriginationBoardData board) {
    return board.stages().stream().filter(s -> "COMPLETED".equals(s.status())).count();
  }
}
