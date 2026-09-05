/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.creditapplication.data.CreditApplicationData;
import org.apache.fineract.creditapplication.data.CreditApplicationSearchRequest;
import org.apache.fineract.creditapplication.validation.CreditApplicationSearchValidator;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
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
class CreditApplicationSearchIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine");

  private static JdbcTemplate jdbcTemplate;
  private CreditApplicationReadPlatformService service;

  @BeforeAll
  static void createSchema() {
    final DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute(
        "CREATE TABLE m_office (id BIGINT PRIMARY KEY, hierarchy VARCHAR(100) NOT NULL)");
    jdbcTemplate.execute(
        "CREATE TABLE m_client (id BIGINT PRIMARY KEY, display_name VARCHAR(255), office_id BIGINT"
            + " NOT NULL, transfer_to_office_id BIGINT, client_type_cv_id BIGINT)");
    jdbcTemplate.execute(
        "CREATE TABLE m_group (id BIGINT PRIMARY KEY, display_name VARCHAR(255),"
            + " office_id BIGINT NOT NULL)");
    jdbcTemplate.execute(
        "CREATE TABLE m_product_loan (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL)");
    jdbcTemplate.execute(
        "CREATE TABLE m_loan (id BIGINT PRIMARY KEY, account_no VARCHAR(20) NOT NULL,"
            + " client_id BIGINT, group_id BIGINT, product_id BIGINT NOT NULL,"
            + " loan_status_id SMALLINT NOT NULL, currency_code VARCHAR(3) NOT NULL,"
            + " principal_amount_proposed DECIMAL(19,6) NOT NULL, submittedon_date DATE)");
    jdbcTemplate.execute(
        "CREATE TABLE m_address (id BIGINT PRIMARY KEY, state_province_id BIGINT,"
            + " county_district VARCHAR(100))");
    jdbcTemplate.execute(
        "CREATE TABLE m_client_address (id BIGINT PRIMARY KEY, client_id BIGINT NOT NULL,"
            + " address_id BIGINT NOT NULL, is_active BOOLEAN NOT NULL)");
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute(
        "TRUNCATE m_client_address, m_address, m_loan, m_product_loan, m_group,"
            + " m_client, m_office");
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
        new CreditApplicationReadPlatformServiceImpl(
            new NamedParameterJdbcTemplate(jdbcTemplate),
            context,
            new CreditApplicationSearchValidator(),
            sqlGenerator);
  }

  @Test
  void noFiltersReturnsScopedClientsGroupsAndAddresslessClientsWithoutDuplicates() {
    final Page<CreditApplicationData> result = search(new CreditApplicationSearchRequest());

    assertEquals(4, result.getTotalFilteredRecords());
    assertEquals(List.of(2L, 1L, 3L, 5L), loanIds(result));
    assertEquals(2, loanIds(result).stream().filter(id -> id == 1L || id == 2L).count());
    final CreditApplicationData addressless = find(result, 3L);
    assertNull(addressless.stateProvinceId());
    assertNull(addressless.municipality());
    final CreditApplicationData groupLoan = find(result, 5L);
    assertNull(groupLoan.clientId());
    assertEquals(21L, groupLoan.groupId());
    assertEquals("Solidarity Group", groupLoan.clientName());
  }

  @Test
  void dateProductClientTypeStatusAndLocationFiltersAreAppliedAtQueryLevel() {
    final CreditApplicationSearchRequest from = request();
    from.setSubmittedFrom("2026-09-01");
    assertEquals(List.of(2L, 1L), loanIds(search(from)));

    final CreditApplicationSearchRequest to = request();
    to.setSubmittedTo("2026-08-15");
    assertEquals(List.of(3L, 5L), loanIds(search(to)));

    final CreditApplicationSearchRequest range = request();
    range.setSubmittedFrom("2026-08-01");
    range.setSubmittedTo("2026-08-31");
    assertEquals(List.of(3L), loanIds(search(range)));

    final CreditApplicationSearchRequest clientType = request();
    clientType.setClientTypeId(7L);
    assertEquals(List.of(2L, 1L), loanIds(search(clientType)));

    final CreditApplicationSearchRequest product = request();
    product.setProductId(32L);
    assertEquals(List.of(2L), loanIds(search(product)));

    final CreditApplicationSearchRequest status = request();
    status.setStatus(200);
    final CreditApplicationData approved = search(status).getPageItems().getFirst();
    assertEquals(2L, approved.loanId());
    assertEquals(200L, approved.status().id());
    assertEquals("loanStatusType.approved", approved.status().code());

    final CreditApplicationSearchRequest state = request();
    state.setStateProvinceId(91L);
    final Page<CreditApplicationData> stateResult = search(state);
    assertEquals(List.of(2L, 1L), loanIds(stateResult));
    assertEquals(91L, stateResult.getPageItems().getFirst().stateProvinceId());
    assertEquals("Beta", stateResult.getPageItems().getFirst().municipality());

    final CreditApplicationSearchRequest municipality = request();
    municipality.setMunicipality("Alpha");
    assertEquals(List.of(2L, 1L), loanIds(search(municipality)));
  }

  @Test
  void amountCurrencyCombinedAndIdentityFiltersUseProposedPrincipal() {
    final CreditApplicationSearchRequest currency = request();
    currency.setCurrencyCode("MXN");
    assertEquals(List.of(3L), loanIds(search(currency)));

    final CreditApplicationSearchRequest minimum = request();
    minimum.setMinAmount(new BigDecimal("1500"));
    minimum.setCurrencyCode("USD");
    assertEquals(List.of(2L), loanIds(search(minimum)));

    final CreditApplicationSearchRequest maximum = request();
    maximum.setMaxAmount(new BigDecimal("1000"));
    maximum.setCurrencyCode("USD");
    assertEquals(List.of(1L, 5L), loanIds(search(maximum)));

    final CreditApplicationSearchRequest range = request();
    range.setMinAmount(new BigDecimal("900"));
    range.setMaxAmount(new BigDecimal("1100"));
    range.setCurrencyCode("USD");
    assertEquals(List.of(1L), loanIds(search(range)));

    final CreditApplicationSearchRequest combined = request();
    combined.setClientId(11L);
    combined.setOfficeId(2L);
    combined.setProductId(31L);
    combined.setStatus(100);
    combined.setSubmittedFrom("2026-09-01");
    combined.setStateProvinceId(90L);
    combined.setMunicipality("Alpha");
    assertEquals(List.of(1L), loanIds(search(combined)));
  }

  @Test
  void paginationCountsBeforeLimitAndSortingUsesLoanIdTieBreaker() {
    final CreditApplicationSearchRequest firstPage = request();
    firstPage.setLimit(1);
    assertEquals(4, search(firstPage).getTotalFilteredRecords());
    assertEquals(List.of(2L), loanIds(search(firstPage)));

    final CreditApplicationSearchRequest secondPage = request();
    secondPage.setOffset(1);
    secondPage.setLimit(1);
    assertEquals(4, search(secondPage).getTotalFilteredRecords());
    assertEquals(List.of(1L), loanIds(search(secondPage)));

    final CreditApplicationSearchRequest sortByAmount = request();
    sortByAmount.setOrderBy("amount");
    sortByAmount.setSortOrder("ASC");
    assertEquals(List.of(3L, 5L, 1L, 2L), loanIds(search(sortByAmount)));
  }

  @Test
  void emptyAndUnauthorizedOfficeFiltersDoNotLeakRowsOrCounts() {
    final CreditApplicationSearchRequest absentProduct = request();
    absentProduct.setProductId(999L);
    assertEquals(0, search(absentProduct).getTotalFilteredRecords());

    final CreditApplicationSearchRequest unauthorizedOffice = request();
    unauthorizedOffice.setOfficeId(9L);
    assertEquals(0, search(unauthorizedOffice).getTotalFilteredRecords());
  }

  private Page<CreditApplicationData> search(final CreditApplicationSearchRequest request) {
    return service.search(request);
  }

  private CreditApplicationSearchRequest request() {
    return new CreditApplicationSearchRequest();
  }

  private List<Long> loanIds(final Page<CreditApplicationData> page) {
    return page.getPageItems().stream().map(CreditApplicationData::loanId).toList();
  }

  private CreditApplicationData find(final Page<CreditApplicationData> page, final long loanId) {
    return page.getPageItems().stream()
        .filter(item -> item.loanId() == loanId)
        .findFirst()
        .orElseThrow();
  }

  private void seedData() {
    jdbcTemplate.update("INSERT INTO m_office VALUES (?, ?)", 1L, ".");
    jdbcTemplate.update("INSERT INTO m_office VALUES (?, ?)", 2L, ".1.");
    jdbcTemplate.update("INSERT INTO m_office VALUES (?, ?)", 3L, ".1.2.");
    jdbcTemplate.update("INSERT INTO m_office VALUES (?, ?)", 9L, ".9.");
    jdbcTemplate.update("INSERT INTO m_client VALUES (?, ?, ?, ?, ?)", 11L, "Alice", 2L, null, 7L);
    jdbcTemplate.update("INSERT INTO m_client VALUES (?, ?, ?, ?, ?)", 12L, "Bob", 3L, null, 8L);
    jdbcTemplate.update("INSERT INTO m_client VALUES (?, ?, ?, ?, ?)", 13L, "Hidden", 9L, null, 7L);
    jdbcTemplate.update("INSERT INTO m_group VALUES (?, ?, ?)", 21L, "Solidarity Group", 2L);
    jdbcTemplate.update("INSERT INTO m_product_loan VALUES (?, ?)", 31L, "Starter Loan");
    jdbcTemplate.update("INSERT INTO m_product_loan VALUES (?, ?)", 32L, "Business Loan");
    jdbcTemplate.update("INSERT INTO m_address VALUES (?, ?, ?)", 101L, 90L, "Alpha");
    jdbcTemplate.update("INSERT INTO m_address VALUES (?, ?, ?)", 102L, 91L, "Beta");
    jdbcTemplate.update("INSERT INTO m_address VALUES (?, ?, ?)", 103L, 90L, "Alpha");
    jdbcTemplate.update("INSERT INTO m_client_address VALUES (?, ?, ?, ?)", 201L, 11L, 101L, true);
    jdbcTemplate.update("INSERT INTO m_client_address VALUES (?, ?, ?, ?)", 202L, 11L, 102L, true);
    jdbcTemplate.update("INSERT INTO m_client_address VALUES (?, ?, ?, ?)", 203L, 13L, 103L, true);
    insertLoan(1L, "L1", 11L, null, 31L, 100, "USD", "1000", "2026-09-01");
    insertLoan(2L, "L2", 11L, null, 32L, 200, "USD", "2500", "2026-09-01");
    insertLoan(3L, "L3", 12L, null, 31L, 100, "MXN", "500", "2026-08-15");
    insertLoan(4L, "L4", 13L, null, 31L, 100, "USD", "3000", "2026-09-02");
    insertLoan(5L, "L5", null, 21L, 31L, 100, "USD", "750", "2026-07-01");
  }

  private void insertLoan(
      final long id,
      final String accountNo,
      final Long clientId,
      final Long groupId,
      final long productId,
      final int status,
      final String currency,
      final String amount,
      final String submittedOn) {
    jdbcTemplate.update(
        "INSERT INTO m_loan VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS DATE))",
        id,
        accountNo,
        clientId,
        groupId,
        productId,
        status,
        currency,
        new BigDecimal(amount),
        submittedOn);
  }
}
