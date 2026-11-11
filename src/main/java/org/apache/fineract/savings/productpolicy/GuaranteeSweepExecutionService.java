/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.GuaranteeSweepData.*;
import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable rejected attempts. Deliberately has no native financial writer or policy enable switch.
 */
@Service
@RequiredArgsConstructor
public class GuaranteeSweepExecutionService {
  private final JdbcTemplate jdbc;
  private final GuaranteeSweepAccess access;
  private final GuaranteeEligibilityService eligibility;
  private final GuaranteeSweepAuditService audit;

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_GUARANTEE_SWEEP')")
  public Capabilities template() {
    access.authorize(GuaranteeSweepAccess.READ);
    return capabilities();
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_GUARANTEE_SWEEP')")
  @Transactional
  public Decision preview(long client, long loan, long source, BigDecimal amount) {
    long actor = access.authorize(GuaranteeSweepAccess.READ);
    if (source <= 0) throw invalid("savingsAccountId", "A positive source ID is required.");
    var decision = eligibility.evaluate(client, loan, source, amount, Operation.APPLY);
    audit.record(null, "ELIGIBILITY_EVALUATED", actor, decision);
    return decision;
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','APPLY_GUARANTEE_SWEEP')")
  @Transactional
  public Submission apply(long client, long loan, String json) {
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    long actor = access.authorize(GuaranteeSweepAccess.APPLY);
    return submit(
        client,
        loan,
        GuaranteeSweepValidation.parse(json, Operation.APPLY),
        Operation.APPLY,
        actor);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','RELEASE_GUARANTEE_SWEEP')")
  @Transactional
  public Submission release(long client, long loan, String json) {
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    long actor = access.authorize(GuaranteeSweepAccess.RELEASE);
    return submit(
        client,
        loan,
        GuaranteeSweepValidation.parse(json, Operation.RELEASE),
        Operation.RELEASE,
        actor);
  }

  private Submission submit(
      long client, long loan, Request request, Operation operation, long actor) {
    access.client(client);
    requireLoan(client, loan);
    String tenant = MonthlyFeeAssessmentService.tenant();
    String key = "MANUAL:" + request.idempotencyKey();
    String fingerprint = GuaranteeSweepValidation.fingerprint(client, loan, request, operation);
    // A committed duplicate returns only its original receipt. Never retry an unknown financial
    // outcome.
    var existing =
        jdbc.queryForList(
            "SELECT id,fingerprint FROM m_savings_guarantee_execution WHERE tenant_identifier=? AND"
                + " execution_key=?",
            tenant,
            key);
    if (!existing.isEmpty()) return duplicate(client, loan, fingerprint, existing.getFirst());
    // Re-evaluate server-owned financial state; there is no preview token or balance override.
    var decision =
        eligibility.evaluate(client, loan, request.savingsAccountId(), request.amount(), operation);
    String id = UUID.randomUUID().toString();
    int claimed =
        jdbc.update(
            "INSERT INTO"
                + " m_savings_guarantee_execution(id,tenant_identifier,execution_key,fingerprint,client_id,loan_id,"
                + "savings_account_id,operation,mode,status,business_date,policy_version,requested_amount,currency_code,actor_id,reason,reason_code,evaluation_json)"
                + " VALUES (?,?,?,?,?,?,?,?,'MANUAL','BLOCKED_PENDING_POLICY',?,?,?,?,?,?,?,?) ON"
                + " CONFLICT (tenant_identifier,execution_key) DO NOTHING",
            id,
            tenant,
            key,
            fingerprint,
            client,
            loan,
            request.savingsAccountId(),
            operation.name(),
            decision.businessDate(),
            decision.policyVersion(),
            request.amount(),
            decision.currencyCode(),
            actor,
            request.reason(),
            "BLOCKED_PENDING_POLICY",
            RatePropagationJson.JSON.toJson(decision));
    if (claimed == 0) {
      var prior =
          jdbc.queryForList(
                  "SELECT id,fingerprint FROM m_savings_guarantee_execution WHERE"
                      + " tenant_identifier=? AND execution_key=?",
                  tenant,
                  key)
              .getFirst();
      return duplicate(client, loan, fingerprint, prior);
    }
    audit.record(
        id,
        operation == Operation.APPLY ? "MANUAL_APPLICATION_BLOCKED" : "SETTLEMENT_RELEASE_BLOCKED",
        actor,
        decision);
    return new Submission(find(client, loan, id), false);
  }

  private Submission duplicate(
      long client, long loan, String fingerprint, Map<String, Object> prior) {
    if (!fingerprint.equals(prior.get("fingerprint"))) throw new Conflict();
    return new Submission(find(client, loan, (String) prior.get("id")), true);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_GUARANTEE_SWEEP')")
  @Transactional(readOnly = true)
  public Receipt get(long client, long loan, String id) {
    access.authorize(GuaranteeSweepAccess.READ);
    access.client(client);
    requireLoan(client, loan);
    return find(client, loan, id);
  }

  Receipt find(long client, long loan, String id) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM m_savings_guarantee_execution WHERE id=? AND tenant_identifier=? AND"
                + " client_id=? AND loan_id=?",
            id,
            MonthlyFeeAssessmentService.tenant(),
            client,
            loan);
    if (rows.size() != 1)
      throw invalid("executionId", "Execution is unavailable for this client and loan.");
    var r = rows.getFirst();
    return new Receipt(
        id,
        (String) r.get("tenant_identifier"),
        client,
        loan,
        r.get("savings_account_id") == null
            ? 0
            : ((Number) r.get("savings_account_id")).longValue(),
        Operation.valueOf((String) r.get("operation")),
        (String) r.get("mode"),
        State.valueOf((String) r.get("status")),
        ((java.sql.Date) r.get("business_date")).toLocalDate(),
        ProductPolicyWorkflow.number(r.get("policy_version")),
        (BigDecimal) r.get("requested_amount"),
        (BigDecimal) r.get("applied_amount"),
        (String) r.get("currency_code"),
        ((Number) r.get("actor_id")).longValue(),
        (String) r.get("reason_code"),
        string(r.get("native_savings_transaction_id")),
        string(r.get("native_loan_transaction_id")),
        string(r.get("native_transfer_id")),
        RatePropagationJson.JSON.fromJson((String) r.get("evaluation_json"), Decision.class));
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_GUARANTEE_SWEEP')")
  @Transactional(readOnly = true)
  public List<?> history(long client, long loan, long after, int limit, String kind) {
    access.authorize(GuaranteeSweepAccess.READ);
    access.client(client);
    requireLoan(client, loan);
    GuaranteeSweepAccess.page(after, limit);
    String table =
        switch (kind) {
          case "audit" -> "m_savings_guarantee_audit";
          case "allocations" -> "m_savings_guarantee_allocation";
          default -> throw invalid("kind", "Unsupported history kind.");
        };
    return jdbc.query(
        "SELECT * FROM "
            + table
            + " WHERE tenant_identifier=? AND client_id=? AND loan_id=? AND id>? ORDER BY id LIMIT"
            + " ?",
        (rs, n) ->
            kind.equals("audit")
                ? new Audit(
                    rs.getLong("id"),
                    rs.getString("execution_id"),
                    rs.getLong("actor_id"),
                    rs.getDate("business_date").toLocalDate(),
                    rs.getString("event"),
                    rs.getString("reason_code"),
                    RatePropagationJson.JSON.fromJson(
                        rs.getString("evaluation_json"), Decision.class),
                    RatePropagationJson.JSON.fromJson(
                        rs.getString("reconciliation_json"), Reconciliation.class),
                    rs.getTimestamp("recorded_at").toLocalDateTime())
                : new Allocation(
                    rs.getLong("id"),
                    rs.getLong("savings_account_id"),
                    rs.getLong("policy_version"),
                    rs.getString("currency_code"),
                    rs.getString("status"),
                    rs.getBigDecimal("proposed_amount"),
                    rs.getBigDecimal("reserved_amount"),
                    rs.getBigDecimal("consumed_amount"),
                    rs.getBigDecimal("released_amount")),
        MonthlyFeeAssessmentService.tenant(),
        client,
        loan,
        after,
        limit);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_GUARANTEE_SWEEP')")
  @Transactional(readOnly = true)
  public List<LoanSummary> loans(long client, long after, int limit) {
    access.authorize(GuaranteeSweepAccess.READ);
    access.client(client);
    GuaranteeSweepAccess.page(after, limit);
    return jdbc.query(
        "SELECT id,loan_status_id,currency_code FROM m_loan WHERE client_id=? AND group_id IS NULL"
            + " AND id>? ORDER BY id LIMIT ?",
        (rs, n) ->
            new LoanSummary(
                rs.getLong("id"), rs.getInt("loan_status_id"), rs.getString("currency_code")),
        client,
        after,
        limit);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_GUARANTEE_SWEEP')")
  @Transactional(readOnly = true)
  public List<Source> sources(long client, long after, int limit) {
    access.authorize(GuaranteeSweepAccess.READ);
    access.client(client);
    GuaranteeSweepAccess.page(after, limit);
    return jdbc.query(
        "SELECT a.id,a.status_enum,b.purpose,b.version FROM m_savings_account a JOIN"
            + " m_savings_account_policy b ON b.savings_account_id=a.id JOIN m_client c ON"
            + " c.id=a.client_id JOIN m_office o ON o.id=c.office_id WHERE a.client_id=? AND"
            + " a.group_id IS NULL AND b.purpose IN ('MAIN','GUARANTEED') AND o.hierarchy LIKE ?"
            + " AND a.id>? ORDER BY a.id LIMIT ?",
        (rs, n) ->
            new Source(
                rs.getLong("id"),
                rs.getString("purpose"),
                rs.getLong("version"),
                rs.getInt("status_enum")),
        client,
        access.hierarchy() + "%",
        after,
        limit);
  }

  void requireLoan(long client, long loan) {
    if (loan <= 0
        || jdbc.queryForObject(
                "SELECT COUNT(*) FROM m_loan WHERE id=? AND client_id=? AND group_id IS NULL",
                Long.class,
                loan,
                client)
            != 1) throw invalid("loanId", "Loan is unavailable for this client.");
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }

  public static class Conflict extends RuntimeException {
    public Conflict() {
      super("Idempotency key was already used for a different request.");
    }
  }
}
