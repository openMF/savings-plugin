/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;
import static org.apache.fineract.savings.productpolicy.RatePropagationData.*;
import static org.apache.fineract.savings.productpolicy.RatePropagationJson.JSON;
import static org.apache.fineract.savings.productpolicy.RatePropagationValidation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable configuration-only propagation workflow. Every write chunk is one database transaction.
 */
@Service
@RequiredArgsConstructor
public class RatePropagationService {
  public static final String READ = "READ_SAVINGS_RATE_PROPAGATION";
  public static final String CREATE = "CREATE_SAVINGS_RATE_PROPAGATION";
  public static final String SUBMIT = "SUBMIT_SAVINGS_RATE_PROPAGATION";
  public static final String CHECK = "CHECK_SAVINGS_RATE_PROPAGATION";
  public static final String ASSESS = "ASSESS_SAVINGS_RATE_PROPAGATION";
  public static final String CANCEL = "CANCEL_SAVINGS_RATE_PROPAGATION";
  private final ProductPolicyWorkflow workflow;
  private final JdbcTemplate jdbc;
  private final RatePropagationSources sources;
  private final RatePropagationPopulation population;
  private final AccountRateTransitionExecutor executor;

  @Value("${savings.rate-propagation.max-chunk-size:100}")
  private int maxChunkSize = 100;

  @Transactional(readOnly = true)
  public Capabilities template() {
    workflow.authorize(READ);
    return capabilities();
  }

  @Transactional(readOnly = true)
  public Preview preview(long product, String json) {
    workflow.authorize(READ);
    Plan plan = plan(json);
    nonretroactive(plan.effectiveDate());
    Source source = sources.resolve(product, plan);
    var at = now();
    long cutoff = population.cutoff(product),
        after = 0,
        count = 0,
        eligible = 0,
        excluded = 0,
        blocked = 0;
    Map<String, Long> reasons = new LinkedHashMap<>();
    while (true) {
      var page = population.page(product, after, cutoff, at, "", chunkLimit());
      if (page.isEmpty()) break;
      for (var a : page) {
        var e = RatePropagationEligibility.evaluate(a, source, today());
        count++;
        if (e.decision().equals("ELIGIBLE")) eligible++;
        else if (e.decision().equals("EXCLUDED")) excluded++;
        else blocked++;
        reasons.merge(e.reason(), 1L, Long::sum);
      }
      after = page.getLast().accountId();
    }
    return new Preview(
        source,
        plan.effectiveDate(),
        at,
        today(),
        cutoff,
        new Population(count, eligible, excluded, blocked, Map.copyOf(reasons)),
        "PROVISIONAL_READ_ONLY_RECREATE_AND_DISCOVER_BEFORE_REVIEW",
        capabilities());
  }

  @Transactional
  public Batch create(long product, String json) {
    long actor = workflow.authorize(CREATE);
    var r = RatePropagationValidation.create(json);
    workflow.lock(product);
    var existing =
        jdbc.queryForList(
            "SELECT id,maker_id,savings_product_id,policy_version,effective_date FROM"
                + " m_savings_rate_batch WHERE tenant_id=? AND request_key=?",
            tenant(),
            r.requestKey());
    if (!existing.isEmpty()) {
      var b = existing.getFirst();
      if (number(b, "maker_id") != actor
          || number(b, "savings_product_id") != product
          || number(b, "policy_version") != r.policyVersion()
          || !((java.sql.Date) b.get("effective_date")).toLocalDate().equals(r.effectiveDate()))
        throw new ProductPolicyService.Conflict();
      return view((String) b.get("id"));
    }
    nonretroactive(r.effectiveDate());
    Source source = sources.resolve(product, new Plan(r.policyVersion(), r.effectiveDate()));
    String id = UUID.randomUUID().toString(),
        snapshot = JSON.toJson(source),
        digest = hash(snapshot);
    LocalDateTime at = now();
    jdbc.update(
        "INSERT INTO"
            + " m_savings_rate_batch(id,tenant_id,request_key,savings_product_id,policy_version,rate_type,reference_id,benchmark_version,effective_date,population_cutoff,population_business_date,cutoff_account_id,source_json,source_hash,change_classification,status,planning_complete,discovery_checkpoint,population_hash,revision,maker_id,created_at,last_progress_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',false,0,?,1,?,?,?)",
        id,
        tenant(),
        r.requestKey(),
        product,
        r.policyVersion(),
        source.policy().interestMode().name(),
        source.benchmarkFixing() == null ? null : source.benchmarkFixing().referenceId(),
        source.benchmarkFixing() == null ? null : source.benchmarkFixing().version(),
        r.effectiveDate(),
        at,
        today(),
        population.cutoff(product),
        snapshot,
        digest,
        sources.classification(source),
        hash(""),
        actor,
        at,
        at);
    event(id, actor, "CREATE", null, "DRAFT", null);
    return view(id);
  }

