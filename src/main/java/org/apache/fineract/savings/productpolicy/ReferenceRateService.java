/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;
import static org.apache.fineract.savings.productpolicy.ReferenceRateData.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-database benchmark workflow. Reuses BE-02 identity, permissions and business-date
 * conventions.
 */
@Service
@RequiredArgsConstructor
public class ReferenceRateService {
  public static final String READ = "READ_SAVINGS_REFERENCE_RATE";
  public static final String DEFINE = "CREATE_SAVINGS_REFERENCE_RATE";
  public static final String CAPTURE = "CAPTURE_SAVINGS_REFERENCE_RATE";
  public static final String SUBMIT = "SUBMIT_SAVINGS_REFERENCE_RATE";
  public static final String CHECK = "CHECK_SAVINGS_REFERENCE_RATE";
  private final ProductPolicyWorkflow workflow;
  private final JdbcTemplate jdbc;

  @Transactional(readOnly = true)
  public Template template() {
    workflow.authorize(READ);
    return new Template(
        List.of(ReferenceType.values()),
        List.of(FormulaMode.values()),
        List.of(ReviewFrequency.values()),
        "EXACT_EFFECTIVE_DATE_PREVIEW_ONLY",
        false,
        false,
        blockers());
  }

  @Transactional(readOnly = true)
  public List<Definition> definitions() {
    workflow.authorize(READ);
    return jdbc.query(
        "SELECT * FROM m_savings_reference_rate ORDER BY reference_id", (rs, n) -> definition(rs));
  }

  @Transactional(readOnly = true)
  public Definition definition(String reference) {
    workflow.authorize(READ);
    return find(reference);
  }

  @Transactional
  public Definition define(String json) {
    long maker = workflow.authorize(DEFINE);
    var o = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(
        o, Set.of("referenceId", "referenceType", "tenor", "sourceDescription"));
    String id =
        ReferenceRateValidation.identifier(
            ReferenceRateValidation.text(o.get("referenceId"), "referenceId", 64), "referenceId");
    var type =
        ReferenceRateValidation.enumeration(
            o.get("referenceType"), "referenceType", ReferenceType.class);
    String tenor =
        ReferenceRateValidation.identifier(
            ReferenceRateValidation.text(o.get("tenor"), "tenor", 64), "tenor");
    String source =
        ReferenceRateValidation.text(o.get("sourceDescription"), "sourceDescription", 500);
    // Immutable definition: changing tenor/source requires a new identity. This is metadata, not
    // institutional source approval.
    jdbc.update(
        "INSERT INTO"
            + " m_savings_reference_rate(reference_id,reference_type,tenor,source_description,revision,maker_id,created_at)"
            + " VALUES (?,?,?,?,0,?,?)",
        id,
        type.name(),
        tenor,
        source,
        maker,
        DateUtils.getAuditLocalDateTime());
    event(id, 0, "DEFINE", maker, null, "CONFIGURED", null);
    return find(id);
  }

  @Transactional
  public Fixing capture(String reference, String json) {
    long maker = workflow.authorize(CAPTURE);
    var r = ReferenceRateValidation.capture(json);
    var d = lock(reference);
    if (!d.tenor().equals(r.tenor()))
      throw invalid("tenor", "Tenor must match this tenant's immutable reference definition.");
    if (r.expectedRevision() != d.revision()) throw new ProductPolicyService.Conflict();
    if (!jdbc.queryForList(
            "SELECT version FROM m_savings_reference_fixing WHERE reference_id=? AND"
                + " effective_date=? AND status<>'REJECTED'",
            Long.class,
            reference,
            r.effectiveDate())
        .isEmpty()) throw new ProductPolicyService.Conflict();
    long version = d.revision() + 1;
    jdbc.update(
        "UPDATE m_savings_reference_rate SET revision=? WHERE reference_id=? AND revision=?",
        version,
        reference,
        d.revision());
    jdbc.update(
        "INSERT INTO"
            + " m_savings_reference_fixing(reference_id,version,revision,status,annual_rate_percent,publication_date,effective_date,source_reference,note,base_version,maker_id,captured_at,open_effective_date)"
            + " VALUES (?,?,0,'DRAFT',?,?,?,?,?,?,?,?,?)",
        reference,
        version,
        r.annualRatePercent(),
        r.publicationDate(),
        r.effectiveDate(),
        r.sourceReference(),
        r.note(),
        latest(reference),
        maker,
        DateUtils.getAuditLocalDateTime(),
        r.effectiveDate());
    event(reference, version, "CAPTURE", maker, null, "DRAFT", null);
    return fixing(reference, version);
  }

