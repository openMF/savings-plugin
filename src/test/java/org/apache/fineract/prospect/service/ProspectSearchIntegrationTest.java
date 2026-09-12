/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.prospect.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.prospect.data.ProspectData;
import org.apache.fineract.prospect.data.ProspectSearchRequest;
import org.apache.fineract.prospect.validation.ProspectSearchValidator;
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
class ProspectSearchIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  private static JdbcTemplate jdbcTemplate;
  private ProspectReadPlatformService service;

  @BeforeAll
  static void createSchema() {
    final DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute(
        "CREATE TABLE m_office (id BIGINT PRIMARY KEY, hierarchy VARCHAR(100) NOT NULL)");
    jdbcTemplate.execute(
        "CREATE TABLE m_client (id BIGINT PRIMARY KEY, display_name VARCHAR(255),"
            + " office_id BIGINT NOT NULL, status_enum INTEGER)");
    jdbcTemplate.execute(
        "CREATE TABLE m_loan (id BIGINT PRIMARY KEY, client_id BIGINT,"
            + " loan_status_id INTEGER NOT NULL)");
    jdbcTemplate.execute(
        "CREATE TABLE m_prospect_registration (id BIGINT PRIMARY KEY,"
            + " external_ref VARCHAR(100), office_id BIGINT NOT NULL, client_id BIGINT,"
            + " display_name VARCHAR(255), registration_status VARCHAR(50) NOT NULL,"
            + " assigned_user_id BIGINT, submitted_on_utc TIMESTAMP, version INTEGER NOT NULL,"
            + " created_by BIGINT, created_on_utc TIMESTAMP NOT NULL, last_modified_by BIGINT,"
            + " last_modified_on_utc TIMESTAMP NOT NULL)");
    jdbcTemplate.execute(
        "CREATE TABLE m_prospect_stage_event (id BIGINT PRIMARY KEY, case_id BIGINT NOT NULL,"
            + " sequence_no BIGINT NOT NULL, stage_code VARCHAR(100) NOT NULL,"
            + " previous_status VARCHAR(50), new_status VARCHAR(50) NOT NULL,"
            + " occurred_on_utc TIMESTAMP NOT NULL, recorded_on_utc TIMESTAMP NOT NULL,"
            + " actor_id BIGINT, source VARCHAR(100) NOT NULL, source_reference VARCHAR(255),"
            + " event_key VARCHAR(150) NOT NULL, reason VARCHAR(500))");
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute(
        "TRUNCATE m_prospect_stage_event, m_prospect_registration, m_loan, m_client, m_office");
    seedData();

    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final Office office = mock(Office.class);
    final DatabaseSpecificSQLGenerator sqlGenerator = mock(DatabaseSpecificSQLGenerator.class);
    when(context.authenticatedUser()).thenReturn(user);
    when(user.getOffice()).thenReturn(office);
    when(office.getHierarchy()).thenReturn(".1.");
    when(sqlGenerator.limit(
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenAnswer(
            invocation ->
                "LIMIT " + invocation.getArgument(0) + " OFFSET " + invocation.getArgument(1));
    service =
        new ProspectReadPlatformServiceImpl(
            new NamedParameterJdbcTemplate(jdbcTemplate),
            context,
            new ProspectSearchValidator(),
            sqlGenerator);
  }

  @Test
  void noFiltersReturnOnlyExplicitProspectsWithinOfficeScope() {
    final Page<ProspectData> result = search(new ProspectSearchRequest());

    assertEquals(4, result.getTotalFilteredRecords());
    assertEquals(List.of(3L, 5L, 2L, 1L), prospectIds(result));
    assertEquals(0, prospectIds(result).stream().filter(id -> id == 4L).count());
  }

  @Test
  void registrationStatusCreatedDateAndCombinedFiltersAreAppliedInSql() {
    final ProspectSearchRequest status = request();
    status.setRegistrationStatus("pending");
    assertEquals(List.of(5L, 2L), prospectIds(search(status)));

    final ProspectSearchRequest date = request();
    date.setCreatedFrom("2026-09-02");
    date.setCreatedTo("2026-09-02");
    date.setOrderBy("prospectId");
    date.setSortOrder("ASC");
    assertEquals(List.of(2L, 5L), prospectIds(search(date)));

    final ProspectSearchRequest combined = request();
    combined.setQ("alpha");
    combined.setOfficeId(2L);
    combined.setRegistrationStatus("IN_PROGRESS");
    combined.setCreatedFrom("2026-09-01");
    combined.setCreatedTo("2026-09-01");
    combined.setClientId(11L);
    combined.setLastCompletedStageCode("COMMERCIAL_REGISTRATION");
    assertEquals(List.of(1L), prospectIds(search(combined)));
  }

  @Test
  void deterministicPaginationAndSortingUseProspectIdTieBreaker() {
    final ProspectSearchRequest firstPage = request();
    firstPage.setOrderBy("createdAt");
    firstPage.setSortOrder("ASC");
    firstPage.setLimit(2);
    assertEquals(4, search(firstPage).getTotalFilteredRecords());
    assertEquals(List.of(1L, 2L), prospectIds(search(firstPage)));

    final ProspectSearchRequest secondPage = request();
    secondPage.setOrderBy("createdAt");
    secondPage.setSortOrder("ASC");
    secondPage.setLimit(2);
    secondPage.setOffset(2);
    assertEquals(List.of(5L, 3L), prospectIds(search(secondPage)));
  }

  @Test
  void officeRestrictionClientLinkageEmptyResultAndPendingClientRegression() {
    final ProspectSearchRequest hiddenOffice = request();
    hiddenOffice.setOfficeId(9L);
    assertEquals(0, search(hiddenOffice).getTotalFilteredRecords());

    final ProspectSearchRequest linkedClient = request();
    linkedClient.setClientId(11L);
    final ProspectData linked = search(linkedClient).getPageItems().getFirst();
    assertEquals(1L, linked.prospectId());
    assertEquals(11L, linked.clientId());
    assertEquals(2, linked.pendingCreditCount());

    final ProspectSearchRequest pendingClientOnly = request();
    pendingClientOnly.setClientId(99L);
    assertEquals(0, search(pendingClientOnly).getTotalFilteredRecords());

    final ProspectSearchRequest absent = request();
    absent.setQ("does-not-exist");
    assertEquals(0, search(absent).getTotalFilteredRecords());
  }

  @Test
  void stageEventOrderingDeterminesCurrentLastCompletedAndStoppedAtStages() {
    final ProspectData inProgress = find(search(request()), 1L);
    assertEquals("KYC_LEVEL_1", inProgress.currentStage());
    assertEquals("COMMERCIAL_REGISTRATION", inProgress.lastCompletedStage());
    assertEquals("KYC_LEVEL_1", inProgress.stoppedAtStage());

    final ProspectData completedLatest = find(search(request()), 3L);
    assertEquals("KYC_LEVEL_1", completedLatest.currentStage());
    assertEquals("KYC_LEVEL_1", completedLatest.lastCompletedStage());
    assertEquals("UNKNOWN", completedLatest.stoppedAtStage());

    final ProspectData withoutEvents = find(search(request()), 2L);
    assertEquals("UNKNOWN", withoutEvents.currentStage());
    assertEquals("UNKNOWN", withoutEvents.lastCompletedStage());
    assertEquals("UNKNOWN", withoutEvents.stoppedAtStage());
    assertNull(withoutEvents.clientId());

    final ProspectData tiedEvents = find(search(request()), 5L);
    assertEquals("KYC_LEVEL_2", tiedEvents.currentStage());
    assertEquals("UNKNOWN", tiedEvents.lastCompletedStage());
    assertEquals("KYC_LEVEL_2", tiedEvents.stoppedAtStage());
  }

  private Page<ProspectData> search(final ProspectSearchRequest request) {
    return service.search(request);
  }

  private ProspectSearchRequest request() {
    return new ProspectSearchRequest();
  }

  private List<Long> prospectIds(final Page<ProspectData> page) {
    return page.getPageItems().stream().map(ProspectData::prospectId).toList();
  }

  private ProspectData find(final Page<ProspectData> page, final long prospectId) {
    return page.getPageItems().stream()
        .filter(item -> item.prospectId() == prospectId)
        .findFirst()
        .orElseThrow();
  }

  private void seedData() {
    jdbcTemplate.update("INSERT INTO m_office VALUES (?, ?)", 1L, ".");
    jdbcTemplate.update("INSERT INTO m_office VALUES (?, ?)", 2L, ".1.");
    jdbcTemplate.update("INSERT INTO m_office VALUES (?, ?)", 3L, ".1.2.");
    jdbcTemplate.update("INSERT INTO m_office VALUES (?, ?)", 9L, ".9.");
    jdbcTemplate.update("INSERT INTO m_client VALUES (?, ?, ?, ?)", 11L, "Linked Client", 2L, 300);
    jdbcTemplate.update("INSERT INTO m_client VALUES (?, ?, ?, ?)", 99L, "Pending Client", 2L, 100);
    jdbcTemplate.update("INSERT INTO m_loan VALUES (?, ?, ?)", 101L, 11L, 100);
    jdbcTemplate.update("INSERT INTO m_loan VALUES (?, ?, ?)", 102L, 11L, 100);
    jdbcTemplate.update("INSERT INTO m_loan VALUES (?, ?, ?)", 103L, 11L, 200);
    insertProspect(
        1L,
        "alpha-1",
        2L,
        11L,
        "Alpha Prospect",
        "IN_PROGRESS",
        "2026-09-01 08:00:00",
        "2026-09-01 09:00:00");
    insertProspect(
        2L,
        "beta-2",
        2L,
        null,
        "Beta Prospect",
        "PENDING",
        "2026-09-02 00:00:00",
        "2026-09-02 00:00:00");
    insertProspect(
        3L,
        "gamma-3",
        3L,
        null,
        "Gamma Prospect",
        "SUBMITTED",
        "2026-09-03 00:00:00",
        "2026-09-03 00:00:00");
    insertProspect(
        4L,
        "hidden-4",
        9L,
        null,
        "Hidden Prospect",
        "PENDING",
        "2026-09-04 00:00:00",
        "2026-09-04 00:00:00");
    insertProspect(
        5L,
        "delta-5",
        2L,
        null,
        "Delta Prospect",
        "PENDING",
        "2026-09-02 00:00:00",
        "2026-09-02 00:00:00");
    insertStage(1L, 1L, 1L, "COMMERCIAL_REGISTRATION", "COMPLETED", "2026-09-01 08:30:00");
    insertStage(2L, 1L, 2L, "KYC_LEVEL_1", "IN_PROGRESS", "2026-09-01 09:00:00");
    insertStage(3L, 3L, 1L, "COMMERCIAL_REGISTRATION", "COMPLETED", "2026-09-03 01:00:00");
    insertStage(4L, 3L, 2L, "KYC_LEVEL_1", "COMPLETED", "2026-09-03 02:00:00");
    insertStage(5L, 5L, 1L, "COMMERCIAL_REGISTRATION", "IN_PROGRESS", "2026-09-02 01:00:00");
    insertStage(6L, 5L, 1L, "KYC_LEVEL_2", "IN_PROGRESS", "2026-09-02 01:00:00");
  }

  private void insertProspect(
      final Long id,
      final String externalRef,
      final Long officeId,
      final Long clientId,
      final String displayName,
      final String status,
      final String createdAt,
      final String lastModifiedAt) {
    jdbcTemplate.update(
        "INSERT INTO m_prospect_registration VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, 1, 1,"
            + " CAST(? AS TIMESTAMP), 1, CAST(? AS TIMESTAMP))",
        id,
        externalRef,
        officeId,
        clientId,
        displayName,
        status,
        createdAt,
        lastModifiedAt);
  }

  private void insertStage(
      final Long id,
      final Long caseId,
      final Long sequence,
      final String stageCode,
      final String status,
      final String occurredAt) {
    jdbcTemplate.update(
        "INSERT INTO m_prospect_stage_event VALUES (?, ?, ?, ?, NULL, ?, CAST(? AS TIMESTAMP),"
            + " CAST(? AS TIMESTAMP), 1, 'TEST', NULL, ?, NULL)",
        id,
        caseId,
        sequence,
        stageCode,
        status,
        occurredAt,
        occurredAt,
        "event-" + id);
  }
}
