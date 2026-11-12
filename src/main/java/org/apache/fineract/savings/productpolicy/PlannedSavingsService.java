/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.PlannedSavingsAccess.number;
import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proposed corporate arrangements. A reference or native-account link never grants debit authority.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PlannedSavingsService {
  private final JdbcTemplate jdbc;
  private final PlannedSavingsAccess access;
  private final PlannedSavingsAuditService audit;
  private final ProductPolicyWorkflow workflow;

  public static class Conflict extends RuntimeException {}

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_PLANNED_SAVINGS')")
  public PlannedSavingsData.Capabilities template() {
    access.authorize(PlannedSavingsAccess.READ);
    return PlannedSavingsData.capabilities();
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','CONFIGURE_PLANNED_SAVINGS')")
  public PlannedSavingsData.Arrangement save(long client, String id, String json) {
    long actor = access.authorize(PlannedSavingsAccess.CONFIGURE);
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    PlannedSavingsValidation.id(id);
    var request = PlannedSavingsValidation.configuration(json);
    var c = request.configuration();
    var policy =
        access.policy(
            c.savingsProductId(), c.policyVersion(), ProductPolicyData.Purpose.PLANNED_MAIN);
    int digits = ((Number) policy.coreSettings().get("currency_digits")).intValue();
    if (!ProductTransactionPolicyEvaluator.validMoney(c.contributionAmount(), digits)
        || c.contributionAmount().compareTo(policy.policy().maximumBalance()) > 0)
      throw invalid(
          "contributionAmount",
          "Contribution exceeds product precision or configured maximum balance.");
    if (policy.policy().freezing() == ProductPolicyData.Freezing.TERM
        && !Integer.valueOf(c.termDays()).equals(policy.policy().freezingTermDays()))
      throw invalid("termDays", "Term must match the bound TERM policy configuration.");
    if (client == c.employerClientId())
      throw invalid("employerClientId", "Employer and employee must be distinct.");
    // Product -> sorted customers -> arrangement. Shared with native policy-opening serialization.
    for (long customer : new TreeSet<>(List.of(client, c.employerClientId())))
      access.client(customer, true, true);
    if (number(access.client(client, false, true), "legal_form_enum") != 1
        || number(access.client(c.employerClientId(), false, true), "legal_form_enum") != 2)
      throw invalid(
          "employerClientId",
          "An individual employee and an entity employer are required; their association remains unapproved.");
    var rows = rows(client, id, true);
    long revision = rows.isEmpty() ? 0 : number(rows.getFirst(), "revision");
    if (revision != request.expectedRevision()) throw new Conflict();
    if (rows.isEmpty()) {
      if (c.status() != PlannedSavingsData.ArrangementStatus.CONFIGURATION_ONLY)
        throw invalid("status", "New arrangements must be CONFIGURATION_ONLY.");
      jdbc.update(
          "INSERT INTO m_savings_planned_arrangement(id,client_id,employer_client_id,"
              + "savings_product_id,policy_version,revision,status,configuration_json) VALUES (?,?,?,?,?,1,?,?)",
          id,
          client,
          c.employerClientId(),
          c.savingsProductId(),
          c.policyVersion(),
          c.status().name(),
          RatePropagationJson.JSON.toJson(c));
    } else {
      var old = configuration(rows.getFirst());
      if (old.status() == PlannedSavingsData.ArrangementStatus.CANCELLED
          || rows.getFirst().get("savings_account_id") != null)
        throw invalid(
            "status",
            "Cancelled or native-linked configuration is immutable; no financial lifecycle change is implied.");
      if (old.savingsProductId() != c.savingsProductId()
          || old.employerClientId() != c.employerClientId())
        throw invalid(
            "configuration",
            "Product and employer identity cannot be reassigned; create a separate proposal.");
      jdbc.update(
          "UPDATE m_savings_planned_arrangement SET revision=revision+1,policy_version=?,status=?,"
              + "configuration_json=? WHERE id=? AND client_id=?",
          c.policyVersion(),
          c.status().name(),
          RatePropagationJson.JSON.toJson(c),
          id,
          client);
    }
    persistSchedule(id, revision + 1, c);
    var result = arrangement(load(client, id, false));
    evidence(result, actor, revision == 0 ? "CREATE" : "UPDATE", result);
    return result;
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','CONFIGURE_PLANNED_SAVINGS')")
  public PlannedSavingsData.Arrangement link(long client, String id, String json) {
    long actor = access.authorize(PlannedSavingsAccess.CONFIGURE);
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    var body = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(body, Set.of("expectedRevision", "savingsAccountId"));
    long revision = PlannedSavingsValidation.revision(body),
        account = PlannedSavingsValidation.positive(body, "savingsAccountId");
    access.client(client, false, true);
    var initial = load(client, id, false);
    var c = configuration(initial);
    access.policy(c.savingsProductId(), c.policyVersion(), ProductPolicyData.Purpose.PLANNED_MAIN);
    for (long customer : new TreeSet<>(List.of(client, c.employerClientId())))
      access.client(customer, true, true);
    var nativeAccount = access.account(client, account, true);
    var locked = load(client, id, true);
    if (number(locked, "revision") != revision) throw new Conflict();
    // The initial read precedes the product-lock wait. Schedule and policy checks must use the
    // locked revision, even if a caller supplied the revision of a concurrent configuration update.
    c = configuration(locked);
    access.policy(c.savingsProductId(), c.policyVersion(), ProductPolicyData.Purpose.PLANNED_MAIN);
    if (c.status() != PlannedSavingsData.ArrangementStatus.CONFIGURATION_ONLY
        || locked.get("savings_account_id") != null)
      throw invalid("status", "Only an unlinked configuration can be linked once.");
    access.requirePolicy(nativeAccount, ProductPolicyData.Purpose.PLANNED_MAIN, c.policyVersion());
    if (nativeAccount.product() != c.savingsProductId())
      throw invalid("savingsProductId", "Native product must match the arrangement.");
    // This never creates a binding or an account. Only a pre-existing native, approved binding is
    // accepted.
    jdbc.update(
        "UPDATE m_savings_planned_arrangement SET savings_account_id=?,revision=revision+1 WHERE id=? AND client_id=?",
        account,
        id,
        client);
    persistSchedule(id, revision + 1, c);
    var result = arrangement(load(client, id, false));
    evidence(result, actor, "LINK_NATIVE", result);
    return result;
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_PLANNED_SAVINGS')")
  public PlannedSavingsData.Arrangement get(long client, String id) {
    access.authorize(PlannedSavingsAccess.READ);
    access.client(client, false, false);
    var result = arrangement(load(client, id, false));
    // Current ownership must still agree with historical configuration, including terminal
    // accounts.
    if (result.savingsAccountId() != null) access.account(client, result.savingsAccountId(), false);
    return result;
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_PLANNED_SAVINGS')")
  public List<PlannedSavingsData.Arrangement> list(long client, String after, int limit) {
    access.authorize(PlannedSavingsAccess.READ);
    access.client(client, false, false);
    PlannedSavingsValidation.page(0, limit);
    if (!after.isEmpty()) PlannedSavingsValidation.id(after);
    return jdbc
        .queryForList(
            "SELECT id FROM m_savings_planned_arrangement WHERE client_id=? AND id>? ORDER BY id LIMIT ?",
            String.class,
            client,
            after,
            limit)
        .stream()
        .map(id -> get(client, id))
        .toList();
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_PLANNED_SAVINGS')")
  public List<PlannedSavingsData.Contribution> schedule(
      long client, String id, long revision, long after, int limit) {
    var current = get(client, id);
    PlannedSavingsValidation.page(after, limit);
    if (revision == 0) revision = current.revision();
    if (revision < 1 || revision > current.revision())
      throw invalid("revision", "Unknown schedule revision.");
    return jdbc.query(
        "SELECT revision,sequence_no,due_date,expected_amount FROM m_savings_planned_contribution"
            + " WHERE arrangement_id=? AND revision=? AND sequence_no>? ORDER BY sequence_no LIMIT ?",
        (rs, n) ->
            new PlannedSavingsData.Contribution(
                rs.getLong("revision"),
                rs.getInt("sequence_no"),
                rs.getDate("due_date").toLocalDate(),
                rs.getBigDecimal("expected_amount"),
                null,
                PlannedContributionSchedule.status(
                    rs.getDate("due_date").toLocalDate(),
                    ProductPolicyWorkflow.today(),
                    current.configuration().status()),
                false),
        id,
        revision,
        after,
        limit);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_PLANNED_SAVINGS')")
  public PlannedSavingsData.Eligibility maturity(long client, String id) {
    var arrangement = get(client, id);
    return maturityStatus(arrangement);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','REVIEW_PLANNED_SAVINGS')")
  public PlannedSavingsData.Review review(long client, String id, String json) {
    long actor = access.authorize(PlannedSavingsAccess.REVIEW);
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    access.client(client, false, false);
    var request = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(
        request, Set.of("expectedRevision", "idempotencyKey", "kind", "reason", "amount"));
    long revision = PlannedSavingsValidation.revision(request);
    String key =
        PlannedSavingsValidation.id(PlannedSavingsValidation.text(request, "idempotencyKey", 36));
    var kind =
        PlannedSavingsValidation.enumeration(request, "kind", PlannedSavingsData.ReviewKind.class);
    String reason = PlannedSavingsValidation.text(request, "reason", 500);
    java.math.BigDecimal amount = null;
    if (kind == PlannedSavingsData.ReviewKind.EARLY_WITHDRAWAL)
      amount = PlannedSavingsValidation.amount(request, "amount");
    else if (request.has("amount"))
      throw invalid("amount", "Amount is supported only for an early-withdrawal review.");
    var initial = load(client, id, false);
    workflow.lock(number(initial, "savings_product_id"));
    access.client(client, true, false);
    var arrangement = arrangement(load(client, id, true));
    if (amount != null) {
      var bound =
          workflow.version(
              arrangement.configuration().savingsProductId(),
              arrangement.configuration().policyVersion(),
              ProductPolicyWorkflow.today());
      int digits = ((Number) bound.coreSettings().get("currency_digits")).intValue();
      if (!ProductTransactionPolicyEvaluator.validMoney(amount, digits))
        throw invalid("amount", "Review amount exceeds the bound native currency precision.");
    }
    if (arrangement.savingsAccountId() != null)
      access.account(client, arrangement.savingsAccountId(), false);
    String fingerprint =
        hash(
            RatePropagationJson.JSON.toJson(
                Arrays.asList(
                    id,
                    revision,
                    kind,
                    reason,
                    amount == null ? null : amount.stripTrailingZeros().toPlainString())));
    var existing =
        jdbc.queryForList("SELECT * FROM m_savings_planned_review WHERE idempotency_key=?", key);
    if (!existing.isEmpty()) {
      var old = existing.getFirst();
      if (number(old, "actor_id") != actor
          || number(old, "client_id") != client
          || !fingerprint.equals(old.get("request_hash"))) throw new Conflict();
      return RatePropagationJson.JSON.fromJson(
          (String) old.get("response_json"), PlannedSavingsData.Review.class);
    }
    if (revision != arrangement.revision()) throw new Conflict();
    var blockers = new ArrayList<>(maturityStatus(arrangement).blockers());
    if (kind != PlannedSavingsData.ReviewKind.MATURITY)
      blockers.add("REASON_APPROVAL_AND_NATIVE_WORKFLOW_UNAVAILABLE");
    var response =
        new PlannedSavingsData.Review(
            key,
            id,
            revision,
            kind,
            ProductPolicyWorkflow.today(),
            "BLOCKED_PENDING_POLICY",
            false,
            null,
            blockers);
    jdbc.update(
        "INSERT INTO m_savings_planned_review(idempotency_key,arrangement_id,client_id,actor_id,request_hash,response_json) VALUES (?,?,?,?,?,?)",
        key,
        id,
        client,
        actor,
        fingerprint,
        RatePropagationJson.JSON.toJson(response));
    evidence(
        arrangement,
        actor,
        kind.name(),
        Map.of("review", response, "request", request, "requestHash", fingerprint));
    return response;
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_PLANNED_SAVINGS')")
  public List<Map<String, Object>> history(
      long client, String type, String id, long after, int limit) {
    access.authorize(PlannedSavingsAccess.READ);
    access.client(client, false, false);
    PlannedSavingsValidation.page(after, limit);
    if (!Set.of("ARRANGEMENT", "POCKET").contains(type))
      throw invalid("type", "Unknown audit subject.");
    return jdbc.queryForList(
        "SELECT id,subject_type,subject_id,savings_account_id,savings_product_id,policy_version,actor_id,tenant_identifier,action,outcome,business_date,recorded_at,details_json"
            + " FROM m_savings_planned_audit WHERE client_id=? AND subject_type=? AND subject_id=? AND id>? ORDER BY id LIMIT ?",
        client,
        type,
        id,
        after,
        limit);
  }

  private PlannedSavingsData.Eligibility maturityStatus(PlannedSavingsData.Arrangement a) {
    var reasons = new ArrayList<>(PlannedSavingsData.BLOCKERS);
    String phase =
        ProductPolicyWorkflow.today().isBefore(a.configuration().maturityDate())
            ? "BEFORE_MATURITY"
            : ProductPolicyWorkflow.today().equals(a.configuration().maturityDate())
                ? "MATURITY_DUE_BLOCKED"
                : "PAST_MATURITY_BLOCKED";
    if (phase.equals("BEFORE_MATURITY")) reasons.add("MATURITY_NOT_REACHED");
    reasons.add("CONTRIBUTION_OBLIGATIONS_AND_NATIVE_MATCHING_UNVERIFIED");
    if (a.savingsAccountId() == null) reasons.add("NATIVE_PLANNED_ACCOUNT_UNAVAILABLE");
    if (a.configuration().status() == PlannedSavingsData.ArrangementStatus.CANCELLED)
      reasons.add("CONFIGURATION_CANCELLED");
    return new PlannedSavingsData.Eligibility(phase, false, ProductPolicyWorkflow.today(), reasons);
  }

  private void persistSchedule(String id, long revision, PlannedSavingsData.Configuration c) {
    int sequence = 0;
    for (var due : PlannedContributionSchedule.dates(c))
      jdbc.update(
          "INSERT INTO m_savings_planned_contribution(arrangement_id,revision,sequence_no,due_date,expected_amount) VALUES (?,?,?,?,?)",
          id,
          revision,
          ++sequence,
          due,
          c.contributionAmount());
  }

  private List<Map<String, Object>> rows(long client, String id, boolean lock) {
    PlannedSavingsValidation.id(id);
    return jdbc.queryForList(
        "SELECT * FROM m_savings_planned_arrangement WHERE id=? AND client_id=?"
            + (lock ? " FOR UPDATE" : ""),
        id,
        client);
  }

  private Map<String, Object> load(long client, String id, boolean lock) {
    var rows = rows(client, id, lock);
    if (rows.size() != 1)
      throw invalid("arrangementId", "Arrangement is unavailable for this client.");
    return rows.getFirst();
  }

  private static PlannedSavingsData.Configuration configuration(Map<String, Object> row) {
    return RatePropagationJson.JSON.fromJson(
        (String) row.get("configuration_json"), PlannedSavingsData.Configuration.class);
  }

  private static PlannedSavingsData.Arrangement arrangement(Map<String, Object> row) {
    return new PlannedSavingsData.Arrangement(
        (String) row.get("id"),
        number(row, "client_id"),
        number(row, "revision"),
        configuration(row),
        row.get("savings_account_id") == null ? null : number(row, "savings_account_id"),
        "BLOCKED_PENDING_POLICY",
        false,
        PlannedSavingsData.BLOCKERS);
  }

  private void evidence(
      PlannedSavingsData.Arrangement a, long actor, String action, Object details) {
    audit.record(
        a.clientId(),
        "ARRANGEMENT",
        a.id(),
        a.savingsAccountId(),
        a.configuration().savingsProductId(),
        a.configuration().policyVersion(),
        actor,
        action,
        "BLOCKED_PENDING_POLICY",
        details);
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