  @Transactional
  public Fixing submit(String reference, long version, String json) {
    long actor = workflow.authorize(SUBMIT);
    var a = ReferenceRateValidation.action(json, false);
    lock(reference);
    var f = fixing(reference, version);
    if (f.status() != Status.DRAFT || f.revision() != a.expectedRevision())
      throw new ProductPolicyService.Conflict();
    if (f.effectiveDate().isBefore(ReferenceRateValidation.businessDate(null)))
      throw invalid("effectiveDate", "Elapsed effective date; capture a new proposal.");
    if (!Objects.equals(f.baseVersion(), latest(reference)))
      throw new ProductPolicyService.Conflict();
    jdbc.update(
        "UPDATE m_savings_reference_fixing SET"
            + " status='PENDING_APPROVAL',revision=revision+1,submitter_id=?,submitted_at=? WHERE"
            + " reference_id=? AND version=?",
        actor,
        DateUtils.getAuditLocalDateTime(),
        reference,
        version);
    event(reference, version, "SUBMIT", actor, "DRAFT", "PENDING_APPROVAL", a.reason());
    return fixing(reference, version);
  }

  @Transactional
  public Fixing decide(String reference, long version, boolean approve, String json) {
    long checker = workflow.authorize(CHECK);
    var a = ReferenceRateValidation.action(json, !approve);
    lock(reference);
    var f = fixing(reference, version);
    if (f.status() != Status.PENDING_APPROVAL || f.revision() != a.expectedRevision())
      throw new ProductPolicyService.Conflict();
    if (checker == f.makerId() || Objects.equals(checker, f.submitterId()))
      throw new AccessDeniedException(
          "Benchmark contributors cannot review their own proposal, including privileged users.");
    if (approve) {
      if (f.effectiveDate().isBefore(ReferenceRateValidation.businessDate(null)))
        throw invalid(
            "effectiveDate", "Elapsed effective date; reject and capture a new proposal.");
      if (!Objects.equals(f.baseVersion(), latest(reference))
          || !jdbc.queryForList(
                  "SELECT version FROM m_savings_reference_fixing WHERE reference_id=? AND"
                      + " status='APPROVED' AND effective_date>=?",
                  Long.class,
                  reference,
                  f.effectiveDate())
              .isEmpty()) throw new ProductPolicyService.Conflict();
    }
    String state = approve ? "APPROVED" : "REJECTED";
    jdbc.update(
        "UPDATE m_savings_reference_fixing SET"
            + " status=?,revision=revision+1,checker_id=?,decided_at=?,reason=?,open_effective_date=NULL,approved_effective_date=?"
            + " WHERE reference_id=? AND version=?",
        state,
        checker,
        DateUtils.getAuditLocalDateTime(),
        a.reason(),
        approve ? f.effectiveDate() : null,
        reference,
        version);
    event(
        reference,
        version,
        approve ? "APPROVE" : "REJECT",
        checker,
        "PENDING_APPROVAL",
        state,
        a.reason());
    return fixing(reference, version);
  }

  @Transactional(readOnly = true)
  public List<Fixing> history(String reference) {
    workflow.authorize(READ);
    find(reference);
    return jdbc.query(
        "SELECT f.*,d.tenor FROM m_savings_reference_fixing f JOIN m_savings_reference_rate d ON"
            + " d.reference_id=f.reference_id WHERE f.reference_id=? ORDER BY f.version",
        (rs, n) -> fixing(rs),
        reference);
  }

  @Transactional(readOnly = true)
  public Fixing get(String reference, long version) {
    workflow.authorize(READ);
    return fixing(reference, version);
  }

  @Transactional(readOnly = true)
  public List<Fixing> pending() {
    long checker = workflow.authorize(CHECK);
    return jdbc.query(
        "SELECT f.*,d.tenor FROM m_savings_reference_fixing f JOIN m_savings_reference_rate d ON"
            + " d.reference_id=f.reference_id WHERE f.status='PENDING_APPROVAL' AND f.maker_id<>?"
            + " AND f.submitter_id<>? ORDER BY f.reference_id,f.version",
        (rs, n) -> fixing(rs),
        checker,
        checker);
  }

  @Transactional(readOnly = true)
  public Fixing resolve(String reference, String tenor, LocalDate requested) {
    workflow.authorize(READ);
    var d = find(reference);
    if (!d.tenor().equals(tenor)) throw invalid("tenor", "Unsupported tenor for this reference.");
    LocalDate date = ReferenceRateValidation.businessDate(requested);
    // No approved observation/carry-forward policy exists. Exact-date preview is deliberately
    // conservative.
    var matches =
        jdbc.query(
            "SELECT f.*,d.tenor FROM m_savings_reference_fixing f JOIN m_savings_reference_rate d"
                + " ON d.reference_id=f.reference_id WHERE f.reference_id=? AND f.status='APPROVED'"
                + " AND f.effective_date=?",
            (rs, n) -> fixing(rs),
            reference,
            date);
    if (matches.size() != 1)
      throw invalid(
          "benchmarkFixing",
          "No unique approved fixing for this exact business date; missing/stale values cannot be"
              + " carried forward.");
    return matches.getFirst();
  }

