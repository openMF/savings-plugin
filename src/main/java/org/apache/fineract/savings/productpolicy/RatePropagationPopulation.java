/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.RatePropagationData.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Bounded keyset reads from authoritative account/product and BE-02 account bindings. */
@Component
@RequiredArgsConstructor
public class RatePropagationPopulation {
  private final JdbcTemplate jdbc;
  private static final String SELECT =
      "SELECT"
          + " a.id,a.product_id,a.status_enum,a.deposit_type_enum,a.sub_status_enum,a.created_on_utc,a.activatedon_date,"
          + RatePropagationEligibility.ACCOUNT_COLUMNS.stream()
              .map(k -> "a." + k)
              .collect(java.util.stream.Collectors.joining(","))
          + ",p.version AS bound_version,v.status AS policy_status,v.policy_json,v.core_json,EXISTS"
          + " (SELECT 1 FROM m_savings_rate_account i JOIN m_savings_rate_batch b ON"
          + " b.id=i.batch_id WHERE i.savings_account_id=a.id AND b.id<>? AND b.tenant_id=? AND"
          + " b.status NOT IN ('CANCELLED','REJECTED') AND i.eligibility<>'EXCLUDED') AS"
          + " conflicting_plan FROM m_savings_account a LEFT JOIN m_savings_account_policy p ON"
          + " p.savings_account_id=a.id AND p.savings_product_id=a.product_id LEFT JOIN"
          + " m_savings_product_policy_version v ON v.savings_product_id=p.savings_product_id AND"
          + " v.version=p.version ";

  long cutoff(long product) {
    return jdbc.queryForObject(
        "SELECT COALESCE(MAX(id),0) FROM m_savings_account WHERE product_id=?",
        Long.class,
        product);
  }

  List<AccountSnapshot> page(
      long product, long after, long cutoff, LocalDateTime at, String batch, int limit) {
    // Null creation metadata is deliberately included so it can be reported as blocked.
    return jdbc.query(
        SELECT
            + "WHERE a.product_id=? AND a.id>? AND a.id<=? AND (a.created_on_utc<=? OR"
            + " a.created_on_utc IS NULL) ORDER BY a.id LIMIT ?",
        (rs, n) -> snapshot(rs),
        batch,
        org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil.getTenant()
            .getTenantIdentifier(),
        product,
        after,
        cutoff,
        at.atOffset(java.time.ZoneOffset.UTC),
        limit);
  }

  List<AccountSnapshot> forWorkItems(String batch, long after, int limit) {
    return jdbc.query(
        SELECT
            + "JOIN m_savings_rate_account w ON w.savings_account_id=a.id WHERE w.batch_id=? AND"
            + " w.outcome='PENDING' AND a.id>? ORDER BY a.id LIMIT ?",
        (rs, n) -> snapshot(rs),
        batch,
        org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil.getTenant()
            .getTenantIdentifier(),
        batch,
        after,
        limit);
  }

  private static AccountSnapshot snapshot(ResultSet rs) throws SQLException {
    var actual = new JsonObject();
    for (String key : RatePropagationEligibility.ACCOUNT_COLUMNS)
      actual.add(key, ProductPolicyWorkflow.JSON.toJsonTree(rs.getObject(key)));
    var date = rs.getDate("activatedon_date");
    var created =
        rs.getTimestamp(
            "created_on_utc",
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
    return new AccountSnapshot(
        rs.getLong("id"),
        rs.getLong("product_id"),
        rs.getInt("status_enum"),
        rs.getInt("deposit_type_enum"),
        rs.getInt("sub_status_enum"),
        created == null
            ? null
            : created.toInstant().atOffset(java.time.ZoneOffset.UTC).toLocalDateTime(),
        date == null ? null : date.toLocalDate(),
        rs.getBigDecimal("nominal_annual_interest_rate"),
        ProductPolicyWorkflow.number(rs.getObject("bound_version")),
        rs.getString("policy_status"),
        rs.getString("policy_json") == null
            ? null
            : ProductPolicyWorkflow.JSON.fromJson(
                rs.getString("policy_json"), ProductPolicyData.Policy.class),
        rs.getString("core_json") == null
            ? null
            : JsonParser.parseString(rs.getString("core_json")).getAsJsonObject(),
        actual,
        rs.getBoolean("conflicting_plan"));
  }

  static LocalDateTime time(ResultSet rs, String column) throws SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toLocalDateTime();
  }
}
