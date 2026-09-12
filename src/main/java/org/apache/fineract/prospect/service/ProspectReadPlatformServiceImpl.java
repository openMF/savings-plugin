/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.prospect.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.prospect.data.ProspectData;
import org.apache.fineract.prospect.data.ProspectSearchCriteria;
import org.apache.fineract.prospect.data.ProspectSearchRequest;
import org.apache.fineract.prospect.data.ProspectStageSummary;
import org.apache.fineract.prospect.validation.ProspectSearchValidator;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProspectReadPlatformServiceImpl implements ProspectReadPlatformService {

  private static final String READ_PROSPECT_PERMISSION_RESOURCE = "PROSPECT";
  private static final int SUBMITTED_AND_PENDING_APPROVAL_LOAN_STATUS = 100;
  private static final Map<String, String> ORDER_BY_COLUMNS =
      Map.ofEntries(
          Map.entry("prospectId", "p.id"),
          Map.entry("externalRef", "p.external_ref"),
          Map.entry("displayName", "p.display_name"),
          Map.entry("officeId", "p.office_id"),
          Map.entry("clientId", "p.client_id"),
          Map.entry("registrationStatus", "p.registration_status"),
          Map.entry("createdAt", "p.created_on_utc"),
          Map.entry("submittedAt", "p.submitted_on_utc"),
          Map.entry("lastUpdatedAt", lastUpdatedExpression()),
          Map.entry("currentStage", "le.stage_code"),
          Map.entry("lastCompletedStage", "ce.stage_code"),
          Map.entry("stoppedAtStage", "le.stage_code"),
          Map.entry("pendingCreditCount", "COALESCE(pc.pending_credit_count, 0)"));

  private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  private final PlatformSecurityContext context;
  private final ProspectSearchValidator validator;
  private final DatabaseSpecificSQLGenerator sqlGenerator;

  @Override
  public Page<ProspectData> search(final ProspectSearchRequest request) {
    final AppUser currentUser = context.authenticatedUser();
    currentUser.validateHasReadPermission(READ_PROSPECT_PERMISSION_RESOURCE);
    final ProspectSearchCriteria criteria = validator.validate(request);
    final Office userOffice = currentUser.getOffice();

    final Map<String, Object> parameters = new HashMap<>();
    parameters.put("officeHierarchy", userOffice.getHierarchy() + "%");
    parameters.put("pendingLoanStatus", SUBMITTED_AND_PENDING_APPROVAL_LOAN_STATUS);

    final String fromAndWhere = buildFromAndWhere(criteria, parameters);
    final Long count =
        namedParameterJdbcTemplate.queryForObject(
            "SELECT COUNT(*) " + fromAndWhere, parameters, Long.class);
    if (count == null || count == 0) {
      return new Page<>(List.of(), 0);
    }

    final String sql =
        selectColumns()
            + fromAndWhere
            + buildOrderBy(criteria)
            + " "
            + sqlGenerator.limit(criteria.limit(), criteria.offset());
    final List<ProspectData> pageItems =
        namedParameterJdbcTemplate.query(sql, parameters, new ProspectRowMapper());
    return new Page<>(pageItems, Math.toIntExact(count));
  }

  private String buildFromAndWhere(
      final ProspectSearchCriteria criteria, final Map<String, Object> parameters) {
    final StringBuilder sql =
        new StringBuilder(
            " FROM m_prospect_registration p"
                + " JOIN m_office o ON o.id = p.office_id"
                + latestStageJoin()
                + completedStageJoin()
                + " LEFT JOIN ("
                + " SELECT l.client_id, COUNT(*) AS pending_credit_count"
                + " FROM m_loan l"
                + " WHERE l.client_id IS NOT NULL AND l.loan_status_id = :pendingLoanStatus"
                + " GROUP BY l.client_id"
                + " ) pc ON pc.client_id = p.client_id"
                + " WHERE o.hierarchy LIKE :officeHierarchy");

    addEquals(sql, parameters, "p.office_id", "officeId", criteria.officeId());
    addEquals(sql, parameters, "p.client_id", "clientId", criteria.clientId());
    addEquals(
        sql,
        parameters,
        "p.registration_status",
        "registrationStatus",
        criteria.registrationStatus());
    addEquals(
        sql,
        parameters,
        "ce.stage_code",
        "lastCompletedStageCode",
        criteria.lastCompletedStageCode());
    if (criteria.q() != null) {
      sql.append(
          " AND (LOWER(COALESCE(p.external_ref, '')) LIKE :q"
              + " OR LOWER(COALESCE(p.display_name, '')) LIKE :q)");
      parameters.put("q", "%" + criteria.q().toLowerCase(Locale.ROOT) + "%");
    }
    if (criteria.createdFrom() != null) {
      sql.append(" AND p.created_on_utc >= :createdFrom");
      parameters.put("createdFrom", criteria.createdFrom().atStartOfDay());
    }
    if (criteria.createdTo() != null) {
      sql.append(" AND p.created_on_utc < :createdToExclusive");
      parameters.put("createdToExclusive", criteria.createdTo().plusDays(1).atStartOfDay());
    }
    return sql.toString();
  }

  private String selectColumns() {
    return "SELECT p.id AS prospect_id, p.external_ref, p.display_name, p.office_id,"
        + " p.client_id, p.registration_status, p.created_on_utc, p.submitted_on_utc,"
        + " "
        + lastUpdatedExpression()
        + " AS last_updated_at,"
        + " le.stage_code AS latest_stage_code, le.new_status AS latest_stage_status,"
        + " ce.stage_code AS last_completed_stage_code,"
        + " COALESCE(pc.pending_credit_count, 0) AS pending_credit_count";
  }

  private String buildOrderBy(final ProspectSearchCriteria criteria) {
    final String column = ORDER_BY_COLUMNS.get(criteria.orderBy());
    final String direction = criteria.sortOrder();
    if ("prospectId".equals(criteria.orderBy())) {
      return " ORDER BY p.id " + direction;
    }
    return " ORDER BY " + column + " " + direction + ", p.id " + direction;
  }

  private static String latestStageJoin() {
    return " LEFT JOIN m_prospect_stage_event le ON le.case_id = p.id"
        + " AND NOT EXISTS ("
        + " SELECT 1 FROM m_prospect_stage_event newer"
        + " WHERE newer.case_id = le.case_id"
        + " AND (newer.sequence_no > le.sequence_no"
        + " OR (newer.sequence_no = le.sequence_no AND newer.recorded_on_utc > le.recorded_on_utc)"
        + " OR (newer.sequence_no = le.sequence_no"
        + " AND newer.recorded_on_utc = le.recorded_on_utc"
        + " AND newer.id > le.id)))";
  }

  private static String completedStageJoin() {
    return " LEFT JOIN m_prospect_stage_event ce ON ce.case_id = p.id"
        + " AND ce.new_status = 'COMPLETED'"
        + " AND NOT EXISTS ("
        + " SELECT 1 FROM m_prospect_stage_event newer_completed"
        + " WHERE newer_completed.case_id = ce.case_id"
        + " AND newer_completed.new_status = 'COMPLETED'"
        + " AND (newer_completed.sequence_no > ce.sequence_no"
        + " OR (newer_completed.sequence_no = ce.sequence_no"
        + " AND newer_completed.recorded_on_utc > ce.recorded_on_utc)"
        + " OR (newer_completed.sequence_no = ce.sequence_no"
        + " AND newer_completed.recorded_on_utc = ce.recorded_on_utc"
        + " AND newer_completed.id > ce.id)))";
  }

  private static String lastUpdatedExpression() {
    return "COALESCE(le.occurred_on_utc, le.recorded_on_utc,"
        + " p.last_modified_on_utc, p.created_on_utc)";
  }

  private void addEquals(
      final StringBuilder sql,
      final Map<String, Object> parameters,
      final String column,
      final String parameter,
      final Object value) {
    if (value != null) {
      sql.append(" AND ").append(column).append(" = :").append(parameter);
      parameters.put(parameter, value);
    }
  }

  static final class ProspectRowMapper implements RowMapper<ProspectData> {

    @Override
    public ProspectData mapRow(final ResultSet resultSet, final int rowNumber)
        throws SQLException {
      final ProspectStageSummary stages =
          ProspectStageResolver.resolve(
              resultSet.getString("latest_stage_code"),
              resultSet.getString("latest_stage_status"),
              resultSet.getString("last_completed_stage_code"));
      return new ProspectData(
          resultSet.getLong("prospect_id"),
          resultSet.getString("external_ref"),
          resultSet.getString("display_name"),
          resultSet.getLong("office_id"),
          nullableLong(resultSet, "client_id"),
          resultSet.getString("registration_status"),
          offsetDateTime(resultSet, "created_on_utc"),
          offsetDateTime(resultSet, "submitted_on_utc"),
          offsetDateTime(resultSet, "last_updated_at"),
          stages.currentStage(),
          stages.lastCompletedStage(),
          stages.stoppedAtStage(),
          resultSet.getInt("pending_credit_count"));
    }

    private static Long nullableLong(final ResultSet resultSet, final String column)
        throws SQLException {
      final long value = resultSet.getLong(column);
      return resultSet.wasNull() ? null : value;
    }

    private static String offsetDateTime(final ResultSet resultSet, final String column)
        throws SQLException {
      final Timestamp timestamp = resultSet.getTimestamp(column);
      if (timestamp == null) {
        return null;
      }
      final LocalDateTime localDateTime = timestamp.toLocalDateTime();
      return OffsetDateTime.of(localDateTime, ZoneOffset.UTC).toString();
    }
  }
}
