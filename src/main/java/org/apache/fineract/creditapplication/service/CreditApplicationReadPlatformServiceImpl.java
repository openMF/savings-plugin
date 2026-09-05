/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.creditapplication.data.CreditApplicationData;
import org.apache.fineract.creditapplication.data.CreditApplicationSearchCriteria;
import org.apache.fineract.creditapplication.data.CreditApplicationSearchRequest;
import org.apache.fineract.creditapplication.data.CreditApplicationStatusData;
import org.apache.fineract.creditapplication.validation.CreditApplicationSearchValidator;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.loanaccount.data.LoanStatusEnumData;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanproduct.service.LoanEnumerations;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** JDBC-backed search over authoritative Fineract tenant data. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreditApplicationReadPlatformServiceImpl
    implements CreditApplicationReadPlatformService {

  private static final String READ_PERMISSION_RESOURCE = "LOAN";
  private static final Map<String, String> ORDER_BY_COLUMNS =
      Map.ofEntries(
          Map.entry("loanId", "l.id"),
          Map.entry("accountNo", "l.account_no"),
          Map.entry("clientName", "COALESCE(c.display_name, g.display_name)"),
          Map.entry("clientTypeId", "c.client_type_cv_id"),
          Map.entry("officeId", "o.id"),
          Map.entry("productId", "lp.id"),
          Map.entry("productName", "lp.name"),
          Map.entry("currencyCode", "l.currency_code"),
          Map.entry("amount", "l.principal_amount_proposed"),
          Map.entry("status", "l.loan_status_id"),
          Map.entry("submittedOnDate", "l.submittedon_date"),
          Map.entry("stateProvinceId", "a.state_province_id"),
          Map.entry("municipality", "a.county_district"));

  private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  private final PlatformSecurityContext context;
  private final CreditApplicationSearchValidator validator;
  private final DatabaseSpecificSQLGenerator sqlGenerator;

  @Override
  public Page<CreditApplicationData> search(final CreditApplicationSearchRequest request) {
    final AppUser currentUser = context.authenticatedUser();
    currentUser.validateHasReadPermission(READ_PERMISSION_RESOURCE);
    final CreditApplicationSearchCriteria criteria = validator.validate(request);
    final Office userOffice = currentUser.getOffice();
    final String hierarchy = userOffice.getHierarchy() + "%";

    final Map<String, Object> parameters = new HashMap<>();
    parameters.put("officeHierarchy", hierarchy);
    parameters.put("activeAddress", true);

    final String fromAndWhere = buildFromAndWhere(criteria, parameters);
    final String countSql = "SELECT COUNT(*) " + fromAndWhere;
    final Long count = namedParameterJdbcTemplate.queryForObject(countSql, parameters, Long.class);
    if (count == null || count == 0) {
      return new Page<>(List.of(), 0);
    }

    final String dataSql =
        selectColumns()
            + fromAndWhere
            + buildOrderBy(criteria)
            + " "
            + sqlGenerator.limit(criteria.limit(), criteria.offset());
    final List<CreditApplicationData> pageItems =
        namedParameterJdbcTemplate.query(dataSql, parameters, new CreditApplicationRowMapper());
    return new Page<>(pageItems, Math.toIntExact(count));
  }

  private String buildFromAndWhere(
      final CreditApplicationSearchCriteria criteria, final Map<String, Object> parameters) {
    // A client can have several active addresses. Selecting the lowest matching relation id gives
    // one deterministic display address and keeps both the count and page free of duplicate loans.
    final StringBuilder sql =
        new StringBuilder(
            " FROM m_loan l"
                + " LEFT JOIN m_client c ON c.id = l.client_id"
                + " LEFT JOIN m_group g ON g.id = l.group_id"
                + " JOIN m_product_loan lp ON lp.id = l.product_id"
                + " JOIN m_office o ON o.id = COALESCE(c.office_id, g.office_id)"
                + " LEFT JOIN m_office transfer_o ON transfer_o.id = c.transfer_to_office_id"
                + " LEFT JOIN m_client_address ca ON ca.id = ("
                + " SELECT MIN(ca2.id) FROM m_client_address ca2"
                + " JOIN m_address a2 ON a2.id = ca2.address_id"
                + " WHERE ca2.client_id = c.id AND ca2.is_active = :activeAddress");

    if (criteria.stateProvinceId() != null) {
      sql.append(" AND a2.state_province_id = :stateProvinceId");
      parameters.put("stateProvinceId", criteria.stateProvinceId());
    }
    if (criteria.municipality() != null) {
      sql.append(" AND a2.county_district = :municipality");
      parameters.put("municipality", criteria.municipality());
    }
    sql.append(
        ") LEFT JOIN m_address a ON a.id = ca.address_id"
            + " WHERE (o.hierarchy LIKE :officeHierarchy"
            + " OR transfer_o.hierarchy LIKE :officeHierarchy)");

    addEquals(sql, parameters, "l.client_id", "clientId", criteria.clientId());
    addEquals(sql, parameters, "o.id", "officeId", criteria.officeId());
    addEquals(sql, parameters, "c.client_type_cv_id", "clientTypeId", criteria.clientTypeId());
    addEquals(sql, parameters, "l.product_id", "productId", criteria.productId());
    addEquals(sql, parameters, "l.loan_status_id", "status", criteria.status());
    addRange(
        sql, parameters, "l.submittedon_date", "submittedFrom", criteria.submittedFrom(), ">=");
    addRange(sql, parameters, "l.submittedon_date", "submittedTo", criteria.submittedTo(), "<=");
    addRange(
        sql, parameters, "l.principal_amount_proposed", "minAmount", criteria.minAmount(), ">=");
    addRange(
        sql, parameters, "l.principal_amount_proposed", "maxAmount", criteria.maxAmount(), "<=");
    addEquals(sql, parameters, "l.currency_code", "currencyCode", criteria.currencyCode());
    if (criteria.stateProvinceId() != null || criteria.municipality() != null) {
      sql.append(" AND ca.id IS NOT NULL");
    }
    return sql.toString();
  }

  private String selectColumns() {
    return "SELECT l.id AS loan_id, l.account_no, l.client_id, l.group_id,"
        + " COALESCE(c.display_name, g.display_name) AS client_name,"
        + " c.client_type_cv_id, o.id AS office_id, lp.id AS product_id,"
        + " lp.name AS product_name, l.currency_code,"
        + " l.principal_amount_proposed AS amount, l.loan_status_id,"
        + " l.submittedon_date, a.state_province_id, a.county_district AS municipality";
  }

  private String buildOrderBy(final CreditApplicationSearchCriteria criteria) {
    final String column = ORDER_BY_COLUMNS.get(criteria.orderBy());
    final String direction = criteria.sortOrder();
    if ("loanId".equals(criteria.orderBy())) {
      return " ORDER BY l.id " + direction;
    }
    return " ORDER BY " + column + " " + direction + ", l.id " + direction;
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

  private void addRange(
      final StringBuilder sql,
      final Map<String, Object> parameters,
      final String column,
      final String parameter,
      final Object value,
      final String operator) {
    if (value != null) {
      sql.append(" AND ")
          .append(column)
          .append(' ')
          .append(operator)
          .append(" :")
          .append(parameter);
      parameters.put(parameter, value);
    }
  }

  private static final class CreditApplicationRowMapper
      implements RowMapper<CreditApplicationData> {

    @Override
    public CreditApplicationData mapRow(final ResultSet resultSet, final int rowNumber)
        throws SQLException {
      final int statusId = resultSet.getInt("loan_status_id");
      final LoanStatus status = LoanStatus.fromInt(statusId);
      final LoanStatusEnumData statusData = LoanEnumerations.status(status);
      return new CreditApplicationData(
          resultSet.getLong("loan_id"),
          resultSet.getString("account_no"),
          nullableLong(resultSet, "client_id"),
          nullableLong(resultSet, "group_id"),
          resultSet.getString("client_name"),
          nullableLong(resultSet, "client_type_cv_id"),
          resultSet.getLong("office_id"),
          resultSet.getLong("product_id"),
          resultSet.getString("product_name"),
          resultSet.getString("currency_code"),
          resultSet.getBigDecimal("amount"),
          new CreditApplicationStatusData(
              statusData.getId(), statusData.getCode(), statusData.getValue()),
          resultSet.getDate("submittedon_date") == null
              ? null
              : resultSet.getDate("submittedon_date").toLocalDate(),
          nullableLong(resultSet, "state_province_id"),
          resultSet.getString("municipality"));
    }

    private static Long nullableLong(final ResultSet resultSet, final String column)
        throws SQLException {
      final long value = resultSet.getLong(column);
      return resultSet.wasNull() ? null : value;
    }
  }
}
