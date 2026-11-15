/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the Mozilla Public License, v. 2.0. If a copy of the MPL
 * was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;

import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product/office/currency/effective-date policy, with separate immutable maker and checker events.
 */
@Service
@RequiredArgsConstructor
@Transactional(
    transactionManager = "jdbcTransactionManager",
    readOnly = true,
    isolation = Isolation.REPEATABLE_READ)
public class AnpaAccountingPolicyService {
  private final AnpaAccountingAccess access;
  private final AnpaAccountingEvidenceService evidence;
  private final AnpaControlStore store;
  private final JdbcTemplate jdbc;

  public PolicyResolution get(long loanId) {
    access.authorize(AnpaAccountingAccess.READ);
    var loan = evidence.loan(loanId);
    return resolve(evidence.collect(loan, loan.businessDate()), loan.businessDate());
  }

  public List<ControlRecord> list(long loanId, long after, int limit) {
    access.authorize(AnpaAccountingAccess.AUDIT);
    evidence.loan(loanId);
    return store.list(loanId, "POLICY_DRAFT", after, limit);
  }

  public ControlRecord event(long loanId, String id) {
    access.authorize(AnpaAccountingAccess.AUDIT);
    evidence.loan(loanId);
    var event = store.get(loanId, id);
    if (!Set.of("POLICY_DRAFT", "POLICY_APPROVAL").contains(event.kind()))
      throw new jakarta.ws.rs.NotFoundException("Policy event not found.");
    return event;
  }

  @Transactional(
      transactionManager = "jdbcTransactionManager",
      isolation = Isolation.REPEATABLE_READ)
  public ControlRecord draft(long loanId, String json) {
    long actor = access.authorize(AnpaAccountingAccess.MANAGE_POLICY);
    var loan = evidence.loan(loanId);
    var request = AnpaSupport.object(json, Set.of("idempotencyKey", "policy"));
    String key = AnpaSupport.key(request);
    String hash = AnpaSupport.controlHash(List.of(loanId, actor, "POLICY_DRAFT", request));
    var replay = store.replay(loanId, key, hash);
    if (replay != null) return replay;
    var definition = definition(request);
    var sources = evidence.collect(loan, loan.businessDate());
    validate(definition, sources, true);
    return store.append(
        loan,
        actor,
        "POLICY_DRAFT",
        key,
        hash,
        null,
        definition.version(),
        null,
        null,
        AnpaSupport.hash(sources.mappings()),
        request,
        definition);
  }

  @Transactional(transactionManager = "jdbcTransactionManager", isolation = Isolation.SERIALIZABLE)
  public ControlRecord approve(long loanId, String policyId, String json) {
    long actor = access.authorize(AnpaAccountingAccess.APPROVE_POLICY);
    var loan = evidence.loan(loanId);
    var request = AnpaSupport.object(json, Set.of("idempotencyKey", "approvalReference"));
    String key = AnpaSupport.key(request);
    String reference = AnpaSupport.text(request, "approvalReference");
    String hash =
        AnpaSupport.controlHash(List.of(loanId, actor, "POLICY_APPROVAL", policyId, request));
    var replay = store.replay(loanId, key, hash);
    if (replay != null) return replay;
    var draft = store.get(loanId, policyId);
    if (!draft.kind().equals("POLICY_DRAFT"))
      throw AnpaSupport.invalid("policyId", "Policy draft required.");
    if (draft.actorId() == actor)
      throw new org.springframework.security.access.AccessDeniedException(
          "Policy maker cannot approve their own policy.");
    var definition = AnpaSupport.JSON.fromJson(draft.result(), PolicyDefinition.class);
    var sources = evidence.collect(loan, loan.businessDate());
    var accountState = validate(definition, sources, true);
    if (!draft.sourceFingerprint().equals(AnpaSupport.hash(sources.mappings())))
      throw new AnpaAssessmentAuditService.Conflict();
    var approved = store.approvedPolicies(loan);
    if (approved.size() > 200)
      throw AnpaSupport.invalid("policy", "Policy evidence limit exceeded.");
    for (var other : approved) {
      var d = AnpaSupport.JSON.fromJson(other.result(), PolicyDefinition.class);
      if (d.currency().equals(definition.currency())
          && d.accountingRule() == definition.accountingRule()
          && overlaps(d, definition)) throw new AnpaAssessmentAuditService.Conflict();
    }
    // Serializable predicate reads reject concurrent overlapping policy approvals.
    return store.append(
        loan,
        actor,
        "POLICY_APPROVAL",
        key,
        hash,
        policyId,
        null,
        policyId,
        null,
        AnpaSupport.hash(sources.mappings()),
        request,
        new PolicyApproval(
            policyId,
            draft.actorId(),
            AnpaSupport.hash(sources.mappings()),
            AnpaSupport.hash(accountState),
            reference));
  }

