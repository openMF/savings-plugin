/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ConsolidatedIsrData.*;
import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists diagnostic evidence only. No native financial writer or event interceptor is injected.
 */
@Service
@RequiredArgsConstructor
public class ConsolidatedIsrService {
  private final JdbcTemplate jdbc;
  private final ConsolidatedIsrAccess access;
  private final ConsolidatedIsrBalanceService balances;
  private final ConsolidatedIsrRuleResolver rules;
  private final ConsolidatedIsrAuditService audit;

  @Transactional(transactionManager = "jdbcTransactionManager", readOnly = true)
  public Capabilities template() {
    access.authorize(ConsolidatedIsrAccess.ELIGIBILITY);
    return capabilities();
  }

  @Transactional(
      transactionManager = "jdbcTransactionManager",
      readOnly = true,
      isolation = Isolation.REPEATABLE_READ)
  public Preview preview(long client, Period period) {
    access.authorize(ConsolidatedIsrAccess.PREVIEW);
    return observe(client, period);
  }

  @Transactional(
      transactionManager = "jdbcTransactionManager",
      readOnly = true,
      isolation = Isolation.REPEATABLE_READ)
  public Eligibility eligibility(long client, Period period) {
    access.authorize(ConsolidatedIsrAccess.ELIGIBILITY);
    var result = observe(client, period);
    return new Eligibility(
        client,
        period,
        result.state(),
        result.profile(),
        result.sources().accounts().stream()
            .map(
                a ->
                    new AccountDecision(
                        a.accountId(),
                        a.productId(),
                        a.policyVersion(),
                        a.classification(),
                        a.structurallySupported(),
                        a.eligible(),
                        a.reasons()))
            .toList(),
        result.sources().complete(),
        result.blockers());
  }

  @Transactional(
      transactionManager = "jdbcTransactionManager",
      readOnly = true,
      isolation = Isolation.REPEATABLE_READ)
  public Sources accounts(long client, Period period) {
    access.authorize(ConsolidatedIsrAccess.READ);
    return observe(client, period).sources();
  }

  private Preview observe(long client, Period period) {
    ConsolidatedIsrValidation.period(period, ProductPolicyWorkflow.today());
    var customer = access.client(client, false);
    var profile =
        rules.profile(
            client,
            customer.get("legal_form_enum") == null
                ? null
                : ((Number) customer.get("legal_form_enum")).intValue());
    return ConsolidatedIsrCalculator.blocked(
        client,
        ProductPolicyWorkflow.today(),
        period,
        Instant.now(),
        profile,
        balances.capture(client, customer, period));
  }

  @Transactional(
      transactionManager = "jdbcTransactionManager",
      isolation = Isolation.REPEATABLE_READ)
  public Calculation save(long client, String json) {
    long actor = access.authorize(ConsolidatedIsrAccess.PREVIEW);
    access.client(client, false);
    var request =
        ConsolidatedIsrValidation.parse(
            json, Save.class, Set.of("idempotencyKey", "period", "supersedes", "reason"));
    String replay =
        audit.begin(request.idempotencyKey(), "ASSESSMENT", Long.toString(client), request, actor);
    if (replay != null) {
      var result = RatePropagationJson.JSON.fromJson(replay, Calculation.class);
      access.snapshot(result);
      return result;
    }
    access.client(
        client, true); // Serializes finalization/correction for this client in this tenant.
    ConsolidatedIsrValidation.text("reason", request.reason(), request.supersedes() != null, 1000);
    if (request.supersedes() != null) {
      var previous = find(client, request.supersedes());
      if (!previous.result().taxPeriod().equals(request.period()))
        throw invalid("period", "A correction must preserve the original period.");
      if (jdbc.queryForObject(
              "SELECT COUNT(*) FROM m_savings_isr_calculation WHERE tenant_identifier=?"
                  + " AND supersedes_id=?",
              Long.class,
              access.tenant(),
              request.supersedes())
          > 0) throw new ConsolidatedIsrAuditService.Conflict();
    }
    var result = observe(client, request.period());
    var calculation =
        new Calculation(
            request.idempotencyKey(),
            access.tenant(),
            client,
            actor,
            Instant.now(),
            request.supersedes(),
            request.reason(),
            result);
    jdbc.update(
        "INSERT INTO m_savings_isr_calculation(id,tenant_identifier,client_id,actor_id,"
            + "business_date,period_from,period_through,recorded_at,supersedes_id,correction_reason,"
            + "source_fingerprint,result_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        calculation.calculationId(),
        access.tenant(),
        client,
        actor,
        result.businessDate(),
        request.period().from(),
        request.period().through(),
        java.time.LocalDateTime.ofInstant(calculation.recordedAt(), java.time.ZoneOffset.UTC),
        request.supersedes(),
        request.reason(),
        result.sources().fingerprint(),
        RatePropagationJson.JSON.toJson(calculation));
    audit.event(
        client,
        calculation.calculationId(),
        "ASSESSMENT_SAVED",
        actor,
        java.util.Map.of(
            "state", result.state(), "sourceFingerprint", result.sources().fingerprint()));
    audit.finish(request.idempotencyKey(), calculation);
    return calculation;
  }

