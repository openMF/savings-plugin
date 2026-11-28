/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;

/** Minimal Fineract-shaped tables read by the origination board, dashboard and stage events. */
final class CreditOriginationTestSchema {

  private CreditOriginationTestSchema() {}

  static void create(final JdbcTemplate jdbc) {
    jdbc.execute("CREATE TABLE m_office (id BIGINT PRIMARY KEY, hierarchy VARCHAR(100))");
    jdbc.execute("CREATE TABLE m_appuser (id BIGINT PRIMARY KEY, username VARCHAR(100))");
    jdbc.execute("CREATE TABLE m_staff (id BIGINT PRIMARY KEY, display_name VARCHAR(100))");
    jdbc.execute(
        "CREATE TABLE m_client (id BIGINT PRIMARY KEY, office_id BIGINT,"
            + " transfer_to_office_id BIGINT, status_enum INT, activation_date DATE,"
            + " display_name VARCHAR(100))");
    jdbc.execute(
        "CREATE TABLE m_loan (id BIGINT PRIMARY KEY, client_id BIGINT, product_id BIGINT,"
            + " loan_status_id INT, currency_code VARCHAR(3), approvedon_date DATE,"
            + " approvedon_userid BIGINT, disbursedon_date DATE, disbursedon_userid BIGINT,"
            + " closedon_date DATE, loan_officer_id BIGINT)");
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
            + " kyc_verification_id BIGINT UNIQUE, decision_status VARCHAR(50))");
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
            + " reason VARCHAR(500), component_code VARCHAR(30),"
            + " CONSTRAINT ck_credit_stage_event_component CHECK (component_code IS NULL"
            + " OR (stage_code = 'PARAMETRIC_SCORE'"
            + " AND component_code IN ('SAT', 'CREDIT_BUREAU', 'SYNTAGE', 'RUG', 'RPC'))))");
  }

  static void truncate(final JdbcTemplate jdbc) {
    jdbc.execute(
        "TRUNCATE m_credit_origination_stage_event, m_loan_transaction,"
            + " m_client_kyc_decision, m_client_kyc_verification, m_prospect_registration,"
            + " m_loan, m_client, m_staff, m_appuser, m_office RESTART IDENTITY");
  }

  static PlatformSecurityContext userIn(final String hierarchy) {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final Office office = mock(Office.class);
    when(context.authenticatedUser()).thenReturn(user);
    when(user.getOffice()).thenReturn(office);
    when(office.getHierarchy()).thenReturn(hierarchy);
    return context;
  }

  static void businessDate(final LocalDate date) {
    ThreadLocalContextUtil.setBusinessDates(
        new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, date)));
  }
}
