/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;

import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reuses BE-02 bindings and SINGLE slots; never opens, activates, or changes a native account. */
@Service
@RequiredArgsConstructor
public class MainAccountService {
  public static final String READ = "READ_SAVINGS_ACCOUNT_RELATIONSHIP";
  public static final String BIND = "BIND_MAIN_SAVINGS_ACCOUNT";
  private final PlatformSecurityContext security;
  private final JdbcTemplate jdbc;
  private final ProductPolicyWorkflow workflow;
  private final ProductTransactionPolicyResolver resolver;
  private final SavingsAccountRepositoryWrapper accounts;
  private final MainAccountAuditService evidence;

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS', 'READ_SAVINGS_ACCOUNT_RELATIONSHIP')")
  public MainAccountData.Capabilities template() {
    authorize(READ);
    return MainAccountData.capabilities();
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS', 'READ_SAVINGS_ACCOUNT_RELATIONSHIP')")
  @Transactional
  public MainAccountData.Relationship get(long client) {
    authorize(READ);
    visibleClient(client, false, true);
    var candidates =
        jdbc.queryForList(
            "SELECT a.id FROM m_savings_account a JOIN m_savings_account_policy b ON"
                + " b.savings_account_id=a.id WHERE a.client_id=? AND b.purpose='MAIN'"
                + " AND a.status_enum IN (100,200,300) ORDER BY a.id",
            Long.class,
            client);
    if (candidates.isEmpty()) return null; // No implicit adoption of legacy accounts.
    if (candidates.size() != 1)
      throw invalid("multiplicity", "Multiple open Main Accounts require administrative repair.");
    return relationship(client, candidates.getFirst());
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS', 'BIND_MAIN_SAVINGS_ACCOUNT')")
  @Transactional
  public MainAccountData.Relationship bind(long client, String json) {
    // Native EclipseLink uses a shared JDBC connection. Fresh reads after lock waits are required.
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    long actor = authorize(BIND);
    var request = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(request, Set.of("savingsAccountId"));
    ProductPolicyValidation.integer(request.get("savingsAccountId"), "savingsAccountId");
    long account = request.get("savingsAccountId").getAsLong();
    if (account <= 0) throw invalid("savingsAccountId", "A positive account ID is required.");
    visibleClient(client, false, true);
    var initial = account(client, account, false);
    long product = number(initial, "product_id");
    // Same product -> client lock order as native policy opening, followed by the account row.
    workflow.lock(product);
    visibleClient(client, true, true);
    var locked = account(client, account, true);
    if (number(locked, "product_id") != product)
      throw invalid("productId", "Account product changed; retry with current metadata.");
    var result = relationship(client, account);
    long others =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_account a LEFT JOIN m_savings_account_policy b ON"
                + " b.savings_account_id=a.id WHERE a.client_id=? AND a.id<>? AND a.status_enum"
                + " IN (100,200,300) AND (a.product_id=? OR b.purpose='MAIN')",
            Long.class,
            client,
            account,
            product);
    if (others != 0)
      throw invalid(
          "multiplicity", "An open or pending account already exists for this product or purpose.");
    var slots =
        jdbc.queryForList(
            "SELECT savings_account_id FROM m_savings_policy_opening_slot WHERE client_id=? AND purpose='MAIN'",
            Long.class,
            client);
    if (!slots.isEmpty() && slots.getFirst() != account) {
      long old = slots.getFirst();
      var prior = account(client, old, true);
      if (open(number(prior, "status_enum")))
        throw invalid(
            "multiplicity", "An opening reservation already exists for this client and purpose.");
      // History is append-only. Retire only terminal native accounts, never a pending reservation.
      evidence.record(client, old, actor, "RETIRE");
      jdbc.update(
          "DELETE FROM m_savings_policy_opening_slot WHERE client_id=? AND purpose='MAIN'", client);
      slots = List.of();
    }
    if (slots.isEmpty())
      jdbc.update(
          "INSERT INTO m_savings_policy_opening_slot(client_id,purpose,savings_account_id) VALUES (?,'MAIN',?)",
          client,
          account);
    evidence.record(client, account, actor, "BIND");
    return result;
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS', 'READ_SAVINGS_ACCOUNT_RELATIONSHIP')")
  @Transactional(readOnly = true)
  public List<MainAccountData.Audit> audit(long client, long after, int limit) {
    authorize(READ);
    visibleClient(client, false, false);
    if (after < 0 || limit < 1 || limit > 100)
      throw invalid("pagination", "Use afterId >= 0 and limit 1-100.");
    return jdbc.query(
        "SELECT * FROM m_savings_main_account_audit WHERE client_id=? AND id>? ORDER BY id LIMIT ?",
        (rs, n) ->
            new MainAccountData.Audit(
                rs.getLong("id"),
                client,
                rs.getLong("savings_account_id"),
                rs.getLong("savings_product_id"),
                rs.getLong("policy_version"),
                rs.getString("action"),
                rs.getLong("actor_id"),
                rs.getString("tenant_identifier"),
                rs.getTimestamp("recorded_at").toLocalDateTime()),
        client,
        after,
        limit);
  }

  private MainAccountData.Relationship relationship(long client, long id) {
    var row = account(client, id, false);
    var owners =
        jdbc.queryForList(
            "SELECT client_id FROM m_savings_policy_opening_slot WHERE savings_account_id=?"
                + " UNION SELECT client_id FROM m_savings_main_account_audit WHERE savings_account_id=?",
            Long.class,
            id,
            id);
    if (owners.stream().anyMatch(owner -> owner != client))
      throw invalid("clientId", "An established Main Account relationship cannot change customer.");
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_main_account_audit WHERE savings_account_id=? AND action='RETIRE'",
            Long.class,
            id)
        != 0)
      throw invalid("savingsAccountId", "A retired Main Account relationship cannot be rebound.");
    long status = number(row, "status_enum");
    if (!open(status) || row.get("group_id") != null || number(row, "deposit_type_enum") != 100)
      throw invalid(
          "savingsAccountId", "An open or pending individual savings account is required.");
    var nativeAccount = accounts.findOneWithNotFoundDetection(id);
    if (!Long.valueOf(client).equals(nativeAccount.clientId())
        || nativeAccount.groupId() != null
        || nativeAccount.productId() != number(row, "product_id"))
      throw invalid(
          "savingsAccountId", "Native account identity conflicts with persisted ownership.");
    var resolved = resolver.resolve(nativeAccount);
    resolved.decision().requireAllowed();
    var policy = resolved.policy();
    if (policy == null
        || resolved.version() == null
        || policy.purpose() != ProductPolicyData.Purpose.MAIN
        || policy.multiplicity() != ProductPolicyData.Multiplicity.SINGLE
        || policy.freezing() != ProductPolicyData.Freezing.NONE)
      throw invalid(
          "purpose", "A bound, effective, approved SINGLE Main Account policy is required.");
    // Metadata configuration is retained; no provider or reference generation is implied.
    return new MainAccountData.Relationship(
        client, id, resolved.productId(), resolved.version(), (int) status, status == 300);
  }