  /** Resumption reuses the committed keyset checkpoint; rollback also rolls back every item. */
  @Transactional
  public Batch discover(String id, String json, int requested) {
    long actor = workflow.authorize(CREATE);
    var a = action(json, false);
    int size = limit(requested, chunkLimit());
    Batch b = lock(id);
    if (b.planningComplete()) return b;
    revision(b, a);
    requireState(b, "DRAFT");
    sources.revalidate(b);
    var page =
        population.page(
            b.savingsProductId(),
            b.discoveryCheckpoint(),
            b.cutoffAccountId(),
            b.populationCutoff(),
            b.id(),
            size);
    String digest = b.populationHash();
    long checkpoint = b.discoveryCheckpoint();
    for (var account : page) {
      var eligibility =
          RatePropagationEligibility.evaluate(account, b.source(), b.populationBusinessDate());
      String snapshot = JSON.toJson(account);
      String operation =
          UUID.nameUUIDFromBytes(
                  (b.tenantId() + ":" + b.id() + ":" + account.accountId())
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .toString();
      jdbc.update(
          "INSERT INTO"
              + " m_savings_rate_account(batch_id,savings_account_id,operation_key,previous_policy_version,account_snapshot,eligibility,eligibility_reason,outcome,attempt_count)"
              + " VALUES (?,?,?,?,?,?,?,?,0)",
          id,
          account.accountId(),
          operation,
          account.previousPolicyVersion(),
          snapshot,
          eligibility.decision(),
          eligibility.reason(),
          eligibility.decision().equals("EXCLUDED") ? "SKIPPED" : "PENDING");
      digest =
          hash(
              digest
                  + "|"
                  + account.accountId()
                  + "|"
                  + snapshot
                  + "|"
                  + eligibility.decision()
                  + "|"
                  + eligibility.reason());
      checkpoint = account.accountId();
    }
    boolean complete = page.size() < size || checkpoint == b.cutoffAccountId();
    jdbc.update(
        "UPDATE m_savings_rate_batch SET"
            + " discovery_checkpoint=?,population_hash=?,planning_complete=?,revision=revision+1,last_progress_at=?"
            + " WHERE id=? AND tenant_id=?",
        checkpoint,
        digest,
        complete,
        now(),
        id,
        tenant());
    event(
        id,
        actor,
        "DISCOVER",
        b.status(),
        b.status(),
        "Captured " + page.size() + " account snapshots; planningComplete=" + complete);
    return view(id);
  }

  @Transactional
  public Batch submit(String id, String json) {
    long actor = workflow.authorize(SUBMIT);
    var a = action(json, false);
    var b = lock(id);
    revision(b, a);
    requireState(b, "DRAFT");
    if (!b.planningComplete())
      throw invalid(
          "population", "Finish bounded discovery before submitting the exact population.");
    nonretroactive(b.effectiveDate());
    sources.revalidate(b);
    jdbc.update(
        "UPDATE m_savings_rate_batch SET"
            + " status='PENDING_AUTHORIZATION',submitter_id=?,submitted_at=?,revision=revision+1,last_progress_at=?"
            + " WHERE id=? AND tenant_id=?",
        actor,
        now(),
        now(),
        id,
        tenant());
    event(id, actor, "SUBMIT", b.status(), "PENDING_AUTHORIZATION", a.reason());
    return view(id);
  }

  @Transactional
  public Batch decide(String id, boolean approve, String json) {
    long actor = workflow.authorize(CHECK);
    var a = action(json, true);
    var b = lock(id);
    revision(b, a);
    requireState(b, "PENDING_AUTHORIZATION");
    if (b.makerId() == actor
        || Objects.equals(b.submitterId(), actor)
        || jdbc.queryForObject(
                "SELECT COUNT(*) FROM m_savings_rate_audit WHERE batch_id=? AND actor_id=? AND"
                    + " action IN ('CREATE','DISCOVER','SUBMIT')",
                Long.class,
                id,
                actor)
            > 0)
      throw new AccessDeniedException(
          "Batch contributors cannot check their own mass-operation plan, including privileged"
              + " users.");
    // A product-policy draft contributor must not approve propagation of that same draft either.
    var source = workflow.row(b.savingsProductId(), b.policyVersion());
    for (String field : List.of("maker_id", "draft_creator_id", "draft_editor_id"))
      if (((Number) source.get(field)).longValue() == actor)
        throw new AccessDeniedException(
            "Source policy contributors cannot check their propagation plan.");
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_product_policy_audit WHERE savings_product_id=? AND"
                + " version<=? AND version>? AND actor_id=? AND action IN"
                + " ('DRAFT_CREATE','DRAFT_EDIT')",
            Long.class,
            b.savingsProductId(),
            b.policyVersion(),
            source.get("base_version") == null ? 0 : source.get("base_version"),
            actor)
        > 0)
      throw new AccessDeniedException(
          "Source draft contributors cannot check their propagation plan.");
    String state = "REJECTED";
    if (approve) {
      nonretroactive(b.effectiveDate());
      sources.revalidate(b);
      state = b.effectiveDate().isAfter(today()) ? "SCHEDULED" : "AUTHORIZED";
    }
    jdbc.update(
        "UPDATE m_savings_rate_batch SET"
            + " status=?,checker_id=?,decided_at=?,reason=?,authorized_hash=?,revision=revision+1,last_progress_at=?"
            + " WHERE id=? AND tenant_id=?",
        state,
        actor,
        now(),
        a.reason(),
        approve ? manifest(b) : null,
        now(),
        id,
        tenant());
    event(id, actor, approve ? "APPROVE" : "REJECT", b.status(), state, a.reason());
    return view(id);
  }

  /**
   * Configuration-only worker: serialized database claims, no remote calls or financial locks. A
   * crashed chunk rolls back entirely. Unknown outcomes are never selected for retry.
   */
  @Transactional
  public Batch assess(String id, String json, int requested) {
    long actor = workflow.authorize(ASSESS);
    var a = action(json, false);
    int size = limit(requested, chunkLimit());
    var b = lock(id);
    if (b.status().equals("BLOCKED") && b.counters().totalPending() == 0) return b;
    revision(b, a);
    if (!List.of("AUTHORIZED", "SCHEDULED", "ASSESSING", "BLOCKED").contains(b.status()))
      throw new ProductPolicyService.Conflict();
    if (b.checkerId() == null || !Objects.equals(b.authorizedHash(), manifest(b)))
      throw new ProductPolicyService.Conflict();
    if (b.effectiveDate().isAfter(today())) throw new NotReady();
    String common =
        b.effectiveDate().isBefore(today())
            ? "MISSED_EFFECTIVE_DATE_REAUTHORIZATION_REQUIRED"
            : null;
    try {
      sources.revalidate(b);
    } catch (ProductPolicyService.Conflict
        | PlatformApiDataValidationException
        | AbstractPlatformResourceNotFoundException e) {
      common = "SOURCE_VERSION_CHANGED_OR_UNAVAILABLE";
    }
    // Only PENDING items are claimable. There are no unknown-effect retries in this release.
    var work =
        jdbc.queryForList(
            "SELECT savings_account_id,account_snapshot,attempt_count FROM m_savings_rate_account"
                + " WHERE batch_id=? AND outcome='PENDING' ORDER BY savings_account_id LIMIT ? FOR"
                + " UPDATE",
            id,
            size);
    // One bounded authoritative read, including membership and status changes since approval.
    var current =
        population.forWorkItems(id, 0, size).stream()
            .collect(java.util.stream.Collectors.toMap(AccountSnapshot::accountId, x -> x));
    for (var item : work) {
      long account = number(item, "savings_account_id");
      var before = JSON.fromJson((String) item.get("account_snapshot"), AccountSnapshot.class);
      var actual = current.get(account);
      String reason = common;
      if (reason == null && (actual == null || !sameAccount(before, actual)))
        reason = "ACCOUNT_STATE_CHANGED_REVIEW_REQUIRED";
      if (reason == null) {
        var eligibility =
            RatePropagationEligibility.evaluate(actual, b.source(), b.populationBusinessDate());
        reason =
            eligibility.reason().equals("CONTRACTUAL_REPRICING_POLICY_UNAPPROVED")
                ? executor.blockingReason(b.source())
                : eligibility.reason();
      }
      int attempt = ((Number) item.get("attempt_count")).intValue() + 1;
      jdbc.update(
          "UPDATE m_savings_rate_account SET"
              + " outcome='BLOCKED',attempt_count=?,processed_at=?,error_category=? WHERE"
              + " batch_id=? AND savings_account_id=? AND outcome='PENDING'",
          attempt,
          now(),
          reason,
          id,
          account);
      jdbc.update(
          "INSERT INTO"
              + " m_savings_rate_attempt(batch_id,savings_account_id,attempt_number,actor_id,recorded_at,outcome,error_category)"
              + " VALUES (?,?,?,?,?,'BLOCKED',?)",
          id,
          account,
          attempt,
          actor,
          now(),
          reason);
    }
    long pending =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_rate_account WHERE batch_id=? AND outcome='PENDING'",
            Long.class,
            id);
    String state = pending == 0 ? "BLOCKED" : "ASSESSING";
    jdbc.update(
        "UPDATE m_savings_rate_batch SET status=?,reason=?,revision=revision+1,last_progress_at=?"
            + " WHERE id=? AND tenant_id=?",
        state,
        common == null ? executor.blockingReason(b.source()) : common,
        now(),
        id,
        tenant());
    event(
        id,
        actor,
        "ASSESS",
        b.status(),
        state,
        "Assessed " + work.size() + " items; financial execution unavailable.");
    return view(id);
  }

  /** A financial start request always fails closed, with durable evidence and a 409 API result. */
  @Transactional
  public Blocked start(String id, String json) {
    long actor = workflow.authorize(ASSESS);
    var a = action(json, false);
    var b = lock(id);
    revision(b, a);
    if (!List.of("AUTHORIZED", "SCHEDULED", "ASSESSING", "BLOCKED").contains(b.status())
        || b.checkerId() == null
        || !Objects.equals(b.authorizedHash(), manifest(b)))
      throw new ProductPolicyService.Conflict();
    String code =
        b.effectiveDate().isAfter(today())
            ? "EFFECTIVE_DATE_NOT_REACHED"
            : b.effectiveDate().isBefore(today())
                ? "MISSED_EFFECTIVE_DATE_REAUTHORIZATION_REQUIRED"
                : executor.blockingReason(b.source());
    // Recheck source even on this deliberately disabled boundary; never call execute().
    try {
      sources.revalidate(b);
    } catch (ProductPolicyService.Conflict
        | PlatformApiDataValidationException
        | AbstractPlatformResourceNotFoundException e) {
      code = "SOURCE_VERSION_CHANGED_OR_UNAVAILABLE";
    }
    event(id, actor, "START_BLOCKED", b.status(), b.status(), code);
    return new Blocked(
        code,
        "Financial repricing is disabled. No account rate, balance, interest or journal was"
            + " changed.",
        b);
  }

  @Transactional
  public Batch cancel(String id, String json) {
    long actor = workflow.authorize(CANCEL);
    var a = action(json, true);
    var b = lock(id);
    if (b.status().equals("CANCELLED")) return b;
    revision(b, a);
    if (b.status().equals("REJECTED")) throw new ProductPolicyService.Conflict();
    if (b.counters().totalUnknownOutcome() > 0) throw new ProductPolicyService.Conflict();
    jdbc.update(
        "UPDATE m_savings_rate_batch SET"
            + " status='CANCELLED',reason=?,revision=revision+1,last_progress_at=? WHERE id=? AND"
            + " tenant_id=?",
        a.reason(),
        now(),
        id,
        tenant());
    event(id, actor, "CANCEL", b.status(), "CANCELLED", a.reason());
    return view(id);
  }

  @Transactional(readOnly = true)
  public Batch get(String id) {
    workflow.authorize(READ);
    uuid(id, "batchId");
    return view(id);
  }

  @Transactional(readOnly = true)
  public Page<AccountOutcome> accounts(String id, long after, int requested) {
    workflow.authorize(READ);
    cursor(after);
    int size = limit(requested, chunkLimit());
    var b = view(id);
    var items =
        jdbc.query(
            "SELECT * FROM m_savings_rate_account WHERE batch_id=? AND savings_account_id>? ORDER"
                + " BY savings_account_id LIMIT ?",
            (rs, n) ->
                new AccountOutcome(
                    rs.getLong("savings_account_id"),
                    rs.getString("operation_key"),
                    ProductPolicyWorkflow.number(rs.getObject("previous_policy_version")),
                    b.policyVersion(),
                    b.effectiveDate(),
                    rs.getString("eligibility"),
                    rs.getString("eligibility_reason"),
                    rs.getString("outcome"),
                    rs.getInt("attempt_count"),
                    RatePropagationPopulation.time(rs, "processed_at"),
                    rs.getString("error_category"),
                    rs.getString("confirmed_operation_id"),
                    JSON.fromJson(rs.getString("account_snapshot"), AccountSnapshot.class)),
            id,
            after,
            size + 1);
    boolean more = items.size() > size;
    var page = List.copyOf(items.subList(0, Math.min(size, items.size())));
    return new Page<>(page, page.isEmpty() ? after : page.getLast().savingsAccountId(), more);
  }

  @Transactional(readOnly = true)
  public Page<Audit> audit(String id, long after, int requested) {
    workflow.authorize(READ);
    cursor(after);
    int size = limit(requested, chunkLimit());
    view(id);
    var items =
        jdbc.query(
            "SELECT * FROM m_savings_rate_audit WHERE batch_id=? AND id>? ORDER BY id LIMIT ?",
            (rs, n) ->
                new Audit(
                    rs.getLong("id"),
                    rs.getLong("actor_id"),
                    rs.getString("action"),
                    rs.getString("previous_state"),
                    rs.getString("new_state"),
                    rs.getLong("revision"),
                    RatePropagationPopulation.time(rs, "recorded_at"),
                    rs.getString("detail"),
                    rs.getString("snapshot_hash")),
            id,
            after,
            size + 1);
    var page = List.copyOf(items.subList(0, Math.min(size, items.size())));
    return new Page<>(page, page.isEmpty() ? after : page.getLast().id(), items.size() > size);
  }

  @Transactional(readOnly = true)
  public Page<Attempt> attempts(String id, long after, int requested) {
    workflow.authorize(READ);
    cursor(after);
    int size = limit(requested, chunkLimit());
    view(id);
    var items =
        jdbc.query(
            "SELECT * FROM m_savings_rate_attempt WHERE batch_id=? AND id>? ORDER BY id LIMIT ?",
            (rs, n) ->
                new Attempt(
                    rs.getLong("id"),
                    rs.getLong("savings_account_id"),
                    rs.getInt("attempt_number"),
                    rs.getLong("actor_id"),
                    RatePropagationPopulation.time(rs, "recorded_at"),
                    rs.getString("outcome"),
                    rs.getString("error_category")),
            id,
            after,
            size + 1);
    var page = List.copyOf(items.subList(0, Math.min(size, items.size())));
    return new Page<>(page, page.isEmpty() ? after : page.getLast().id(), items.size() > size);
  }

  private Batch lock(String id) {
    var initial = row(id, false);
    workflow.lock(number(initial, "savings_product_id"));
    row(id, true);
    // A stale REPEATABLE_READ worker must abort after another chunk commits.
    jdbc.update(
        "UPDATE m_savings_rate_batch SET revision=revision WHERE id=? AND tenant_id=?",
        id,
        tenant());
    return view(id);
  }

  private Map<String, Object> row(String id, boolean lock) {
    uuid(id, "batchId");
    var rows =
        jdbc.queryForList(
            "SELECT * FROM m_savings_rate_batch WHERE id=? AND tenant_id=?"
                + (lock ? " FOR UPDATE" : ""),
            id,
            tenant());
    if (rows.isEmpty()) throw new NotFound();
    return rows.getFirst();
  }

  private Batch view(String id) {
    var r = row(id, false);
    boolean complete = (Boolean) r.get("planning_complete");
    return new Batch(
        id,
        (String) r.get("tenant_id"),
        number(r, "savings_product_id"),
        number(r, "policy_version"),
        ((java.sql.Date) r.get("effective_date")).toLocalDate(),
        time(r, "population_cutoff"),
        ((java.sql.Date) r.get("population_business_date")).toLocalDate(),
        number(r, "cutoff_account_id"),
        JSON.fromJson((String) r.get("source_json"), Source.class),
        (String) r.get("source_hash"),
        (String) r.get("change_classification"),
        (String) r.get("status"),
        complete,
        number(r, "discovery_checkpoint"),
        (String) r.get("population_hash"),
        (String) r.get("authorized_hash"),
        number(r, "revision"),
        number(r, "maker_id"),
        ProductPolicyWorkflow.number(r.get("submitter_id")),
        ProductPolicyWorkflow.number(r.get("checker_id")),
        time(r, "created_at"),
        time(r, "submitted_at"),
        time(r, "decided_at"),
        time(r, "last_progress_at"),
        (String) r.get("reason"),
        counters(id),
        complete ? "PLANNING_COMPLETE" : "PLANNING_IN_PROGRESS",
        capabilities());
  }

  private Counters counters(String id) {
    long discovered = 0,
        eligible = 0,
        excluded = 0,
        popBlocked = 0,
        pending = 0,
        processed = 0,
        skipped = 0,
        blocked = 0,
        failed = 0,
        unknown = 0;
    Map<String, Long> reasons = new LinkedHashMap<>();
    for (var r :
        jdbc.queryForList(
            "SELECT eligibility,eligibility_reason,outcome,error_category,COUNT(*) AS n,SUM(CASE"
                + " WHEN attempt_count>0 THEN 1 ELSE 0 END) AS processed FROM"
                + " m_savings_rate_account WHERE batch_id=? GROUP BY"
                + " eligibility,eligibility_reason,outcome,error_category",
            id)) {
      long n = number(r, "n");
      discovered += n;
      processed += number(r, "processed");
      String e = (String) r.get("eligibility"), o = (String) r.get("outcome");
      if (e.equals("ELIGIBLE")) eligible += n;
      else if (e.equals("EXCLUDED")) excluded += n;
      else popBlocked += n;
      switch (o) {
        case "PENDING" -> pending += n;
        case "SKIPPED" -> skipped += n;
        case "BLOCKED" -> blocked += n;
        case "UNKNOWN_OUTCOME" -> unknown += n;
        default -> failed += n;
      }
      reasons.merge(
          (String)
              (r.get("error_category") == null
                  ? r.get("eligibility_reason")
                  : r.get("error_category")),
          n,
          Long::sum);
    }
    return new Counters(
        discovered,
        eligible,
        excluded,
        popBlocked,
        pending,
        processed,
        0,
        0,
        skipped,
        blocked,
        failed,
        unknown,
        Map.copyOf(reasons));
  }

  private void event(
      String id, long actor, String action, String before, String after, String detail) {
    var r = row(id, false);
    jdbc.update(
        "INSERT INTO"
            + " m_savings_rate_audit(batch_id,actor_id,action,previous_state,new_state,revision,recorded_at,detail,snapshot_hash)"
            + " VALUES (?,?,?,?,?,?,?,?,?)",
        id,
        actor,
        action,
        before,
        after,
        number(r, "revision"),
        now(),
        detail,
        hash((String) r.get("source_hash") + "|" + r.get("population_hash")));
  }

  private static String manifest(Batch b) {
    return hash(
        b.tenantId()
            + "|"
            + b.id()
            + "|"
            + b.sourceHash()
            + "|"
            + b.effectiveDate()
            + "|"
            + b.populationCutoff()
            + "|"
            + b.populationBusinessDate()
            + "|"
            + b.cutoffAccountId()
            + "|"
            + b.populationHash());
  }

  private static boolean sameAccount(AccountSnapshot a, AccountSnapshot b) {
    // A new conflicting draft can arise after approval; it must block rather than alter the
    // authorized snapshot.
    return a.accountId() == b.accountId()
        && a.savingsProductId() == b.savingsProductId()
        && a.status() == b.status()
        && a.depositType() == b.depositType()
        && a.subStatus() == b.subStatus()
        && Objects.equals(a.previousPolicyVersion(), b.previousPolicyVersion())
        && Objects.equals(a.activatedOn(), b.activatedOn())
        && Objects.equals(a.accountSettings(), b.accountSettings())
        && Objects.equals(a.previousPolicyStatus(), b.previousPolicyStatus())
        && !b.conflictingPlan();
  }

  private int chunkLimit() {
    if (maxChunkSize < 1 || maxChunkSize > 500)
      throw new IllegalStateException("savings.rate-propagation.max-chunk-size must be 1..500");
    return maxChunkSize;
  }

  private static long number(Map<String, Object> r, String key) {
    return ((Number) r.get(key)).longValue();
  }

  private static LocalDateTime time(Map<String, Object> r, String key) {
    var t = (java.sql.Timestamp) r.get(key);
    return t == null ? null : t.toLocalDateTime();
  }

  private static LocalDateTime now() {
    var t = DateUtils.getAuditLocalDateTime();
    return t.withNano((t.getNano() / 1000) * 1000);
  }

  private static LocalDate today() {
    return ReferenceRateValidation.businessDate(null);
  }

  private static String tenant() {
    return ThreadLocalContextUtil.getTenant().getTenantIdentifier();
  }

  private static void nonretroactive(LocalDate date) {
    if (date.isBefore(today()))
      throw invalid(
          "effectiveDate",
          "Retroactive or missed-date plans require a new proposal; no historical repricing is"
              + " available.");
  }

  private static void revision(Batch b, Action a) {
    if (b.revision() != a.expectedRevision()) throw new ProductPolicyService.Conflict();
  }

  private static void requireState(Batch b, String state) {
    if (!b.status().equals(state)) throw new ProductPolicyService.Conflict();
  }

  private static void cursor(long after) {
    if (after < 0) throw invalid("afterId", "Nonnegative keyset cursor required.");
  }

  public static class NotReady extends ProductPolicyService.Conflict {}

  public static class NotFound extends AbstractPlatformResourceNotFoundException {
    public NotFound() {
      super(
          "error.msg.savings.rate.propagation.not.found",
          "Propagation batch was not found in this tenant.");
    }
  }
}
