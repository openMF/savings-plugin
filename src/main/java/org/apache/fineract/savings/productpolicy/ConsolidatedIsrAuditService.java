/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Database arbitration and append-only audit. Call only inside the caller's transaction. */
@Component
@RequiredArgsConstructor
public class ConsolidatedIsrAuditService {
  private final JdbcTemplate jdbc;
  private final ConsolidatedIsrAccess access;

  String begin(String key, String kind, String scope, Object request, long actor) {
    ConsolidatedIsrValidation.uuid(key);
    String hash = ConsolidatedIsrValidation.hash(request);
    jdbc.update(
        "INSERT INTO m_savings_isr_request(tenant_identifier,idempotency_key,kind,scope_id,request_hash,actor_id)"
            + " VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_identifier,idempotency_key) DO NOTHING",
        access.tenant(),
        key,
        kind,
        scope,
        hash,
        actor);
    var row =
        jdbc.queryForMap(
            "SELECT kind,scope_id,request_hash,response_json FROM m_savings_isr_request"
                + " WHERE tenant_identifier=? AND idempotency_key=? FOR UPDATE",
            access.tenant(),
            key);
    if (!kind.equals(row.get("kind"))
        || !scope.equals(row.get("scope_id"))
        || !hash.equals(row.get("request_hash"))) throw new Conflict();
    return (String) row.get("response_json");
  }

  void finish(String key, Object response) {
    if (jdbc.update(
            "UPDATE m_savings_isr_request SET response_json=? WHERE tenant_identifier=?"
                + " AND idempotency_key=? AND response_json IS NULL",
            RatePropagationJson.JSON.toJson(response),
            access.tenant(),
            key)
        != 1) throw new Conflict();
  }

  void event(Long client, String subject, String action, long actor, Object details) {
    jdbc.update(
        "INSERT INTO m_savings_isr_audit(tenant_identifier,client_id,subject_id,action,actor_id,"
            + "business_date,recorded_at,details_json) VALUES (?,?,?,?,?,?,?,?)",
        access.tenant(),
        client,
        subject,
        action,
        actor,
        ProductPolicyWorkflow.today(),
        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
        RatePropagationJson.JSON.toJson(details));
  }

  List<ConsolidatedIsrData.AuditEntry> history(long client, String id, long after, int limit) {
    ConsolidatedIsrValidation.size(after, limit);
    return jdbc.query(
        "SELECT id,action,actor_id,business_date,recorded_at,details_json"
            + " FROM m_savings_isr_audit WHERE tenant_identifier=? AND client_id=? AND subject_id=?"
            + " AND id>? ORDER BY id LIMIT ?",
        (rs, n) ->
            new ConsolidatedIsrData.AuditEntry(
                rs.getLong(1),
                rs.getString(2),
                rs.getLong(3),
                rs.getDate(4).toLocalDate(),
                rs.getObject(5, java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC),
                rs.getString(6)),
        access.tenant(),
        client,
        id,
        after,
        limit);
  }

  static final class Conflict extends RuntimeException {}
}