  PolicyResolution resolve(Evidence sources, LocalDate date) {
    var policies = store.approvedPolicies(sources.loan());
    if (policies.size() > 200) return blocked(Reason.EVIDENCE_LIMIT_EXCEEDED);
    var candidates =
        policies.stream()
            .filter(
                p -> {
                  var d = AnpaSupport.JSON.fromJson(p.result(), PolicyDefinition.class);
                  return d.currency().equals(sources.loan().currency())
                      && d.accountingRule() == sources.loan().accountingRule()
                      && !date.isBefore(d.effectiveFrom())
                      && (d.effectiveThrough() == null || !date.isAfter(d.effectiveThrough()));
                })
            .toList();
    if (candidates.isEmpty()) return blocked(Reason.ACCOUNTING_POLICY_MISSING);
    if (candidates.size() != 1) return blocked(Reason.ACCOUNTING_POLICY_AMBIGUOUS);
    var candidate = candidates.getFirst();
    var d = AnpaSupport.JSON.fromJson(candidate.result(), PolicyDefinition.class);
    boolean valid = false;
    try {
      var state = validate(d, sources, true);
      var approval = store.approval(candidate.id());
      valid =
          approval != null
              && candidate.sourceFingerprint().equals(AnpaSupport.hash(sources.mappings()))
              && approval.accountFingerprint().equals(AnpaSupport.hash(state));
    } catch (
        org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException ex) {
      /* report blocked */
    }
    return new PolicyResolution(
        candidate.id(),
        d,
        true,
        valid,
        false,
        valid
            ? List.of(Reason.NATIVE_WRITE_OFF_INTEGRATION_UNVERIFIED)
            : List.of(
                Reason.ACCOUNTING_POLICY_INVALID, Reason.NATIVE_WRITE_OFF_INTEGRATION_UNVERIFIED));
  }

  static boolean complete(PolicyResolution policy) {
    if (!policy.explicitlyApproved() || !policy.mappingsValidated() || policy.definition() == null)
      return false;
    var rules = policy.definition().rules();
    if (rules.stream().noneMatch(r -> r.category() == Category.PRINCIPAL)) return false;
    for (var c :
        List.of(Category.INTEREST, Category.FEE_COMMISSION, Category.PENALTY, Category.ITBMS)) {
      if (rules.stream()
          .noneMatch(
              r ->
                  r.category() == c
                      && r.historicalOrigin() == Classification.REGULAR
                      && r.currentClassification() == Classification.REGULAR)) return false;
      if (rules.stream()
          .noneMatch(
              r ->
                  r.category() == c
                      && r.historicalOrigin() == Classification.NPA
                      && r.currentClassification() == Classification.NPA)) return false;
      if (rules.stream()
          .noneMatch(
              r ->
                  r.category() == c
                      && r.historicalOrigin() == Classification.NPA
                      && r.currentClassification() == Classification.REGULAR
                      && r.reclassifiedExpenseEligible() != null)) return false;
    }
    return true;
  }

  private static PolicyResolution blocked(Reason reason) {
    return new PolicyResolution(
        null,
        null,
        false,
        false,
        false,
        List.of(reason, Reason.NATIVE_WRITE_OFF_INTEGRATION_UNVERIFIED));
  }