  @Transactional(readOnly = true)
  public List<Audit> audit(String reference) {
    workflow.authorize(READ);
    find(reference);
    return jdbc.query(
        "SELECT * FROM m_savings_reference_audit WHERE reference_id=? ORDER BY id",
        (rs, n) ->
            new Audit(
                rs.getLong("id"),
                reference,
                rs.getLong("version"),
                rs.getString("action"),
                rs.getLong("actor_id"),
                instant(rs, "recorded_at"),
                rs.getString("previous_state"),
                rs.getString("new_state"),
                rs.getString("reason")),
        reference);
  }

  private Definition find(String reference) {
    ReferenceRateValidation.identifier(reference, "referenceId");
    var rows =
        jdbc.query(
            "SELECT * FROM m_savings_reference_rate WHERE reference_id=?",
            (rs, n) -> definition(rs),
            reference);
    if (rows.isEmpty()) throw new NotFound();
    return rows.getFirst();
  }

  private Definition lock(String reference) {
    ReferenceRateValidation.identifier(reference, "referenceId");
    if (jdbc.queryForList(
            "SELECT reference_id FROM m_savings_reference_rate WHERE reference_id=? FOR UPDATE",
            String.class,
            reference)
        .isEmpty()) throw new NotFound();
    // As in BE-02, force stale REPEATABLE_READ snapshots to fail instead of deciding from old
    // state.
    jdbc.update(
        "UPDATE m_savings_reference_rate SET revision=revision WHERE reference_id=?", reference);
    return find(reference);
  }

  private Long latest(String reference) {
    return jdbc.queryForObject(
        "SELECT MAX(version) FROM m_savings_reference_fixing WHERE reference_id=? AND"
            + " status='APPROVED'",
        Long.class,
        reference);
  }

  private Fixing fixing(String reference, long version) {
    ReferenceRateValidation.identifier(reference, "referenceId");
    var rows =
        jdbc.query(
            "SELECT f.*,d.tenor FROM m_savings_reference_fixing f JOIN m_savings_reference_rate d"
                + " ON d.reference_id=f.reference_id WHERE f.reference_id=? AND f.version=?",
            (rs, n) -> fixing(rs),
            reference,
            version);
    if (rows.isEmpty()) throw new NotFound();
    return rows.getFirst();
  }

  private static Definition definition(ResultSet rs) throws SQLException {
    return new Definition(
        rs.getString("reference_id"),
        ReferenceType.valueOf(rs.getString("reference_type")),
        rs.getString("tenor"),
        rs.getString("source_description"),
        rs.getLong("revision"),
        rs.getLong("maker_id"),
        instant(rs, "created_at"));
  }

  private static Fixing fixing(ResultSet rs) throws SQLException {
    return new Fixing(
        rs.getString("reference_id"),
        rs.getString("tenor"),
        rs.getLong("version"),
        rs.getLong("revision"),
        Status.valueOf(rs.getString("status")),
        rs.getBigDecimal("annual_rate_percent"),
        rs.getDate("publication_date").toLocalDate(),
        rs.getDate("effective_date").toLocalDate(),
        rs.getString("source_reference"),
        rs.getString("note"),
        (Long) rs.getObject("base_version"),
        rs.getLong("maker_id"),
        (Long) rs.getObject("submitter_id"),
        (Long) rs.getObject("checker_id"),
        instant(rs, "captured_at"),
        instant(rs, "submitted_at"),
        instant(rs, "decided_at"),
        rs.getString("reason"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var t = rs.getTimestamp(column);
    return t == null ? null : t.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
  }

  private void event(
      String reference,
      long version,
      String action,
      long actor,
      String before,
      String after,
      String reason) {
    jdbc.update(
        "INSERT INTO"
            + " m_savings_reference_audit(reference_id,version,action,actor_id,recorded_at,previous_state,new_state,reason)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        reference,
        version,
        action,
        actor,
        DateUtils.getAuditLocalDateTime(),
        before,
        after,
        reason);
  }

  public static class NotFound extends AbstractPlatformResourceNotFoundException {
    public NotFound() {
      super(
          "error.msg.savings.reference.rate.not.found",
          "Reference definition or fixing was not found in this tenant.");
    }
  }
}
