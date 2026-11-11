/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.GuaranteeSweepData.*;
import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounded, durable diagnostics only. Calendar labels do not establish a financial month-end rule.
 */
@Service
@RequiredArgsConstructor
public class GuaranteeSweepDiagnosticService {
  private final JdbcTemplate jdbc;
  private final GuaranteeSweepAccess access;
  private final GuaranteeEligibilityService eligibility;
  private final GuaranteeSweepAuditService audit;

  private static final String POPULATION =
      " FROM m_loan l JOIN m_client c ON c.id=l.client_id JOIN m_office o ON o.id=c.office_id WHERE"
          + " l.group_id IS NULL AND o.hierarchy LIKE ? AND EXISTS (SELECT 1 FROM m_savings_account"
          + " a JOIN m_savings_account_policy b ON b.savings_account_id=a.id WHERE"
          + " a.client_id=l.client_id AND b.purpose IN ('MAIN','GUARANTEED'))";

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','ASSESS_GUARANTEE_SWEEP')")
  @Transactional
  public Run create(String period) {
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    long actor = access.authorize(GuaranteeSweepAccess.PROCESS);
    var month = MonthlyFeePeriod.parse(period);
    if (month.isAfter(YearMonth.from(ProductPolicyWorkflow.today())))
      throw invalid("period", "Future diagnostic periods are unsupported.");
    String tenant = MonthlyFeeAssessmentService.tenant();
    long cutoff =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(l.id),0)" + POPULATION, Long.class, access.hierarchy() + "%");
    jdbc.update(
        "INSERT INTO"
            + " m_savings_guarantee_run(id,tenant_identifier,period,business_date,status,office_hierarchy,cutoff_loan_id,after_loan_id,created_by,created_at)"
            + " VALUES (?,?,?,?,'PENDING',?,?,0,?,CURRENT_TIMESTAMP) ON CONFLICT"
            + " (tenant_identifier,period,office_hierarchy) DO NOTHING",
        UUID.randomUUID().toString(),
        tenant,
        period,
        ProductPolicyWorkflow.today(),
        access.hierarchy(),
        cutoff,
        actor);
    String id =
        jdbc.queryForObject(
            "SELECT id FROM m_savings_guarantee_run WHERE tenant_identifier=? AND period=? AND"
                + " office_hierarchy=?",
            String.class,
            tenant,
            period,
            access.hierarchy());
    return find(id, false);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','ASSESS_GUARANTEE_SWEEP')")
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Run processChunk(String id, int limit) {
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    long actor = access.authorize(GuaranteeSweepAccess.PROCESS);
    GuaranteeSweepAccess.page(0, limit);
    var run = find(id, true); // Database row lock held until the entire checkpoint/audit commits.
    if (ProductPolicyWorkflow.today().isBefore(run.businessDate()))
      throw invalid("businessDate", "Business date moved backwards.");
    if (run.state() != State.PENDING && run.state() != State.PROCESSING) return run;
    var rows =
        jdbc.queryForList(
            "SELECT l.id,l.client_id"
                + POPULATION
                + " AND l.id>? AND l.id<=? ORDER BY l.id LIMIT ?",
            access.hierarchy() + "%",
            run.afterLoanId(),
            run.cutoffLoanId(),
            limit);
    long after = run.afterLoanId();
    for (var row : rows) {
      if (Thread.currentThread().isInterrupted())
        throw new IllegalStateException("Interrupted diagnostic checkpoint; retry after rollback.");
      long loan = ((Number) row.get("id")).longValue();
      long client = ((Number) row.get("client_id")).longValue();
      // A customer can have several loans. No implicit source selection or loan priority.
      String key = "DIAGNOSTIC:" + run.period() + ":" + loan;
      var decision = eligibility.evaluate(client, loan, 0, null, Operation.APPLY);
      String execution = UUID.randomUUID().toString();
      int inserted =
          jdbc.update(
              "INSERT INTO"
                  + " m_savings_guarantee_execution(id,tenant_identifier,execution_key,fingerprint,client_id,loan_id,"
                  + "operation,mode,status,business_date,currency_code,actor_id,reason,reason_code,evaluation_json)"
                  + " VALUES"
                  + " (?,?,?,?,?,?,'APPLY','DIAGNOSTIC','BLOCKED_PENDING_POLICY',?,?,?,?,?,?) ON"
                  + " CONFLICT (tenant_identifier,execution_key) DO NOTHING",
              execution,
              run.tenant(),
              key,
              "DIAGNOSTIC",
              client,
              loan,
              decision.businessDate(),
              decision.currencyCode(),
              actor,
              "Explicit diagnostic evaluation",
              "BLOCKED_PENDING_POLICY",
              RatePropagationJson.JSON.toJson(decision));
      if (inserted == 1) audit.record(execution, "SCHEDULED_EVALUATION_BLOCKED", actor, decision);
      var existing =
          jdbc.queryForMap(
              "SELECT id,status,client_id FROM m_savings_guarantee_execution WHERE"
                  + " tenant_identifier=? AND execution_key=?",
              run.tenant(),
              key);
      if (((Number) existing.get("client_id")).longValue() != client) {
        checkpoint(id, after, State.RECONCILIATION_REQUIRED);
        return find(id, false);
      }
      jdbc.update(
          "INSERT INTO m_savings_guarantee_run_item(tenant_identifier,run_id,loan_id,execution_id)"
              + " VALUES (?,?,?,?) ON CONFLICT (tenant_identifier,run_id,loan_id) DO NOTHING",
          run.tenant(),
          id,
          loan,
          existing.get("id"));
      after = loan;
      if (!"BLOCKED_PENDING_POLICY".equals(existing.get("status"))) {
        checkpoint(id, after, State.RECONCILIATION_REQUIRED);
        return find(id, false); // Never automatically retry an uncertain financial operation.
      }
    }
    boolean more =
        !jdbc.queryForList(
                "SELECT l.id" + POPULATION + " AND l.id>? AND l.id<=? ORDER BY l.id LIMIT 1",
                Long.class,
                access.hierarchy() + "%",
                after,
                run.cutoffLoanId())
            .isEmpty();
    checkpoint(
        id,
        after,
        more
            ? State.PROCESSING
            : (run.cutoffLoanId() == 0 ? State.SKIPPED : State.BLOCKED_PENDING_POLICY));
    return find(id, false);
  }