  @Transactional(transactionManager = "jdbcTransactionManager", readOnly = true)
  public Calculation get(long client, String id) {
    access.authorize(ConsolidatedIsrAccess.READ);
    access.client(client, false);
    return find(client, id);
  }

  Calculation find(long client, String id) {
    ConsolidatedIsrValidation.uuid(id);
    var rows =
        jdbc.queryForList(
            "SELECT result_json FROM m_savings_isr_calculation WHERE id=?"
                + " AND tenant_identifier=? AND client_id=?",
            String.class,
            id,
            access.tenant(),
            client);
    if (rows.size() != 1) throw new NotFound();
    var result = RatePropagationJson.JSON.fromJson(rows.getFirst(), Calculation.class);
    access.snapshot(result);
    return result;
  }

  @Transactional(transactionManager = "jdbcTransactionManager", readOnly = true)
  public List<HistoryEntry> history(long client, long after, int limit) {
    access.authorize(ConsolidatedIsrAccess.READ);
    access.client(client, false);
    ConsolidatedIsrValidation.size(after, limit);
    return jdbc.query(
        "SELECT sequence_no,result_json FROM m_savings_isr_calculation WHERE client_id=?"
            + " AND tenant_identifier=? AND sequence_no>? ORDER BY sequence_no LIMIT ?",
        (rs, n) -> {
          var result = RatePropagationJson.JSON.fromJson(rs.getString(2), Calculation.class);
          access.snapshot(result);
          return new HistoryEntry(rs.getLong(1), result);
        },
        client,
        access.tenant(),
        after,
        limit);
  }

  @Transactional(transactionManager = "jdbcTransactionManager", readOnly = true)
  public Object audit(long client, String id, long after, int limit) {
    access.authorize(ConsolidatedIsrAccess.AUDIT);
    access.client(client, false);
    find(client, id);
    return audit.history(client, id, after, limit);
  }

  @Transactional(
      transactionManager = "jdbcTransactionManager",
      readOnly = true,
      isolation = Isolation.REPEATABLE_READ)
  public Reconciliation reconciliation(long client, String id) {
    access.authorize(ConsolidatedIsrAccess.RECONCILE);
    access.client(client, false);
    return compare(client, find(client, id));
  }

  private Reconciliation compare(long client, Calculation previous) {
    var saved = previous.result().sources();
    var current = observe(client, previous.result().taxPeriod()).sources();
    boolean same =
        saved.complete() && current.complete() && saved.fingerprint().equals(current.fingerprint());
    return new Reconciliation(
        previous.calculationId(),
        same ? "UNCHANGED_BLOCKED" : "RECONCILIATION_REQUIRED",
        saved.fingerprint(),
        current.fingerprint(),
        Instant.now(),
        same ? BLOCKERS : List.of(Reason.SOURCE_DATA_CHANGED, Reason.ISR_POLICY_NOT_APPROVED),
        false,
        false);
  }

  @Transactional(
      transactionManager = "jdbcTransactionManager",
      isolation = Isolation.REPEATABLE_READ)
  public Reconciliation recordReconciliation(long client, String id, String key) {
    long actor = access.authorize(ConsolidatedIsrAccess.RECONCILE);
    access.client(client, false);
    var previous = find(client, id);
    String replay =
        audit.begin(
            key, "RECONCILIATION", id, java.util.Map.of("clientId", client, "id", id), actor);
    if (replay != null) return RatePropagationJson.JSON.fromJson(replay, Reconciliation.class);
    access.client(client, true);
    var result = compare(client, previous);
    audit.event(client, id, "RECONCILIATION_CHECKED", actor, result);
    audit.finish(key, result);
    return result;
  }

  static final class NotFound extends AbstractPlatformResourceNotFoundException {
    NotFound() {
      super("error.msg.consolidated.isr.not.found", "ISR assessment not found.");
    }
  }
}