  static boolean overlaps(PolicyDefinition a, PolicyDefinition b) {
    return (a.effectiveThrough() == null || !b.effectiveFrom().isAfter(a.effectiveThrough()))
        && (b.effectiveThrough() == null || !a.effectiveFrom().isAfter(b.effectiveThrough()));
  }

  private PolicyDefinition definition(JsonObject request) {
    try {
      var p = request.getAsJsonObject("policy");
      if (p == null
          || !Set.of(
                  "version",
                  "accountingRule",
                  "currency",
                  "effectiveFrom",
                  "effectiveThrough",
                  "approvalReference",
                  "rules")
              .containsAll(p.keySet())) throw new IllegalArgumentException();
      positiveInteger(p, "version");
      positiveInteger(p, "accountingRule");
      for (String field : List.of("currency", "effectiveFrom", "approvalReference"))
        AnpaSupport.text(p, field);
      if (p.has("effectiveThrough") && !p.get("effectiveThrough").isJsonNull())
        AnpaSupport.text(p, "effectiveThrough");
      for (var rule : p.getAsJsonArray("rules")) {
        var r = rule.getAsJsonObject();
        if (!Set.of(
                "category",
                "historicalOrigin",
                "currentClassification",
                "sourceAccountId",
                "nativeMappingId",
                "destinationAccountId",
                "expenseEligible",
                "reclassifiedExpenseEligible",
                "taxComponentId")
            .containsAll(r.keySet())) throw new IllegalArgumentException();
        for (String field : List.of("category", "historicalOrigin", "currentClassification"))
          AnpaSupport.text(r, field);
        positiveInteger(r, "sourceAccountId");
        positiveInteger(r, "destinationAccountId");
        for (String field : List.of("nativeMappingId", "taxComponentId"))
          if (r.has(field) && !r.get(field).isJsonNull()) positiveInteger(r, field);
        if (!r.has("expenseEligible")
            || !r.get("expenseEligible").isJsonPrimitive()
            || !r.get("expenseEligible").getAsJsonPrimitive().isBoolean())
          throw new IllegalArgumentException();
        if (r.has("reclassifiedExpenseEligible")
            && !r.get("reclassifiedExpenseEligible").isJsonNull()
            && (!r.get("reclassifiedExpenseEligible").isJsonPrimitive()
                || !r.get("reclassifiedExpenseEligible").getAsJsonPrimitive().isBoolean()))
          throw new IllegalArgumentException();
      }
      return AnpaSupport.JSON.fromJson(p, PolicyDefinition.class);
    } catch (RuntimeException ex) {
      throw AnpaSupport.invalid("policy", "Invalid policy definition or unsupported fields.");
    }
  }

