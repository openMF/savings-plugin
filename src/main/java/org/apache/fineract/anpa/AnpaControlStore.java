/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the Mozilla Public License, v. 2.0. If a copy of the MPL
 * was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tenant-routed, append-only plugin events; callers own authorization and transaction boundaries.
 */
@Component
@RequiredArgsConstructor
class AnpaControlStore {
  private final JdbcTemplate jdbc;
  private final AnpaAccountingAccess access;

  ControlRecord replay(long loanId, String key, String hash) {
    var rows =
        jdbc.queryForList(
            "SELECT id,loan_id,request_hash FROM m_anpa_control_record WHERE tenant_identifier=?"
                + " AND idempotency_key=?",
            access.tenant(),
            key);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    if (((Number) row.get("loan_id")).longValue() != loanId
        || !hash.equals(row.get("request_hash"))) throw new AnpaAssessmentAuditService.Conflict();
    return get(loanId, (String) row.get("id"));
  }

  ControlRecord append(
      Loan loan,
      long actor,
      String kind,
      String key,
      String hash,
      String parentId,
      Integer policyVersion,
      String approvalFor,
      String supersedesId,
      String fingerprint,
      Object request,
      Object result) {
    String id = UUID.randomUUID().toString();
    int count =
        jdbc.update(
            """
            INSERT INTO m_anpa_control_record(id,tenant_identifier,kind,loan_id,product_id,office_id,
              actor_id,business_date,recorded_at,idempotency_key,request_hash,parent_id,
              policy_version,approval_for,supersedes_id,source_fingerprint,request_json,result_json)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (tenant_identifier,idempotency_key) DO NOTHING
            """,
            id,
            access.tenant(),
            kind,
            loan.loanId(),
            loan.productId(),
            loan.officeId(),
            actor,
            loan.businessDate(),
            LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC),
            key,
            hash,
            parentId,
            policyVersion,
            approvalFor,
            supersedesId,
            fingerprint,
            AnpaSupport.JSON.toJson(request),
            AnpaSupport.JSON.toJson(result));
    if (count != 1) throw new AnpaAssessmentAuditService.Conflict();
    return get(loan.loanId(), id);
  }

  ControlRecord get(long loanId, String id) {
    AnpaSupport.uuid(id);
    var rows =
        jdbc.query(
            """
            SELECT r.* FROM m_anpa_control_record r JOIN m_office o ON o.id=r.office_id
            WHERE r.tenant_identifier=? AND r.loan_id=? AND r.id=? AND o.hierarchy LIKE ?
            """,
            (r, n) -> map(r),
            access.tenant(),
            loanId,
            id,
            access.hierarchy());
    if (rows.size() != 1)
      throw new jakarta.ws.rs.NotFoundException(
          "Control record not found in loan and office scope.");
    return rows.getFirst();
  }

  List<ControlRecord> list(long loanId, String kind, long after, int limit) {
    AnpaSupport.page(after, limit);
    return jdbc.query(
        """
        SELECT r.* FROM m_anpa_control_record r JOIN m_office o ON o.id=r.office_id
        WHERE r.tenant_identifier=? AND r.loan_id=? AND r.kind=?
          AND o.hierarchy LIKE ? AND r.sequence_no>? ORDER BY r.sequence_no LIMIT ?
        """,
        (r, n) -> map(r),
        access.tenant(),
        loanId,
        kind,
        access.hierarchy(),
        after,
        limit);
  }

  List<ControlRecord> approvedPolicies(Loan loan) {
    return jdbc.query(
        """
        SELECT p.* FROM m_anpa_control_record p
        JOIN m_anpa_control_record a ON a.approval_for=p.id AND a.tenant_identifier=p.tenant_identifier
        WHERE p.tenant_identifier=? AND p.product_id=? AND p.office_id=? AND p.kind='POLICY_DRAFT'
        ORDER BY p.policy_version LIMIT 201
        """,
        (r, n) -> map(r),
        access.tenant(),
        loan.productId(),
        loan.officeId());
  }

  PolicyApproval approval(String policyId) {
    var rows =
        jdbc.query(
            "SELECT result_json FROM m_anpa_control_record WHERE tenant_identifier=? AND"
                + " approval_for=? AND kind='POLICY_APPROVAL'",
            (r, n) -> AnpaSupport.JSON.fromJson(r.getString(1), PolicyApproval.class),
            access.tenant(),
            policyId);
    return rows.size() == 1 ? rows.getFirst() : null;
  }

  private static ControlRecord map(java.sql.ResultSet r) throws java.sql.SQLException {
    return new ControlRecord(
        r.getLong("sequence_no"),
        r.getString("id"),
        r.getString("kind"),
        r.getLong("loan_id"),
        r.getLong("product_id"),
        r.getLong("office_id"),
        r.getLong("actor_id"),
        r.getDate("business_date").toLocalDate(),
        r.getObject("recorded_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
        r.getString("parent_id"),
        r.getString("source_fingerprint"),
        com.google.gson.JsonParser.parseString(r.getString("request_json")),
        com.google.gson.JsonParser.parseString(r.getString("result_json")));
  }
}
