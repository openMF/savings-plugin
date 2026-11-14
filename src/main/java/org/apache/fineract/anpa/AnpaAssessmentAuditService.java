/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only assessment is itself the audit event, with actor, request, source and mapping hashes.
 */
@Service
@RequiredArgsConstructor
@Transactional(
    transactionManager = "jdbcTransactionManager",
    readOnly = true,
    isolation = Isolation.REPEATABLE_READ)
public class AnpaAssessmentAuditService {
  private final JdbcTemplate jdbc;
  private final AnpaAccountingAccess access;
  private final AnpaAccountingEvidenceService evidence;
  private final AnpaAccountingService accounting;

  @Transactional(
      transactionManager = "jdbcTransactionManager",
      isolation = Isolation.REPEATABLE_READ)
  public Assessment save(long loanId, String json) {
    long actor = access.authorize(AnpaAccountingAccess.CREATE);
    access.authorize(AnpaAccountingAccess.PREVIEW);
    var loan = evidence.loan(loanId);
    var request = AnpaSupport.request(json);
    String hash = AnpaSupport.hash(List.of(loanId, actor, request));
    var existing =
        jdbc.queryForList(
            "SELECT id,loan_id,request_hash FROM m_anpa_assessment WHERE tenant_identifier=? AND idempotency_key=?",
            access.tenant(),
            request.idempotencyKey());
    if (!existing.isEmpty()) {
      var row = existing.getFirst();
      if (!hash.equals(row.get("request_hash"))
          || ((Number) row.get("loan_id")).longValue() != loanId) throw new Conflict();
      return load(loanId, (String) row.get("id"));
    }
    if (request.supersedesAssessmentId() != null) load(loanId, request.supersedesAssessmentId());
    var result = accounting.calculate(loanId, request.asOf());
    String id = UUID.randomUUID().toString();
    Instant now = Instant.now();
    int inserted =
        jdbc.update(
            """
        INSERT INTO m_anpa_assessment(id,tenant_identifier,loan_id,product_id,office_id,actor_id,
          business_date,as_of_date,recorded_at,idempotency_key,request_hash,supersedes_id,
          correction_reason,source_fingerprint,mapping_fingerprint,result_json)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT (tenant_identifier,idempotency_key) DO NOTHING
        """,
            id,
            access.tenant(),
            loanId,
            loan.productId(),
            loan.officeId(),
            actor,
            DateUtils.getBusinessLocalDate(),
            result.asOf(),
            LocalDateTime.ofInstant(now, ZoneOffset.UTC),
            request.idempotencyKey(),
            hash,
            request.supersedesAssessmentId(),
            request.correctionReason(),
            result.sourceFingerprint(),
            result.mappingFingerprint(),
            AnpaSupport.JSON.toJson(result));
    if (inserted != 1) throw new Conflict();
    return load(loanId, id);
  }

  public List<AssessmentSummary> list(long loanId, long after, int limit) {
    access.authorize(AnpaAccountingAccess.AUDIT);
    evidence.loan(loanId);
    AnpaSupport.page(after, limit);
    return jdbc.query(
        """
        SELECT a.sequence_no,a.id,a.actor_id,a.as_of_date,a.recorded_at,a.source_fingerprint
        FROM m_anpa_assessment a JOIN m_office o ON o.id=a.office_id
        WHERE a.tenant_identifier=? AND a.loan_id=? AND o.hierarchy LIKE ? AND a.sequence_no>?
        ORDER BY a.sequence_no LIMIT ?
        """,
        (r, n) ->
            new AssessmentSummary(
                r.getLong(1),
                r.getString(2),
                r.getLong(3),
                r.getDate(4).toLocalDate(),
                r.getObject(5, LocalDateTime.class).toInstant(ZoneOffset.UTC),
                r.getString(6)),
        access.tenant(),
        loanId,
        access.hierarchy(),
        after,
        limit);
  }

  public Assessment get(long loanId, String id) {
    access.authorize(AnpaAccountingAccess.AUDIT);
    evidence.loan(loanId);
    return load(loanId, id);
  }

  private Assessment load(long loanId, String id) {
    AnpaSupport.uuid(id);
    var values =
        jdbc.query(
            """
        SELECT a.* FROM m_anpa_assessment a JOIN m_office o ON o.id=a.office_id
        WHERE a.tenant_identifier=? AND a.loan_id=? AND a.id=? AND o.hierarchy LIKE ?
        """,
            (r, n) ->
                new Assessment(
                    r.getLong("sequence_no"),
                    r.getString("id"),
                    r.getLong("loan_id"),
                    r.getLong("product_id"),
                    r.getLong("office_id"),
                    r.getLong("actor_id"),
                    r.getDate("business_date").toLocalDate(),
                    r.getObject("recorded_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                    r.getString("supersedes_id"),
                    r.getString("correction_reason"),
                    AnpaSupport.JSON.fromJson(r.getString("result_json"), Preview.class)),
            access.tenant(),
            loanId,
            id,
            access.hierarchy());
    if (values.size() != 1)
      throw new jakarta.ws.rs.NotFoundException("Assessment not found in loan and office scope.");
    return values.getFirst();
  }

  static final class Conflict extends RuntimeException {}
}
