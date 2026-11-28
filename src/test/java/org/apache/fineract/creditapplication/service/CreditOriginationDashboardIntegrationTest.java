/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.apache.fineract.creditapplication.data.CreditOriginationDashboardData;
import org.apache.fineract.creditapplication.data.CreditOriginationDashboardData.StageDistribution;
import org.apache.fineract.creditapplication.data.CreditOriginationDashboardData.StalledApplication;
import org.apache.fineract.creditapplication.data.CreditOriginationDashboardRequest;
import org.apache.fineract.creditapplication.data.CreditOriginationStageCode;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CreditOriginationDashboardIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  private static JdbcTemplate jdbc;
  private NamedParameterJdbcTemplate named;

  @BeforeAll
  static void createSchema() {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    CreditOriginationTestSchema.create(jdbc);
  }

  /**
   * Business date 2026-01-20. Visible to '.1.': clients 10-14 and 17 (office 3 is a child office).
   * Client 12 has two applications; 150 is rejected, 160 cancelled and 200 sits in office 2.
   */
  @BeforeEach
  void seed() {
    CreditOriginationTestSchema.truncate(jdbc);
    CreditOriginationTestSchema.businessDate(LocalDate.of(2026, 1, 20));
    named = new NamedParameterJdbcTemplate(jdbc);
    jdbc.update("INSERT INTO m_office VALUES (1, '.1.'), (2, '.2.'), (3, '.1.3.')");
    jdbc.update("INSERT INTO m_staff VALUES (7, 'Ana Officer')");
    client(10, 1, "Eight Days");
    client(11, 1, "Seven Days");
    client(12, 3, "Two Applications");
    client(13, 1, "Unknown Entry");
    client(14, 1, "In Scoring");
    client(15, 1, "Rejected");
    client(16, 1, "Cancelled");
    client(17, 1, "Future Entry");
    client(20, 2, "Other Office");
    loan(100, 10, 1, 100);
    loan(110, 11, 1, 100);
    loan(120, 12, 1, 200);
    loan(121, 12, 2, 100);
    loan(130, 13, 1, 100);
    loan(140, 14, 1, 100);
    loan(150, 15, 1, 500);
    loan(160, 16, 1, 100);
    loan(170, 17, 1, 100);
    loan(200, 20, 1, 100);
    prospect(30, 10, "PENDING", "2026-01-12 00:00:00", "2026-01-12 00:00:00");
    prospect(31, 11, "PENDING", "2026-01-13 15:00:00", "2026-01-13 15:00:00");
    // An older registration for the same client must not create a second prospect.
    prospect(29, 11, "PENDING", "2025-12-01 00:00:00", "2025-12-01 00:00:00");
    prospect(33, 13, "COMPLETED", "2026-01-01 00:00:00", null);
    prospect(34, 14, "COMPLETED", "2026-01-05 00:00:00", "2026-01-05 00:00:00");
    prospect(37, 17, "PENDING", "2026-02-01 00:00:00", "2026-02-01 00:00:00");
    prospect(40, 20, "PENDING", "2025-12-01 00:00:00", "2025-12-01 00:00:00");
    jdbc.update(
        "INSERT INTO m_client_kyc_verification VALUES"
            + " (44, 14, 'Approved', 1768471200000, 1768471200000, 1768471200000)");
    jdbc.update(
        "INSERT INTO m_credit_origination_stage_event (loan_id, client_id, stage_code,"
            + " new_status, occurred_on_utc, recorded_on_utc, source, event_key)"
            + " VALUES (160, 16, 'ONBOARDING', 'CANCELLED', TIMESTAMP '2026-01-02 00:00:00',"
            + " TIMESTAMP '2026-01-02 00:00:00', 'TEST', 'cancel-160')");
  }

  @Test
  void summaryAndDistributionCountApplicationsAndDistinctProspects() {
    final CreditOriginationDashboardData dashboard = dashboard(null, null, 0, 50);

    assertEquals(LocalDate.of(2026, 1, 20), dashboard.businessDate());
    assertEquals(7, dashboard.stalledThresholdDays());
    assertEquals(7, dashboard.summary().activeApplications());
    assertEquals(6, dashboard.summary().activeProspects());
    assertEquals(3, dashboard.summary().stalledApplications());
    assertEquals(2, dashboard.summary().stalledProspects());
    assertEquals(2, dashboard.summary().unknownAgingApplications());
    assertEquals(1, dashboard.summary().parametricScoreApplications());
    assertEquals(1, dashboard.summary().parametricScoreProspects());
    assertNull(dashboard.summary().progressRate());
    assertEquals("UNDEFINED", dashboard.summary().progressRateAvailability());

    assertEquals(
        Arrays.stream(CreditOriginationStageCode.values()).map(Enum::name).toList(),
        dashboard.stages().stream().map(StageDistribution::code).toList());
    final StageDistribution onboarding = dashboard.stages().get(0);
    final StageDistribution compliance = dashboard.stages().get(1);
    assertEquals(List.of(1, 3, 3, 1, 1, 1), counts(onboarding));
    assertEquals(List.of(2, 2, 3, 1, 2, 1), counts(compliance));
    assertEquals("EVENTS_ONLY", dashboard.stages().get(2).statusAvailability());
    assertEquals("DERIVED", compliance.statusAvailability());
    assertEquals(
        dashboard.summary().activeApplications(),
        dashboard.stages().stream().mapToLong(StageDistribution::applicationCount).sum());
  }

  @Test
  void stalledListIsStrictlyOverSevenDaysOrderedAndPaginated() {
    final CreditOriginationDashboardData first = dashboard(null, null, 0, 2);
    final CreditOriginationDashboardData second = dashboard(null, null, 2, 2);

    assertEquals(3, first.stalledApplications().getTotalFilteredRecords());
    assertEquals(List.of(120L, 121L), ids(first.stalledApplications().getPageItems()));
    assertEquals(List.of(100L), ids(second.stalledApplications().getPageItems()));
    assertEquals(3, second.stalledApplications().getTotalFilteredRecords());
    assertTrue(dashboard(null, null, 50, 2).stalledApplications().getPageItems().isEmpty());

    final StalledApplication worst = first.stalledApplications().getPageItems().getFirst();
    assertEquals(19L, worst.daysInStage());
    assertEquals("COMPLIANCE", worst.currentStage());
    assertEquals("CURRENT", worst.currentStageStatus());
    assertEquals(12L, worst.clientId());
    assertNull(worst.prospectId());
    assertEquals("Two Applications", worst.customerName());
    assertEquals(3L, worst.officeId());
    assertEquals(7L, worst.responsibleStaffId());
    final StalledApplication eightDays = second.stalledApplications().getPageItems().getFirst();
    assertEquals(30L, eightDays.prospectId());
    assertEquals(8L, eightDays.daysInStage());
  }

  @Test
  void stalledEntriesAgreeWithTheIndividualBoard() {
    final CreditOriginationBoardReadPlatformService board =
        new CreditOriginationBoardReadPlatformServiceImpl(named, user(".1."));

    for (StalledApplication stalled :
        dashboard(null, null, 0, 50).stalledApplications().getPageItems()) {
      final var aging = board.retrieve(stalled.creditApplicationId()).currentStageAging();
      assertEquals(stalled.currentStage(), aging.stage());
      assertEquals(stalled.daysInStage(), aging.daysInStage());
      assertEquals("STALLED", aging.status());
    }
    assertEquals("ON_TIME", board.retrieve(110L).currentStageAging().status());
    assertEquals(7L, board.retrieve(110L).currentStageAging().daysInStage());
    assertEquals("UNKNOWN", board.retrieve(170L).currentStageAging().status());
  }

  @Test
  void officeAndProductFiltersApplyToCountsAndListAlike() {
    final CreditOriginationDashboardData office = dashboard(3L, null, 0, 50);
    final CreditOriginationDashboardData product = dashboard(null, 2L, 0, 50);

    assertEquals(2, office.summary().activeApplications());
    assertEquals(1, office.summary().activeProspects());
    assertEquals(2, office.stalledApplications().getTotalFilteredRecords());
    assertEquals(3L, office.officeId());
    assertEquals(1, product.summary().activeApplications());
    assertEquals(List.of(121L), ids(product.stalledApplications().getPageItems()));
    assertEquals(2L, product.productId());
  }

  @Test
  void unauthorizedOfficesAreExcludedFromAggregatesAndList() {
    final CreditOriginationDashboardData branch =
        new CreditOriginationDashboardReadPlatformServiceImpl(named, user(".1.3."))
            .retrieve(request(null, null, 0, 50));
    final CreditOriginationDashboardData foreignFilter = dashboard(2L, null, 0, 50);

    assertEquals(2, branch.summary().activeApplications());
    assertEquals(List.of(120L, 121L), ids(branch.stalledApplications().getPageItems()));
    assertEquals(0, foreignFilter.summary().activeApplications());
    assertEquals(0, foreignFilter.stalledApplications().getTotalFilteredRecords());
  }

  @Test
  void dashboardReadsNeverWrite() {
    final String snapshot =
        "SELECT (SELECT COUNT(*) FROM m_credit_origination_stage_event) || ':'"
            + " || (SELECT COUNT(*) FROM m_loan) || ':'"
            + " || (SELECT COUNT(*) FROM m_prospect_registration)";
    final String before = jdbc.queryForObject(snapshot, String.class);

    dashboard(null, null, 0, 50);

    assertEquals(before, jdbc.queryForObject(snapshot, String.class));
    assertTrue(
        CreditOriginationDashboardReadPlatformServiceImpl.class
            .getAnnotation(Transactional.class)
            .readOnly());
  }

  @Test
  void invalidPaginationAndIdentifiersAreRejected() {
    assertThrows(PlatformApiDataValidationException.class, () -> dashboard(null, null, 0, 0));
    assertThrows(PlatformApiDataValidationException.class, () -> dashboard(null, null, 0, 201));
    assertThrows(PlatformApiDataValidationException.class, () -> dashboard(null, null, -1, 10));
    assertThrows(PlatformApiDataValidationException.class, () -> dashboard(null, null, 10_001, 10));
    assertThrows(PlatformApiDataValidationException.class, () -> dashboard(0L, null, 0, 10));
    assertThrows(PlatformApiDataValidationException.class, () -> dashboard(null, -2L, 0, 10));
  }

  private CreditOriginationDashboardData dashboard(
      final Long officeId, final Long productId, final int offset, final int limit) {
    return new CreditOriginationDashboardReadPlatformServiceImpl(named, user(".1."))
        .retrieve(request(officeId, productId, offset, limit));
  }

  private static CreditOriginationDashboardRequest request(
      final Long officeId, final Long productId, final int offset, final int limit) {
    final CreditOriginationDashboardRequest request = new CreditOriginationDashboardRequest();
    request.setOfficeId(officeId);
    request.setProductId(productId);
    request.setOffset(offset);
    request.setLimit(limit);
    return request;
  }

  private static PlatformSecurityContext user(final String hierarchy) {
    return CreditOriginationTestSchema.userIn(hierarchy);
  }

  private static List<Integer> counts(final StageDistribution stage) {
    return List.of(
        stage.order(),
        (int) stage.prospectCount(),
        (int) stage.applicationCount(),
        (int) stage.stalledProspectCount(),
        (int) stage.stalledApplicationCount(),
        (int) stage.unknownAgingApplicationCount());
  }

  private static List<Long> ids(final List<StalledApplication> items) {
    return items.stream().map(StalledApplication::creditApplicationId).toList();
  }

  private static void client(final long id, final long officeId, final String name) {
    jdbc.update(
        "INSERT INTO m_client VALUES (?, ?, NULL, 300, DATE '2026-01-01', ?)", id, officeId, name);
  }

  private static void loan(
      final long id, final long clientId, final long productId, final int status) {
    jdbc.update(
        "INSERT INTO m_loan VALUES (?, ?, ?, ?, 'USD', NULL, NULL, NULL, NULL, NULL, 7)",
        id,
        clientId,
        productId,
        status);
  }

  private static void prospect(
      final long id,
      final long clientId,
      final String status,
      final String createdOn,
      final String modifiedOn) {
    jdbc.update(
        "INSERT INTO m_prospect_registration VALUES (?, ?, ?, CAST(? AS TIMESTAMP),"
            + " CAST(? AS TIMESTAMP))",
        id,
        clientId,
        status,
        createdOn,
        modifiedOn);
  }
}
