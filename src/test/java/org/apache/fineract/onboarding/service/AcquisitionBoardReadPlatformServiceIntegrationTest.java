/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.onboarding.data.AcquisitionBoardData;
import org.apache.fineract.onboarding.data.AcquisitionStageData;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountNotFoundException;
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

@Testcontainers(disabledWithoutDocker = true)
class AcquisitionBoardReadPlatformServiceIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  private static JdbcTemplate jdbc;
  private AcquisitionBoardReadPlatformService service;

  @BeforeAll
  static void createSchema() {
    final DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE TABLE m_office (id BIGINT PRIMARY KEY, hierarchy VARCHAR(100) NOT NULL)");
    jdbc.execute("CREATE TABLE m_appuser (id BIGINT PRIMARY KEY, username VARCHAR(100))");
    jdbc.execute(
        "CREATE TABLE m_client (id BIGINT PRIMARY KEY, office_id BIGINT NOT NULL,"
            + " transfer_to_office_id BIGINT)");
    jdbc.execute(
        "CREATE TABLE m_savings_account (id BIGINT PRIMARY KEY, client_id BIGINT,"
            + " status_enum INTEGER NOT NULL, currency_code VARCHAR(3), approvedon_date DATE,"
            + " approvedon_userid BIGINT, activatedon_date DATE, activatedon_userid BIGINT)");
    jdbc.execute(
        "CREATE TABLE m_prospect_registration (id BIGINT PRIMARY KEY, client_id BIGINT,"
            + " registration_status VARCHAR(50) NOT NULL, created_on_utc TIMESTAMP NOT NULL,"
            + " last_modified_on_utc TIMESTAMP NOT NULL, last_modified_by BIGINT)");
    jdbc.execute(
        "CREATE TABLE m_prospect_stage_event (id BIGINT PRIMARY KEY, case_id BIGINT NOT NULL,"
            + " sequence_no BIGINT NOT NULL, stage_code VARCHAR(100) NOT NULL,"
            + " new_status VARCHAR(50) NOT NULL, occurred_on_utc TIMESTAMP NOT NULL,"
            + " recorded_on_utc TIMESTAMP NOT NULL, actor_id BIGINT, source VARCHAR(100) NOT NULL,"
            + " source_reference VARCHAR(255), reason VARCHAR(500))");
    jdbc.execute(
        "CREATE TABLE m_savings_account_transaction (id BIGINT PRIMARY KEY,"
            + " savings_account_id BIGINT NOT NULL, transaction_type_enum INTEGER NOT NULL,"
            + " transaction_date DATE NOT NULL, amount DECIMAL(19,6) NOT NULL,"
            + " is_reversed BOOLEAN NOT NULL)");
  }

  @BeforeEach
  void setUp() {
    jdbc.execute(
        "TRUNCATE m_savings_account_transaction, m_prospect_stage_event,"
            + " m_prospect_registration, m_savings_account, m_client, m_appuser, m_office");
    jdbc.update("INSERT INTO m_office VALUES (?, ?)", 1L, ".");
    jdbc.update("INSERT INTO m_office VALUES (?, ?)", 2L, ".1.");
    jdbc.update("INSERT INTO m_office VALUES (?, ?)", 3L, ".1.2.");
    jdbc.update("INSERT INTO m_office VALUES (?, ?)", 9L, ".9.");
    jdbc.update("INSERT INTO m_appuser VALUES (?, ?)", 7L, "commercial.user");
    jdbc.update("INSERT INTO m_appuser VALUES (?, ?)", 8L, "compliance.user");
    jdbc.update("INSERT INTO m_client VALUES (?, ?, ?)", 10L, 2L, null);
    jdbc.update("INSERT INTO m_client VALUES (?, ?, ?)", 11L, 3L, null);
    jdbc.update("INSERT INTO m_client VALUES (?, ?, ?)", 99L, 9L, null);
    insertAccount(20L, 10L, 100, null, null);
    insertAccount(21L, 11L, 100, null, null);
    insertAccount(99L, 99L, 100, null, null);

    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final Office office = mock(Office.class);
    when(context.authenticatedUser()).thenReturn(user);
    when(user.getOffice()).thenReturn(office);
    when(office.getHierarchy()).thenReturn(".1.");
    service =
        new AcquisitionBoardReadPlatformServiceImpl(new NamedParameterJdbcTemplate(jdbc), context);
  }

  @Test
  void noAcquisitionHistoryReturnsCurrentOnboardingAndFivePendingStages() {
    final AcquisitionBoardData board = service.retrieve(10L, 20L);

    assertEquals("ONBOARDING", board.currentStage());
    assertNull(board.prospectId());
    assertEquals(6, board.stages().size());
    assertEquals(6, board.stages().stream().map(AcquisitionStageData::code).distinct().count());
    assertEquals("CURRENT", stage(board, "ONBOARDING").status());
    assertEquals(
        5, board.stages().stream().filter(stage -> "PENDING".equals(stage.status())).count());
  }

  @Test
  void partialHistoryUsesExplicitEventsAndSavingsLifecycleDatesAndActors() {
    insertProspect(30L, 10L, "COMPLETED", "2026-09-01 08:00:00", 7L);
    insertEvent(40L, 30L, 1L, "COMPLIANCE", "COMPLETED", "2026-09-02 09:30:00", 8L);
    updateAccountLifecycle(20L, 200, "2026-09-03", 7L, null, null);

    final AcquisitionBoardData board = service.retrieve(10L, 20L);

    assertEquals(30L, board.prospectId());
    assertEquals("ACTIVATION", board.currentStage());
    assertNull(stage(board, "ONBOARDING").completedOn());
    assertEquals("2026-09-02T09:30Z", stage(board, "COMPLIANCE").completedOn());
    assertEquals("compliance.user", stage(board, "COMPLIANCE").details().actorName());
    assertEquals("2026-09-03", stage(board, "APPROVAL").completedOn());
    assertEquals("commercial.user", stage(board, "APPROVAL").details().actorName());
    assertEquals("CURRENT", stage(board, "ACTIVATION").status());
  }

  @Test
  void fullyCompletedBoardUsesOnlyPostedNonReversedTransactions() {
    insertProspect(30L, 10L, "COMPLETED", "2026-09-01 08:00:00", 7L);
    insertEvent(40L, 30L, 1L, "COMPLIANCE", "COMPLETED", "2026-09-02 09:30:00", 8L);
    updateAccountLifecycle(20L, 300, "2026-09-03", 7L, "2026-09-04", 7L);
    insertTransaction(50L, 20L, 1, "2026-09-05", "125.50", false);
    insertTransaction(51L, 20L, 2, "2026-09-06", "25.00", false);
    insertTransaction(52L, 20L, 1, "2026-09-01", "999.00", true);

    final AcquisitionBoardData board = service.retrieve(10L, 20L);

    assertNull(board.currentStage());
    assertEquals(
        6, board.stages().stream().filter(stage -> "COMPLETED".equals(stage.status())).count());
    assertEquals("2026-09-05", stage(board, "DEPOSIT").completedOn());
    assertEquals(50L, stage(board, "DEPOSIT").details().transactionId());
    assertEquals("2026-09-06", stage(board, "WITHDRAWAL").completedOn());
    assertEquals("USD", stage(board, "WITHDRAWAL").details().currencyCode());
  }

  @Test
  void explicitRejectedStateIsPreservedAndLaterStagesRemainPending() {
    insertProspect(30L, 10L, "COMPLETED", "2026-09-01 08:00:00", 7L);
    insertEvent(40L, 30L, 1L, "COMPLIANCE", "REJECTED", "2026-09-02 09:30:00", 8L);

    final AcquisitionBoardData board = service.retrieve(10L, 20L);

    assertEquals("COMPLIANCE", board.currentStage());
    assertEquals("REJECTED", stage(board, "COMPLIANCE").status());
    assertEquals("PENDING", stage(board, "APPROVAL").status());
  }

  @Test
  void invalidMissingMismatchedAndOutOfHierarchyIdentifiersFail() {
    assertThrows(GeneralPlatformDomainRuleException.class, () -> service.retrieve(0L, 20L));
    assertThrows(ClientNotFoundException.class, () -> service.retrieve(777L, 20L));
    assertThrows(SavingsAccountNotFoundException.class, () -> service.retrieve(10L, 777L));
    assertThrows(GeneralPlatformDomainRuleException.class, () -> service.retrieve(10L, 21L));
    assertThrows(ClientNotFoundException.class, () -> service.retrieve(99L, 99L));
  }

  @Test
  void multipleClientProspectsAreRejectedAsAmbiguousRatherThanGuessed() {
    insertProspect(30L, 10L, "COMPLETED", "2026-09-01 08:00:00", 7L);
    insertProspect(31L, 10L, "IN_PROGRESS", "2026-09-02 08:00:00", 7L);

    assertThrows(GeneralPlatformDomainRuleException.class, () -> service.retrieve(10L, 20L));
  }

  private void insertAccount(
      final Long accountId,
      final Long clientId,
      final int status,
      final String approvedOn,
      final String activatedOn) {
    jdbc.update(
        "INSERT INTO m_savings_account"
            + " (id, client_id, status_enum, currency_code, approvedon_date, activatedon_date)"
            + " VALUES (?, ?, ?, 'USD', CAST(? AS DATE), CAST(? AS DATE))",
        accountId,
        clientId,
        status,
        approvedOn,
        activatedOn);
  }

  private void updateAccountLifecycle(
      final Long accountId,
      final int status,
      final String approvedOn,
      final Long approvedBy,
      final String activatedOn,
      final Long activatedBy) {
    jdbc.update(
        "UPDATE m_savings_account SET status_enum = ?, approvedon_date = CAST(? AS DATE),"
            + " approvedon_userid = ?, activatedon_date = CAST(? AS DATE),"
            + " activatedon_userid = ? WHERE id = ?",
        status,
        approvedOn,
        approvedBy,
        activatedOn,
        activatedBy,
        accountId);
  }

  private void insertProspect(
      final Long id,
      final Long clientId,
      final String status,
      final String modifiedOn,
      final Long actorId) {
    jdbc.update(
        "INSERT INTO m_prospect_registration"
            + " (id, client_id, registration_status, created_on_utc, last_modified_on_utc,"
            + " last_modified_by) VALUES (?, ?, ?, CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMP), ?)",
        id,
        clientId,
        status,
        modifiedOn,
        modifiedOn,
        actorId);
  }

  private void insertEvent(
      final Long id,
      final Long caseId,
      final Long sequence,
      final String stage,
      final String status,
      final String occurredOn,
      final Long actorId) {
    jdbc.update(
        "INSERT INTO m_prospect_stage_event"
            + " (id, case_id, sequence_no, stage_code, new_status, occurred_on_utc,"
            + " recorded_on_utc, actor_id, source, source_reference, reason)"
            + " VALUES (?, ?, ?, ?, ?, CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMP), ?,"
            + " 'OPERATIONS', ?, null)",
        id,
        caseId,
        sequence,
        stage,
        status,
        occurredOn,
        occurredOn,
        actorId,
        "event-" + id);
  }

  private void insertTransaction(
      final Long id,
      final Long accountId,
      final int type,
      final String date,
      final String amount,
      final boolean reversed) {
    jdbc.update(
        "INSERT INTO m_savings_account_transaction VALUES (?, ?, ?, CAST(? AS DATE), ?, ?)",
        id,
        accountId,
        type,
        date,
        amount,
        reversed);
  }

  private AcquisitionStageData stage(final AcquisitionBoardData board, final String code) {
    return board.stages().stream()
        .filter(stage -> code.equals(stage.code()))
        .findFirst()
        .orElseThrow();
  }
}
