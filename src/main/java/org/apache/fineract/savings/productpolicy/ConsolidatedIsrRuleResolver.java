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
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable proposal timeline. Neither a reference nor a contributor can approve a tax formula. */
@Service
@RequiredArgsConstructor
public class ConsolidatedIsrRuleResolver {
  private final JdbcTemplate jdbc;
  private final ConsolidatedIsrAccess access;
  private final ConsolidatedIsrAuditService audit;

  @Transactional(transactionManager = "jdbcTransactionManager")
  public RuleVersion propose(String id, String json) {
    long actor = access.authorize(ConsolidatedIsrAccess.RULES);
    ConsolidatedIsrValidation.uuid(id);
    var request =
        ConsolidatedIsrValidation.parse(
            json,
            RuleProposal.class,
            Set.of("idempotencyKey", "expectedVersion", "definition", "reason"));
    if (request.expectedVersion() == null || request.expectedVersion() < 0)
      throw invalid("expectedVersion", "Nonnegative expected version required.");
    String replay = audit.begin(request.idempotencyKey(), "RULE_PROPOSAL", id, request, actor);
    if (replay != null) return RatePropagationJson.JSON.fromJson(replay, RuleVersion.class);
    validate(request.definition());
    ConsolidatedIsrValidation.text("reason", request.reason(), true, 1000);
    jdbc.update(
        "INSERT INTO m_savings_isr_rule(id,tenant_identifier,revision) VALUES (?,?,0)"
            + " ON CONFLICT (id) DO NOTHING",
        id,
        access.tenant());
    var families =
        jdbc.queryForList(
            "SELECT revision FROM m_savings_isr_rule WHERE id=? AND tenant_identifier=? FOR UPDATE",
            id,
            access.tenant());
    if (families.size() != 1
        || ((Number) families.getFirst().get("revision")).longValue() != request.expectedVersion())
      throw new ConsolidatedIsrAuditService.Conflict();
    var d = request.definition();
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_isr_rule_version WHERE rule_id=?"
                + " AND effective_from<=? AND effective_through>=?",
            Long.class,
            id,
            d.effectiveThrough(),
            d.effectiveFrom())
        != 0)
      throw invalid(
          "effectiveFrom", "Rule proposal validity ranges must not overlap within a timeline.");
    long version = request.expectedVersion() + 1;
    var result =
        new RuleVersion(
            id,
            version,
            version == 1 ? null : version - 1,
            access.tenant(),
            "DRAFT",
            d,
            actor,
            null,
            Instant.now(),
            null,
            request.reason());
    jdbc.update(
        "INSERT INTO m_savings_isr_rule_version(rule_id,version,previous_version,tenant_identifier,"
            + "effective_from,effective_through,author_id,created_at,change_reason,definition_json,snapshot_json)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        id,
        version,
        result.previousVersion(),
        access.tenant(),
        d.effectiveFrom(),
        d.effectiveThrough(),
        actor,
        java.time.LocalDateTime.ofInstant(result.createdAt(), java.time.ZoneOffset.UTC),
        request.reason(),
        RatePropagationJson.JSON.toJson(d),
        RatePropagationJson.JSON.toJson(result));
    jdbc.update(
        "UPDATE m_savings_isr_rule SET revision=? WHERE id=? AND tenant_identifier=?",
        version,
        id,
        access.tenant());
    audit.event(null, id, "RULE_PROPOSED", actor, result);
    audit.finish(request.idempotencyKey(), result);
    return result;
  }

  static void validate(RuleDefinition d) {
    if (d == null
        || d.effectiveFrom() == null
        || d.effectiveThrough() == null
        || d.effectiveFrom().isAfter(d.effectiveThrough())
        || d.effectiveFrom().isBefore(ProductPolicyWorkflow.today()))
      throw invalid("definition", "Ordered, nonretroactive proposal validity dates required.");
    ConsolidatedIsrValidation.text("jurisdiction", d.jurisdiction(), true, 100);
    ConsolidatedIsrValidation.text("taxTreatment", d.taxTreatment(), true, 100);
    if (d.currency() == null || !d.currency().matches("[A-Z]{3}"))
      throw invalid("currency", "ISO currency code required.");
    for (var entry :
        Map.ofEntries(
                Map.entry(
                    "specificationReference",
                    java.util.Optional.ofNullable(d.specificationReference())),
                Map.entry("basisReference", java.util.Optional.ofNullable(d.basisReference())),
                Map.entry("periodReference", java.util.Optional.ofNullable(d.periodReference())),
                Map.entry("formulaReference", java.util.Optional.ofNullable(d.formulaReference())),
                Map.entry(
                    "allocationReference", java.util.Optional.ofNullable(d.allocationReference())),
                Map.entry(
                    "roundingReference", java.util.Optional.ofNullable(d.roundingReference())),
                Map.entry(
                    "accountingReference", java.util.Optional.ofNullable(d.accountingReference())))
            .entrySet())
      ConsolidatedIsrValidation.text(entry.getKey(), entry.getValue().orElse(null), false, 500);
    if ((d.specificationReference() == null) != (d.specificationSha256() == null)
        || (d.specificationSha256() != null && !d.specificationSha256().matches("[0-9a-f]{64}")))
      throw invalid(
          "specificationSha256",
          "A specification reference and SHA-256 must be supplied together.");
  }

  @Transactional(transactionManager = "jdbcTransactionManager", readOnly = true)
  public List<RuleVersion> versions(String id, long after, int limit) {
    access.authorize(ConsolidatedIsrAccess.RULES);
    ConsolidatedIsrValidation.uuid(id);
    ConsolidatedIsrValidation.size(after, limit);
    return jdbc
        .queryForList(
            "SELECT snapshot_json FROM m_savings_isr_rule_version WHERE rule_id=?"
                + " AND tenant_identifier=? AND version>? ORDER BY version LIMIT ?",
            String.class,
            id,
            access.tenant(),
            after,
            limit)
        .stream()
        .map(s -> RatePropagationJson.JSON.fromJson(s, RuleVersion.class))
        .toList();
  }

  Profile profile(long client, Integer legalForm) {
    // No customer classification source or tax aggregation boundary has been approved. In
    // particular, a proposal's jurisdiction/treatment must never be applied as a customer default.
    return new Profile(
        client, access.tenant(), legalForm, null, null, null, null, null, "UNVERIFIED", List.of());
  }
}
