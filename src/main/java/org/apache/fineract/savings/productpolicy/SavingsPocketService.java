/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.PlannedSavingsAccess.number;
import static org.apache.fineract.savings.productpolicy.ProductPolicyValidation.invalid;

import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multiple genuine native POCKET accounts, with immutable customer/Main identity and no balances.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SavingsPocketService {
  private final JdbcTemplate jdbc;
  private final PlannedSavingsAccess access;
  private final ProductPolicyWorkflow workflow;
  private final MainAccountService mainAccounts;
  private final ProductTransactionPolicyEvaluator restrictions;
  private final PlannedSavingsAuditService audit;

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','MANAGE_SAVINGS_POCKET')")
  public PlannedSavingsData.Pocket save(long client, long account, String json) {
    long actor = access.authorize(PlannedSavingsAccess.POCKET);
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    var request = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(
        request,
        Set.of(
            "expectedRevision",
            "mainSavingsAccountId",
            "policyVersion",
            "mainPolicyVersion",
            "name"));
    long revision = PlannedSavingsValidation.revision(request),
        main = PlannedSavingsValidation.positive(request, "mainSavingsAccountId"),
        version = PlannedSavingsValidation.positive(request, "policyVersion"),
        mainVersion = PlannedSavingsValidation.positive(request, "mainPolicyVersion");
    String name = PlannedSavingsValidation.text(request, "name", 100);
    access.client(client, false, true);
    if (account <= 0 || account == main)
      throw invalid("savingsAccountId", "Distinct positive native account IDs are required.");
    lockProducts(client, account, main);
    access.client(client, true, true);
    var lockedAccounts = new HashMap<Long, PlannedSavingsAccess.Account>();
    for (long id : new TreeSet<>(List.of(account, main)))
      lockedAccounts.put(id, access.account(client, id, true));
    var pocket = lockedAccounts.get(account);
    var destination = lockedAccounts.get(main);
    access.requirePolicy(pocket, ProductPolicyData.Purpose.POCKET, version);
    access.requirePolicy(destination, ProductPolicyData.Purpose.MAIN, mainVersion);
    var mainRelationship = mainAccounts.get(client);
    if (mainRelationship == null
        || mainRelationship.savingsAccountId() != main
        || mainRelationship.policyVersion() != mainVersion)
      throw invalid(
          "mainSavingsAccountId", "The customer's current BE-08 Main Account is required.");
    if (!Objects.equals(pocket.row().get("currency_code"), destination.row().get("currency_code"))
        || number(pocket.row(), "currency_digits") != number(destination.row(), "currency_digits"))
      throw invalid("currency", "Pocket and Main currencies/precision must agree.");
    var prior =
        jdbc.queryForList(
            "SELECT * FROM m_savings_pocket_relationship WHERE savings_account_id=? FOR UPDATE",
            account);
    if (prior.isEmpty()) {
      if (revision != 0) throw new PlannedSavingsService.Conflict();
      jdbc.update(
          "INSERT INTO m_savings_pocket_relationship(savings_account_id,client_id,main_savings_account_id,"
              + "savings_product_id,policy_version,main_product_id,main_policy_version,revision,name,status) VALUES (?,?,?,?,?,?,?,1,?,'LINKED')",
          account,
          client,
          main,
          pocket.product(),
          version,
          destination.product(),
          mainVersion,
          name);
    } else {
      var old = prior.getFirst();
      if (number(old, "client_id") != client
          || number(old, "main_savings_account_id") != main
          || number(old, "policy_version") != version
          || number(old, "main_policy_version") != mainVersion
          || !"LINKED".equals(old.get("status")))
        throw invalid(
            "relationship",
            "An established pocket cannot change owner, Main, policy binding, or be rebound after retirement.");
      if (number(old, "revision") != revision) throw new PlannedSavingsService.Conflict();
      jdbc.update(
          "UPDATE m_savings_pocket_relationship SET revision=revision+1,name=? WHERE savings_account_id=?",
          name,
          account);
    }
    var result = view(client, account);
    evidence(result, actor, prior.isEmpty() ? "LINK_POCKET" : "RENAME_POCKET");
    return result;
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','MANAGE_SAVINGS_POCKET')")
  public PlannedSavingsData.Pocket retire(long client, long account, String json) {
    long actor = access.authorize(PlannedSavingsAccess.POCKET);
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    var request = ProductPolicyValidation.object(json);
    ProductPolicyValidation.fields(request, Set.of("expectedRevision"));
    long revision = PlannedSavingsValidation.revision(request);
    access.client(client, false, false);
    var initial = row(client, account);
    lockProducts(client, account, number(initial, "main_savings_account_id"));
    access.client(client, true, false);
    var nativeAccount = access.account(client, account, true);
    var current = row(client, account);
    if (number(current, "revision") != revision) throw new PlannedSavingsService.Conflict();
    if (nativeAccount.open()
        || nativeAccount.row().get("account_balance_derived") == null
        || ((BigDecimal) nativeAccount.row().get("account_balance_derived")).signum() != 0
        || !"LINKED".equals(current.get("status")))
      throw invalid(
          "status",
          "Only a terminal native zero-balance account relationship can be retired; this does not close an account.");
    jdbc.update(
        "UPDATE m_savings_pocket_relationship SET status='RETIRED',revision=revision+1 WHERE savings_account_id=?",
        account);
    var result = view(client, account);
    evidence(result, actor, "RETIRE_POCKET");
    return result;
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_PLANNED_SAVINGS')")
  public PlannedSavingsData.Pocket get(long client, long account) {
    access.authorize(PlannedSavingsAccess.READ);
    access.client(client, false, false);
    return view(client, account);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_PLANNED_SAVINGS')")
  public List<PlannedSavingsData.Pocket> list(long client, long after, int limit) {
    access.authorize(PlannedSavingsAccess.READ);
    access.client(client, false, false);
    PlannedSavingsValidation.page(after, limit);
    return jdbc
        .queryForList(
            "SELECT savings_account_id FROM m_savings_pocket_relationship WHERE client_id=? AND savings_account_id>? ORDER BY savings_account_id LIMIT ?",
            Long.class,
            client,
            after,
            limit)
        .stream()
        .map(id -> view(client, id))
        .toList();
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_PLANNED_SAVINGS')")
  public PlannedSavingsData.Eligibility transferPreview(
      long client, long account, BigDecimal amount) {
    access.authorize(PlannedSavingsAccess.READ);
    access.client(client, false, false);
    var relationship = row(client, account);
    long main = number(relationship, "main_savings_account_id");
    lockProducts(client, account, main);
    var source = access.account(client, account, false);
    var destination = access.account(client, main, false);
    int digits = source.nativeAccount().getCurrency().getDigitsAfterDecimal();
    if (!ProductTransactionPolicyEvaluator.validMoney(amount, digits) || amount.signum() <= 0)
      throw invalid(
          "amount", "A positive exact amount in the native currency precision is required.");
    var blockers = new ArrayList<String>();
    if (!"LINKED".equals(relationship.get("status"))) blockers.add("POCKET_RELATIONSHIP_RETIRED");
    var s = access.snapshot(source, client);
    var d = access.snapshot(destination, client);
    blockers.addAll(s.blockers());
    blockers.addAll(d.blockers());
    if (!Objects.equals(s.currency(), d.currency()) || s.currencyDigits() != d.currencyDigits())
      blockers.add("CURRENCY_MISMATCH");
    if (s.policyVersion() == null
        || s.policyVersion() != number(relationship, "policy_version")
        || !"POCKET".equals(s.purpose())
        || d.policyVersion() == null
        || d.policyVersion() != number(relationship, "main_policy_version")
        || !"MAIN".equals(d.purpose())) blockers.add("RELATIONSHIP_POLICY_BINDING_CHANGED");
    var currentMain = mainAccounts.get(client);
    if (currentMain == null || currentMain.savingsAccountId() != main)
      blockers.add("CURRENT_MAIN_RELATIONSHIP_UNAVAILABLE");
    if (s.withdrawableBalance() == null) blockers.add("NATIVE_WITHDRAWABLE_BALANCE_UNAVAILABLE");
    else if (amount.compareTo(s.withdrawableBalance()) > 0)
      blockers.add("INSUFFICIENT_NATIVE_WITHDRAWABLE_FUNDS");
    var date = ProductPolicyWorkflow.today();
    var out =
        restrictions.evaluate(
            source.resolved().policy(),
            ProductTransactionPolicyData.Operation.WITHDRAWAL,
            amount,
            digits,
            date,
            date);
    var in =
        restrictions.evaluate(
            destination.resolved().policy(),
            ProductTransactionPolicyData.Operation.DEPOSIT,
            amount,
            digits,
            date,
            date);
    if (!out.allowed()) blockers.add(out.code());
    if (!in.allowed()) blockers.add(in.code());
    blockers.add("POCKET_TRANSFER_DIRECTION_AND_NATIVE_ACCOUNTING_COVERAGE_UNAPPROVED");
    return new PlannedSavingsData.Eligibility(
        "BLOCKED_PENDING_POLICY", false, date, blockers.stream().distinct().toList());
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_PLANNED_SAVINGS')")
  public PlannedSavingsData.NativeAccount account(long client, long id) {
    access.authorize(PlannedSavingsAccess.READ);
    access.client(client, false, false);
    requireRelationship(client, id);
    return access.snapshot(access.account(client, id, false), client);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_PLANNED_SAVINGS')")
  public PlannedSavingsData.Eligibility closure(long client, long id) {
    var snapshot = account(client, id);
    var blockers = new ArrayList<>(snapshot.blockers());
    if (snapshot.balance() == null) blockers.add("NATIVE_BALANCE_UNAVAILABLE");
    else if (snapshot.balance().signum() != 0) blockers.add("NATIVE_BALANCE_NOT_ZERO");
    if (snapshot.nativeInterestEarned() == null
        || snapshot.nativeInterestPosted() == null
        || snapshot.nativeInterestEarned().compareTo(snapshot.nativeInterestPosted()) != 0)
      blockers.add("UNPOSTED_INTEREST_OR_INTEREST_RECONCILIATION_UNVERIFIED");
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_pocket_relationship WHERE main_savings_account_id=? AND status='LINKED'",
            Long.class,
            id)
        > 0) blockers.add("LINKED_POCKET_RELATIONSHIPS_EXIST");
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_planned_arrangement WHERE savings_account_id=?",
            Long.class,
            id)
        > 0) blockers.add("CONTRIBUTION_OBLIGATIONS_AND_SETTLEMENT_UNVERIFIED");
    blockers.add("PENDING_NATIVE_COMMANDS_FREEZES_LINKS_AND_CLOSURE_PERMISSION_UNVERIFIED");
    blockers.add("NATIVE_CLOSURE_EXECUTION_DISABLED");
    return new PlannedSavingsData.Eligibility(
        "CLOSURE_BLOCKED",
        false,
        ProductPolicyWorkflow.today(),
        blockers.stream().distinct().toList());
  }

  private void requireRelationship(long client, long id) {
    if (jdbc.queryForObject(
                "SELECT COUNT(*) FROM m_savings_pocket_relationship WHERE savings_account_id=? AND client_id=?",
                Long.class,
                id,
                client)
            == 0
        && jdbc.queryForObject(
                "SELECT COUNT(*) FROM m_savings_planned_arrangement WHERE savings_account_id=? AND client_id=?",
                Long.class,
                id,
                client)
            == 0) throw invalid("savingsAccountId", "A BE-10 relationship is required.");
  }

  private PlannedSavingsData.Pocket view(long client, long id) {
    var r = row(client, id);
    var account = access.account(client, id, false);
    // Ownership of both native accounts is checked even when a relationship is historical.
    access.account(client, number(r, "main_savings_account_id"), false);
    return new PlannedSavingsData.Pocket(
        id,
        client,
        number(r, "main_savings_account_id"),
        number(r, "revision"),
        number(r, "policy_version"),
        number(r, "main_policy_version"),
        (String) r.get("name"),
        (String) r.get("status"),
        access.snapshot(account, client));
  }

  private Map<String, Object> row(long client, long id) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM m_savings_pocket_relationship WHERE savings_account_id=? AND client_id=?",
            id,
            client);
    if (rows.size() != 1)
      throw invalid("savingsAccountId", "Pocket relationship is unavailable for this client.");
    return rows.getFirst();
  }

  private void lockProducts(long client, long source, long destination) {
    var products =
        jdbc.queryForList(
            "SELECT DISTINCT product_id FROM m_savings_account WHERE client_id=? AND id IN (?,?) ORDER BY product_id",
            Long.class,
            client,
            source,
            destination);
    for (long product : products) workflow.lock(product);
  }

  private void evidence(PlannedSavingsData.Pocket p, long actor, String action) {
    var r = row(p.clientId(), p.savingsAccountId());
    audit.record(
        p.clientId(),
        "POCKET",
        Long.toString(p.savingsAccountId()),
        p.savingsAccountId(),
        number(r, "savings_product_id"),
        p.policyVersion(),
        actor,
        action,
        "METADATA_ONLY",
        Map.ofEntries(
            Map.entry("savingsAccountId", p.savingsAccountId()),
            Map.entry("mainSavingsAccountId", p.mainSavingsAccountId()),
            Map.entry("clientId", p.clientId()),
            Map.entry("policyVersion", p.policyVersion()),
            Map.entry("mainPolicyVersion", p.mainPolicyVersion()),
            Map.entry("revision", p.revision()),
            Map.entry("name", p.name()),
            Map.entry("relationshipStatus", p.relationshipStatus())));
  }
}