  private long authorize(String permission) {
    var actor = security.authenticatedUser();
    actor.validateHasPermissionTo(permission);
    MainAccountAuditService.requireContext();
    return actor.getId();
  }

  private void visibleClient(long client, boolean lock, boolean requireActive) {
    if (client <= 0) throw invalid("clientId", "A positive client ID is required.");
    var rows =
        jdbc.queryForList(
            "SELECT c.status_enum FROM m_client c JOIN m_office o ON o.id=c.office_id"
                + " WHERE c.id=? AND o.hierarchy LIKE ?"
                + (lock ? " FOR UPDATE" : ""),
            Integer.class,
            client,
            security.authenticatedUser().getOffice().getHierarchy() + "%");
    if (rows.size() != 1) throw new ClientNotFoundException(client);
    if (requireActive && rows.getFirst() != 300)
      throw invalid("clientId", "An active client is required.");
  }

  private Map<String, Object> account(long client, long account, boolean lock) {
    var rows =
        jdbc.queryForList(
            "SELECT product_id,status_enum,group_id,deposit_type_enum FROM m_savings_account"
                + " WHERE id=? AND client_id=?"
                + (lock ? " FOR UPDATE" : ""),
            account,
            client);
    if (rows.size() != 1)
      throw invalid("savingsAccountId", "Account does not belong to this client.");
    return rows.getFirst();
  }

  private static boolean open(long status) {
    return status == 100 || status == 200 || status == 300;
  }

  private static long number(Map<String, Object> row, String field) {
    return ((Number) row.get(field)).longValue();
  }
}
