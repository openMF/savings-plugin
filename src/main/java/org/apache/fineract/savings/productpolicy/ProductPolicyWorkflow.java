/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyWorkflowData.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mandatory independent policy review, isolated from optional native maker-checker settings. */
@Service
@RequiredArgsConstructor
public class ProductPolicyWorkflow {
  public static final String SUBMIT = "SUBMIT_SAVINGS_PRODUCT_POLICY";
  public static final String CHECK = "CHECK_SAVINGS_PRODUCT_POLICY";
  static final Gson JSON =
      new com.google.gson.GsonBuilder()
          .serializeNulls()
          .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.BIG_DECIMAL)
          .create();
  private final PlatformSecurityContext security;
  private final JdbcTemplate jdbc;
  private final ProductPolicyValidation validation;
  private final ProductPolicyCoreSnapshot core;

  long authorize(String permission) {
    var user = security.authenticatedUser();
    user.validateHasPermissionTo(permission);
    if (ThreadLocalContextUtil.getTenant() == null)
      throw new AccessDeniedException("Authenticated tenant required.");
    return user.getId();
  }

  static LocalDate today() {
    return ThreadLocalContextUtil.getBusinessDate();
  }

  void lock(long product) {
    if (jdbc.queryForList(
            "SELECT id FROM m_savings_product WHERE id=? AND deposit_type_enum=100 FOR UPDATE",
            Long.class,
            product)
        .isEmpty()) throw new ProductPolicyService.NotFound();
    // Force concurrent REPEATABLE_READ transactions with an earlier snapshot to retry rather
    // than validate against stale data after waiting for the authoritative product lock.
    jdbc.update(
        "UPDATE m_savings_product_policy SET revision=revision WHERE savings_product_id=?",
        product);
  }

  @Transactional
  public Version submit(long product, Submit request) {
    long maker = authorize(SUBMIT);
    if (request == null
        || request.expectedRevision() == null
        || request.expectedRevision() <= 0
        || request.effectiveDate() == null)
      throw ProductPolicyValidation.invalid(
          "submission", "Positive draft revision and effectiveDate are required.");
    lock(product);
    if (request.effectiveDate().isBefore(today()))
      throw ProductPolicyValidation.invalid(
          "effectiveDate", "Retroactive policy changes are prohibited.");
    var drafts =
        jdbc.queryForList(
            "SELECT revision,policy_json,created_by,updated_by FROM m_savings_product_policy WHERE"
                + " savings_product_id=?",
            product);
    if (drafts.isEmpty()) throw new ProductPolicyService.NotFound();
    var draft = drafts.getFirst();
    long revision = ((Number) draft.get("revision")).longValue();
    if (revision != request.expectedRevision()
        || !jdbc.queryForList(
                "SELECT version FROM m_savings_product_policy_version WHERE savings_product_id=?"
                    + " AND (version=? OR status='PENDING_APPROVAL')",
                Long.class,
                product,
                revision)
            .isEmpty()) throw new ProductPolicyService.Conflict();
    var policy = JSON.fromJson((String) draft.get("policy_json"), ProductPolicyData.Policy.class);
    if (policy.interestMode() == ProductPolicyData.InterestMode.TIERED
        && policy.interestMatrix() == null)
      throw ProductPolicyValidation.invalid(
          "interestMatrix", "TIERED submission requires a validated matrix snapshot.");
    core.validate(product, policy, validation);
    String snapshot = JSON.toJson(core.capture(product));
    Long base = latestApproved(product);
    jdbc.update(
        "INSERT INTO"
            + " m_savings_product_policy_version(savings_product_id,version,status,effective_date,base_version,maker_id,draft_creator_id,draft_editor_id,policy_json,core_json,submitted_at)"
            + " VALUES (?,?,'PENDING_APPROVAL',?,?,?,?,?,?,?,?)",
        product,
        revision,
        request.effectiveDate(),
        base,
        maker,
        draft.get("created_by"),
        draft.get("updated_by"),
        draft.get("policy_json"),
        snapshot,
        org.apache.fineract.infrastructure.core.service.DateUtils.getAuditLocalDateTime());
    event(
        product,
        revision,
        "SUBMIT",
        maker,
        "DRAFT",
        "PENDING_APPROVAL",
        request.effectiveDate(),
        null,
        (String) draft.get("policy_json"));
    return version(product, revision, today());
  }

  @Transactional
  public Version decide(long product, long revision, boolean approve, Decision decision) {
    long checker = authorize(CHECK);
    String reason = decision == null ? null : decision.reason();
    if (reason != null
        && (reason.isBlank()
            || reason.length() > 1000
            || reason.chars().anyMatch(Character::isISOControl)))
      throw ProductPolicyValidation.invalid(
          "reason", "Use a nonblank reason of at most 1000 characters without control characters.");
    if (!approve && reason == null)
      throw ProductPolicyValidation.invalid("reason", "Rejection requires a reason.");
    lock(product);
    var row = row(product, revision);
    if (!"PENDING_APPROVAL".equals(row.get("status"))) throw new ProductPolicyService.Conflict();
    for (String field : List.of("maker_id", "draft_creator_id", "draft_editor_id")) {
      if (((Number) row.get(field)).longValue() == checker)
        throw new AccessDeniedException(
            "A policy contributor cannot review their own proposal, including privileged users.");
    }
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_product_policy_audit WHERE savings_product_id=? AND"
                + " version<=? AND version>? AND action IN ('DRAFT_CREATE','DRAFT_EDIT') AND"
                + " actor_id=?",
            Long.class,
            product,
            revision,
            row.get("base_version") == null ? 0 : row.get("base_version"),
            checker)
        > 0)
      throw new AccessDeniedException("A draft contributor cannot review their own proposal.");
    LocalDate effective = ((java.sql.Date) row.get("effective_date")).toLocalDate();
    if (approve) {
      if (effective.isBefore(today()))
        throw ProductPolicyValidation.invalid(
            "effectiveDate", "Effective date has elapsed; reject and submit a new revision.");
      if (!Objects.equals(number(row.get("base_version")), latestApproved(product)))
        throw new ProductPolicyService.Conflict();
      var dates =
          jdbc.queryForList(
              "SELECT effective_date FROM m_savings_product_policy_version WHERE"
                  + " savings_product_id=? AND status='APPROVED' AND effective_date>=?",
              java.sql.Date.class,
              product,
              effective);
      if (!dates.isEmpty()) throw new ProductPolicyService.Conflict();
      var policy = JSON.fromJson((String) row.get("policy_json"), ProductPolicyData.Policy.class);
      core.validate(product, policy, validation);
      if (!JSON.fromJson((String) row.get("core_json"), JsonObject.class)
          .equals(core.capture(product))) throw new ProductPolicyService.Conflict();
    }
    String status = approve ? "APPROVED" : "REJECTED";
    if (jdbc.update(
            "UPDATE m_savings_product_policy_version SET"
                + " status=?,checker_id=?,decided_at=?,reason=? WHERE savings_product_id=? AND"
                + " version=? AND status='PENDING_APPROVAL'",
            status,
            checker,
            org.apache.fineract.infrastructure.core.service.DateUtils.getAuditLocalDateTime(),
            reason,
            product,
            revision)
        != 1) throw new ProductPolicyService.Conflict();
    event(
        product,
        revision,
        approve ? "APPROVE" : "REJECT",
        checker,
        "PENDING_APPROVAL",
        status,
        effective,
        reason,
        null);
    reconcile(product, today());
    return version(product, revision, today());
  }

  @Transactional
  public List<Version> history(long product) {
    authorize(ProductPolicyService.READ);
    lock(product);
    reconcile(product, today());
    return versions(product, today());
  }

  @Transactional
  public Version applicable(long product, LocalDate date) {
    authorize(ProductPolicyService.READ);
    lock(product);
    reconcile(product, today());
    if (date == null) date = today();
    // This is an as-of review, not authority to open an account at a caller-selected date.
    return resolve(product, date);
  }

  @Transactional(readOnly = true)
  public List<Version> pending() {
    long checker = authorize(CHECK);
    return jdbc
        .queryForList(
            "SELECT v.savings_product_id,v.version FROM m_savings_product_policy_version v WHERE"
                + " v.status='PENDING_APPROVAL' AND v.maker_id<>? AND v.draft_creator_id<>? AND"
                + " v.draft_editor_id<>? AND NOT EXISTS (SELECT 1 FROM"
                + " m_savings_product_policy_audit a WHERE"
                + " a.savings_product_id=v.savings_product_id AND a.version<=v.version AND"
                + " a.version>COALESCE(v.base_version,0) AND a.action IN"
                + " ('DRAFT_CREATE','DRAFT_EDIT') AND a.actor_id=?) ORDER BY"
                + " v.savings_product_id,v.version",
            checker,
            checker,
            checker,
            checker)
        .stream()
        .map(
            r ->
                version(
                    ((Number) r.get("savings_product_id")).longValue(),
                    ((Number) r.get("version")).longValue(),
                    today()))
        .toList();
  }

  @Transactional
  public Review review(long product, long revision) {
    authorize(ProductPolicyService.READ);
    lock(product);
    reconcile(product, today());
    Version proposed = version(product, revision, today()), active = resolve(product, today());
    var before =
        active == null ? new JsonObject() : JSON.toJsonTree(active.policy()).getAsJsonObject();
    var after = JSON.toJsonTree(proposed.policy()).getAsJsonObject();
    List<String> changes = new ArrayList<>();
    for (String key : after.keySet())
      if (!Objects.equals(before.get(key), after.get(key))) changes.add("policy." + key);
    var oldCore =
        active == null
            ? new JsonObject()
            : JSON.toJsonTree(active.coreSettings()).getAsJsonObject();
    for (String key : proposed.coreSettings().keySet())
      if (!Objects.equals(oldCore.get(key), JSON.toJsonTree(proposed.coreSettings().get(key))))
        changes.add("core." + key);
    return new Review(proposed, active, List.copyOf(changes));
  }

  @Transactional
  public List<Audit> audit(long product) {
    authorize(ProductPolicyService.READ);
    lock(product);
    reconcile(product, today());
    return jdbc.query(
        "SELECT * FROM m_savings_product_policy_audit WHERE savings_product_id=? ORDER BY id",
        (rs, n) ->
            new Audit(
                rs.getLong("id"),
                product,
                rs.getLong("version"),
                rs.getString("action"),
                (Long) rs.getObject("actor_id"),
                rs.getObject("recorded_at", java.time.LocalDateTime.class)
                    .toInstant(java.time.ZoneOffset.UTC),
                rs.getString("previous_state"),
                rs.getString("new_state"),
                rs.getDate("effective_date") == null
                    ? null
                    : rs.getDate("effective_date").toLocalDate(),
                rs.getString("reason"),
                rs.getString("snapshot_json")),
        product);
  }

  Long latestApproved(long product) {
    return jdbc.queryForObject(
        "SELECT MAX(version) FROM m_savings_product_policy_version WHERE savings_product_id=? AND"
            + " status='APPROVED'",
        Long.class,
        product);
  }

  Map<String, Object> row(long product, long version) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM m_savings_product_policy_version WHERE savings_product_id=? AND"
                + " version=?",
            product,
            version);
    if (rows.isEmpty()) throw new ProductPolicyService.NotFound();
    return rows.getFirst();
  }

  Version version(long product, long revision, LocalDate date) {
    var r = row(product, revision);
    String status = (String) r.get("status");
    LocalDate effective = ((java.sql.Date) r.get("effective_date")).toLocalDate();
    if (status.equals("APPROVED") && !effective.isAfter(date)) {
      Long latest =
          jdbc.queryForObject(
              "SELECT MAX(version) FROM m_savings_product_policy_version WHERE savings_product_id=?"
                  + " AND status='APPROVED' AND effective_date<=?",
              Long.class,
              product,
              date);
      status = Objects.equals(latest, revision) ? "ACTIVE" : "SUPERSEDED";
    }
    return new Version(
        product,
        revision,
        status,
        effective,
        number(r.get("base_version")),
        ((Number) r.get("maker_id")).longValue(),
        number(r.get("checker_id")),
        ((Timestamp) r.get("submitted_at")).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC),
        r.get("decided_at") == null
            ? null
            : ((Timestamp) r.get("decided_at"))
                .toLocalDateTime()
                .toInstant(java.time.ZoneOffset.UTC),
        (String) r.get("reason"),
        JSON.fromJson((String) r.get("policy_json"), ProductPolicyData.Policy.class),
        JSON.fromJson(
            (String) r.get("core_json"),
            new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType()));
  }

  List<Version> versions(long product, LocalDate date) {
    return jdbc
        .queryForList(
            "SELECT version FROM m_savings_product_policy_version WHERE savings_product_id=? ORDER"
                + " BY version",
            Long.class,
            product)
        .stream()
        .map(v -> version(product, v, date))
        .toList();
  }

  Version resolve(long product, LocalDate date) {
    Long id =
        jdbc.queryForObject(
            "SELECT MAX(version) FROM m_savings_product_policy_version WHERE savings_product_id=?"
                + " AND status='APPROVED' AND effective_date<=?",
            Long.class,
            product,
            date);
    return id == null ? null : version(product, id, date);
  }

  // Called with the product lock held. Immutable approvals are authoritative; lifecycle audit is
  // materialized idempotently at first observation after business date advances (including
  // restart).
  void reconcile(long product, LocalDate date) {
    Version previous = null;
    for (var v : versions(product, date)) {
      if (!v.status().equals("ACTIVE") && !v.status().equals("SUPERSEDED")) continue;
      once(product, v.version(), "ACTIVATE", "APPROVED", "ACTIVE", v.effectiveDate());
      if (previous != null)
        once(product, previous.version(), "SUPERSEDE", "ACTIVE", "SUPERSEDED", v.effectiveDate());
      previous = v;
    }
  }

  void once(
      long product,
      long revision,
      String action,
      String before,
      String after,
      LocalDate effective) {
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_product_policy_audit WHERE savings_product_id=? AND"
                + " version=? AND action=?",
            Long.class,
            product,
            revision,
            action)
        == 0) event(product, revision, action, null, before, after, effective, null, null);
  }

  void event(
      long product,
      long revision,
      String action,
      Long actor,
      String before,
      String after,
      LocalDate effective,
      String reason,
      String snapshot) {
    jdbc.update(
        "INSERT INTO"
            + " m_savings_product_policy_audit(savings_product_id,version,action,actor_id,recorded_at,previous_state,new_state,effective_date,reason,snapshot_json)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
        product,
        revision,
        action,
        actor,
        org.apache.fineract.infrastructure.core.service.DateUtils.getAuditLocalDateTime(),
        before,
        after,
        effective,
        reason,
        snapshot);
  }

  static Long number(Object n) {
    return n == null ? null : ((Number) n).longValue();
  }
}