  private static void positiveInteger(JsonObject object, String field) {
    var value = object.get(field);
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isNumber()
        || !value.getAsString().matches("[1-9][0-9]*")
        || new java.math.BigInteger(value.getAsString()).longValueExact() <= 0)
      throw new IllegalArgumentException();
  }

  /** Validates IDs, native semantic references and GL types; names never establish semantics. */
  private List<Map<String, Object>> validate(PolicyDefinition d, Evidence e, boolean accounts) {
    var loan = e.loan();
    if (d == null
        || d.version() < 1
        || d.version() > 1000000
        || d.accountingRule() != loan.accountingRule()
        || !Set.of(
                org.apache.fineract.accounting.common.AccountingRuleType.ACCRUAL_UPFRONT.getValue(),
                org.apache.fineract.accounting.common.AccountingRuleType.ACCRUAL_PERIODIC
                    .getValue())
            .contains(d.accountingRule())
        || !loan.currency().equals(d.currency())
        || d.effectiveFrom() == null
        || d.effectiveFrom().isBefore(LocalDate.of(1900, 1, 1))
        || (d.effectiveThrough() != null && d.effectiveThrough().isBefore(d.effectiveFrom()))
        || d.approvalReference() == null
        || d.approvalReference().isBlank()
        || d.approvalReference().length() > 1000
        || d.approvalReference().chars().anyMatch(Character::isISOControl)
        || d.rules() == null
        || d.rules().isEmpty()
        || d.rules().size() > 100
        || e.truncated())
      throw AnpaSupport.invalid(
          "policy",
          "Product, currency, accrual rule, dates, source reference and bounded rules required.");
    var identities = new HashSet<String>();
    var state = new ArrayList<Map<String, Object>>();
    for (var r : d.rules()) {
      if (r == null
          || r.category() == null
          || r.category() == Category.OTHER
          || r.historicalOrigin() == null
          || r.currentClassification() == null
          || r.sourceAccountId() <= 0
          || r.destinationAccountId() <= 0
          || r.sourceAccountId() == r.destinationAccountId()
          || r.expenseEligible() == null
          || (r.category() != Category.PRINCIPAL
              && r.historicalOrigin() != Classification.REGULAR
              && r.historicalOrigin() != Classification.NPA)
          || (r.currentClassification() != Classification.REGULAR
              && r.currentClassification() != Classification.NPA)
          || (r.category() == Category.ITBMS) != (r.taxComponentId() != null)
          || !identities.add(
              r.category()
                  + ":"
                  + r.historicalOrigin()
                  + ":"
                  + r.currentClassification()
                  + ":"
                  + r.sourceAccountId()))
        throw AnpaSupport.invalid(
            "rules", "Explicit non-duplicate category, origin, accounts and eligibility required.");
      if (r.nativeMappingId() != null) {
        int type =
            switch (r.category()) {
              case PRINCIPAL -> 2;
              case INTEREST -> 7;
              case FEE_COMMISSION -> 8;
              case PENALTY -> 9;
              default -> -1;
            };
        if (e.mappings().stream()
            .noneMatch(
                m ->
                    m.mappingId() == r.nativeMappingId()
                        && Objects.equals(m.accountId(), r.sourceAccountId())
                        && m.financialAccountType() == type
                        && m.chargeId() == null
                        && m.paymentType() == null
                        && m.chargeOffReason() == null
                        && m.writeOffReason() == null
                        && m.capitalizedClassification() == null
                        && m.buydownClassification() == null))
          throw AnpaSupport.invalid(
              "nativeMappingId", "Matching native product/category mapping required.");
      }
      if (r.category() == Category.ITBMS) {
        var tax =
            jdbc.queryForList(
                "SELECT id,debit_account_id,credit_account_id FROM m_tax_component WHERE id=?",
                r.taxComponentId());
        if (tax.size() != 1
            || (!Objects.equals(number(tax.getFirst().get("debit_account_id")), r.sourceAccountId())
                && !Objects.equals(
                    number(tax.getFirst().get("credit_account_id")), r.sourceAccountId())))
          throw AnpaSupport.invalid(
              "taxComponentId", "Explicit tax component with matching source mapping required.");
        state.addAll(tax);
      }
      if (accounts) {
        state.add(account(r.sourceAccountId(), true, false));
        state.add(
            account(
                r.destinationAccountId(),
                false,
                Boolean.TRUE.equals(r.expenseEligible())
                    || Boolean.TRUE.equals(r.reclassifiedExpenseEligible())));
      }
    }
    return state;
  }

  private static Long number(Object v) {
    return v == null ? null : ((Number) v).longValue();
  }

  private Map<String, Object> account(long id, boolean source, boolean expense) {
    var rows =
        jdbc.queryForList(
            "SELECT id,classification_enum,account_usage,disabled FROM acc_gl_account WHERE id=?",
            id);
    if (rows.size() != 1) throw AnpaSupport.invalid("account", "GL account does not exist.");
    var row = rows.getFirst();
    int type = ((Number) row.get("classification_enum")).intValue();
    if (!Boolean.FALSE.equals(row.get("disabled"))
        || ((Number) row.get("account_usage")).intValue() != 1
        || (source && type != 1)
        || (expense && type != 5))
      throw AnpaSupport.invalid(
          "account", "Active detail asset source and expense destination where eligible required.");
    return row;
  }
}