  private void checkpoint(String id, long after, State state) {
    jdbc.update(
        "UPDATE m_savings_guarantee_run SET after_loan_id=?,status=? WHERE id=? AND"
            + " tenant_identifier=?",
        after,
        state.name(),
        id,
        MonthlyFeeAssessmentService.tenant());
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','ASSESS_GUARANTEE_SWEEP')")
  @Transactional(readOnly = true)
  public Run get(String id) {
    access.authorize(GuaranteeSweepAccess.PROCESS);
    return find(id, false);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','ASSESS_GUARANTEE_SWEEP')")
  @Transactional(readOnly = true)
  public List<RunOutcome> outcomes(String id, long after, int limit) {
    access.authorize(GuaranteeSweepAccess.PROCESS);
    find(id, false);
    GuaranteeSweepAccess.page(after, limit);
    // Recheck current client scope so reassigned clients cannot leak through an old run.
    return jdbc.query(
        "SELECT i.loan_id,i.execution_id,e.status,e.reason_code,e.evaluation_json FROM"
            + " m_savings_guarantee_run_item i JOIN m_savings_guarantee_execution e ON"
            + " e.id=i.execution_id AND e.tenant_identifier=i.tenant_identifier JOIN m_loan l ON"
            + " l.id=i.loan_id AND e.client_id=l.client_id JOIN m_client c ON c.id=l.client_id JOIN"
            + " m_office o ON o.id=c.office_id WHERE i.run_id=? AND i.tenant_identifier=? AND"
            + " i.loan_id>? AND o.hierarchy LIKE ? ORDER BY i.loan_id LIMIT ?",
        (rs, n) ->
            new RunOutcome(
                rs.getLong("loan_id"),
                rs.getString("execution_id"),
                State.valueOf(rs.getString("status")),
                rs.getString("reason_code"),
                RatePropagationJson.JSON.fromJson(rs.getString("evaluation_json"), Decision.class)),
        id,
        MonthlyFeeAssessmentService.tenant(),
        after,
        access.hierarchy() + "%",
        limit);
  }

  private Run find(String id, boolean lock) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM m_savings_guarantee_run WHERE id=? AND tenant_identifier=?"
                + (lock ? " FOR UPDATE" : ""),
            id,
            MonthlyFeeAssessmentService.tenant());
    if (rows.size() != 1 || !access.hierarchy().equals(rows.getFirst().get("office_hierarchy")))
      throw new AccessDeniedException(
          "Diagnostic run is unavailable for this tenant and office scope.");
    var r = rows.getFirst();
    long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_guarantee_run_item WHERE run_id=? AND"
                + " tenant_identifier=?",
            Long.class,
            id,
            MonthlyFeeAssessmentService.tenant());
    return new Run(
        id,
        (String) r.get("tenant_identifier"),
        (String) r.get("period"),
        ((java.sql.Date) r.get("business_date")).toLocalDate(),
        State.valueOf((String) r.get("status")),
        (String) r.get("office_hierarchy"),
        ((Number) r.get("cutoff_loan_id")).longValue(),
        ((Number) r.get("after_loan_id")).longValue(),
        count);
  }
}
