/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.MonthlyFeeData.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable, bounded diagnostic processing. This service has no native financial writer dependency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonthlyFeeAssessmentService {
  public static final String READ = "READ_SAVINGS_MONTHLY_FEE";
  public static final String ASSESS = "ASSESS_SAVINGS_MONTHLY_FEE";
  private final ProductPolicyWorkflow workflow;
  private final JdbcTemplate jdbc;
  private final MonthlyFeeEligibility eligibility;

  @Transactional(readOnly = true)
  public Capabilities template() {
    workflow.authorize(READ);
    return capabilities();
  }

  @Transactional
  public Run create(String period) {
    long actor = workflow.authorize(ASSESS);
    var month = MonthlyFeePeriod.forCreation(period, businessDate());
    String tenant = tenant();
    // Keyset high-water mark bounds the population. Legacy products are never enrolled here.
    long cutoff =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(a.id),0) FROM m_savings_account a WHERE EXISTS (SELECT 1 FROM"
                + " m_savings_product_policy p WHERE p.savings_product_id=a.product_id) OR EXISTS"
                + " (SELECT 1 FROM m_savings_account_policy b WHERE b.savings_account_id=a.id)",
            Long.class);
    jdbc.update(
        "INSERT INTO"
            + " m_savings_monthly_fee_run(id,tenant_identifier,period,business_date,status,cutoff_account_id,after_account_id,created_by,created_at)"
            + " VALUES (?,?,?,?,'READY',?,0,?,CURRENT_TIMESTAMP) ON CONFLICT"
            + " (tenant_identifier,period) DO NOTHING",
        UUID.randomUUID().toString(),
        tenant,
        month.toString(),
        businessDate(),
        cutoff,
        actor);
    return find(
        jdbc.queryForObject(
            "SELECT id FROM m_savings_monthly_fee_run WHERE tenant_identifier=? AND period=?",
            String.class,
            tenant,
            period),
        false);
  }

  /** One committed checkpoint per invocation, including every assessment in this chunk. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Run processChunk(String id, int limit) {
    long actor = workflow.authorize(ASSESS);
    size(limit);
    var run = find(id, true); // Database row lock serializes all workers for this tenant/month.
    if (run.unknown() > 0) {
      jdbc.update(
          "UPDATE m_savings_monthly_fee_run SET status='RECONCILIATION_REQUIRED' WHERE id=? AND"
              + " tenant_identifier=?",
          id,
          tenant());
      return find(id, false);
    }
    if (businessDate().isBefore(run.businessDate()))
      throw ProductPolicyValidation.invalid(
          "businessDate", "Tenant business date moved backwards.");
    if (run.status().equals("DIAGNOSTIC_COMPLETE")
        || run.status().equals("RECONCILIATION_REQUIRED")) return run;
    var month = MonthlyFeePeriod.parse(run.period());
    var accounts =
        jdbc.queryForList(
            "SELECT a.id,a.product_id FROM m_savings_account a WHERE a.id>? AND a.id<=? AND (EXISTS"
                + " (SELECT 1 FROM m_savings_product_policy p WHERE"
                + " p.savings_product_id=a.product_id) OR EXISTS (SELECT 1 FROM"
                + " m_savings_account_policy b WHERE b.savings_account_id=a.id)) ORDER BY a.id"
                + " LIMIT ?",
            run.afterAccountId(),
            run.cutoffAccountId(),
            limit);
    long after = run.afterAccountId();
    for (var a : accounts) {
      if (Thread.currentThread().isInterrupted())
        throw new IllegalStateException(
            "Diagnostic processing interrupted; retry the committed checkpoint.");
      long account = ((Number) a.get("id")).longValue();
      // Immutable previous evidence, including UNKNOWN_OUTCOME, is never overwritten or reposted.
      if (jdbc.queryForObject(
              "SELECT COUNT(*) FROM m_savings_monthly_fee_assessment WHERE tenant_identifier=? AND"
                  + " savings_account_id=? AND period=?",
              Long.class,
              tenant(),
              account,
              run.period())
          == 0) {
        var d = eligibility.evaluate(account, month);
        jdbc.update(
            "INSERT INTO"
                + " m_savings_monthly_fee_assessment(run_id,tenant_identifier,savings_account_id,"
                + "savings_product_id,period,policy_version,minimum_monthly_average_balance,fee_charge_id,configured_fee_amount,outcome,reason_code,financial_outcome,assessed_by,recorded_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,'NOT_ATTEMPTED',?,CURRENT_TIMESTAMP) ON CONFLICT"
                + " (tenant_identifier,savings_account_id,period) DO NOTHING",
            id,
            tenant(),
            account,
            a.get("product_id"),
            run.period(),
            d.policyVersion(),
            d.minimumMonthlyAverageBalance(),
            d.feeChargeId(),
            d.configuredFeeAmount(),
            d.outcome().name(),
            d.reason(),
            actor);
      }
      after = account;
    }
    boolean more =
        !jdbc.queryForList(
                "SELECT a.id FROM m_savings_account a WHERE a.id>? AND a.id<=? AND (EXISTS (SELECT"
                    + " 1 FROM m_savings_product_policy p WHERE p.savings_product_id=a.product_id)"
                    + " OR EXISTS (SELECT 1 FROM m_savings_account_policy b WHERE"
                    + " b.savings_account_id=a.id)) ORDER BY a.id LIMIT 1",
                Long.class,
                after,
                run.cutoffAccountId())
            .isEmpty();
    long unknown = unknown(id);
    String status =
        unknown > 0 ? "RECONCILIATION_REQUIRED" : more ? "IN_PROGRESS" : "DIAGNOSTIC_COMPLETE";
    jdbc.update(
        "UPDATE m_savings_monthly_fee_run SET after_account_id=?,status=? WHERE id=? AND"
            + " tenant_identifier=?",
        after,
        status,
        id,
        tenant());
    var result = find(id, false);
    log.info(
        "Monthly fee diagnostic chunk status={} evaluated={} blocked={} unknown={}",
        status,
        result.evaluated(),
        result.blocked(),
        result.unknown());
    return result;
  }

  @Transactional(readOnly = true)
  public Run get(String id) {
    workflow.authorize(READ);
    return find(id, false);
  }

  @Transactional(readOnly = true)
  public History history(String beforePeriod, int limit) {
    workflow.authorize(READ);
    size(limit);
    String before =
        beforePeriod == null ? "9999-12" : MonthlyFeePeriod.parse(beforePeriod).toString();
    var ids =
        jdbc.queryForList(
            "SELECT id FROM m_savings_monthly_fee_run WHERE tenant_identifier=? AND period<? ORDER"
                + " BY period DESC LIMIT ?",
            String.class,
            tenant(),
            before,
            limit + 1);
    var items =
        ids.subList(0, Math.min(limit, ids.size())).stream().map(id -> find(id, false)).toList();
    return new History(
        items, items.isEmpty() ? beforePeriod : items.getLast().period(), ids.size() > limit);
  }

  @Transactional(readOnly = true)
  public Page<Assessment> assessments(String id, long after, int limit) {
    workflow.authorize(READ);
    size(limit);
    find(id, false);
    if (after < 0)
      throw ProductPolicyValidation.invalid("afterId", "Use a nonnegative account cursor.");
    var rows =
        jdbc.query(
            "SELECT * FROM m_savings_monthly_fee_assessment WHERE run_id=? AND tenant_identifier=?"
                + " AND savings_account_id>? ORDER BY savings_account_id LIMIT ?",
            (rs, n) ->
                new Assessment(
                    rs.getLong("savings_account_id"),
                    rs.getLong("savings_product_id"),
                    rs.getString("period"),
                    (Long) rs.getObject("policy_version"),
                    rs.getBigDecimal("minimum_monthly_average_balance"),
                    (Long) rs.getObject("fee_charge_id"),
                    rs.getBigDecimal("configured_fee_amount"),
                    Outcome.valueOf(rs.getString("outcome")),
                    rs.getString("reason_code"),
                    rs.getString("financial_outcome"),
                    rs.getLong("assessed_by"),
                    rs.getTimestamp("recorded_at").toLocalDateTime()),
            id,
            tenant(),
            after,
            limit + 1);
    var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
    return new Page<>(
        items, items.isEmpty() ? after : items.getLast().accountId(), rows.size() > limit);
  }

  @Transactional(readOnly = true)
  public Reconciliation reconciliation(String id) {
    workflow.authorize(READ);
    find(id, false);
    long unknown = unknown(id);
    return new Reconciliation(
        id, unknown > 0 ? "RECONCILIATION_REQUIRED" : "NOT_ATTEMPTED", unknown, false, false);
  }

  private long unknown(String id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM m_savings_monthly_fee_assessment WHERE run_id=? AND"
            + " tenant_identifier=? AND financial_outcome='UNKNOWN_OUTCOME'",
        Long.class,
        id,
        tenant());
  }

  private Run find(String id, boolean lock) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM m_savings_monthly_fee_run WHERE id=? AND tenant_identifier=?"
                + (lock ? " FOR UPDATE" : ""),
            id,
            tenant());
    if (rows.isEmpty()) throw new NotFound();
    var r = rows.getFirst();
    long evaluated =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_monthly_fee_assessment WHERE run_id=? AND"
                + " tenant_identifier=?",
            Long.class,
            id,
            tenant());
    long blocked =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_monthly_fee_assessment WHERE run_id=? AND"
                + " tenant_identifier=? AND outcome='BLOCKED'",
            Long.class,
            id,
            tenant());
    return new Run(
        id,
        tenant(),
        (String) r.get("period"),
        ((java.sql.Date) r.get("business_date")).toLocalDate(),
        (String) r.get("status"),
        ((Number) r.get("cutoff_account_id")).longValue(),
        ((Number) r.get("after_account_id")).longValue(),
        evaluated,
        blocked,
        unknown(id),
        ((Number) r.get("created_by")).longValue(),
        ((java.sql.Timestamp) r.get("created_at")).toLocalDateTime());
  }

  static String tenant() {
    if (ThreadLocalContextUtil.getTenant() == null)
      throw new AccessDeniedException("Authenticated tenant required.");
    return ThreadLocalContextUtil.getTenant().getTenantIdentifier();
  }

  static LocalDate businessDate() {
    LocalDate date = ProductPolicyWorkflow.today();
    if (date == null)
      throw ProductPolicyValidation.invalid("businessDate", "Tenant business date required.");
    return date;
  }

  private static void size(int size) {
    if (size < 1 || size > 100)
      throw ProductPolicyValidation.invalid("limit", "Use a batch size from 1 to 100.");
  }

  static class NotFound extends AbstractPlatformResourceNotFoundException {
    NotFound() {
      super("error.msg.savings.monthly.fee.run.not.found", "Monthly fee diagnostic run not found.");
    }
  }
}
