/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ConsolidatedIsrData.*;
import static org.apache.fineract.savings.productpolicy.ConsolidatedIsrEligibilityService.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Observes native ledger amounts. No available-balance, daily-average or taxable-basis inference.
 */
@Component
@RequiredArgsConstructor
public class ConsolidatedIsrBalanceService {
  static final int MAX_TRANSACTIONS = 20000;
  private final JdbcTemplate jdbc;
  private final ConsolidatedIsrAccess access;
  private final ConsolidatedIsrEligibilityService eligibility;

  Sources capture(long client, Map<String, Object> customer, Period period) {
    var accounts = eligibility.accounts(client, period);
    var reasons = new ArrayList<Reason>();
    if (accounts.isEmpty()) reasons.add(Reason.NO_ACCOUNTS);
    if (accounts.size() > MAX_ACCOUNTS) reasons.add(Reason.ACCOUNT_POPULATION_LIMIT_EXCEEDED);
    var transactions =
        jdbc.query(
            "SELECT t.id,t.savings_account_id,t.transaction_type_enum,"
                + "t.transaction_date,t.amount,t.is_reversed,t.is_reversal,t.original_transaction_id,"
                + "t.running_balance_derived FROM m_savings_account_transaction t"
                + " JOIN m_savings_account a ON a.id=t.savings_account_id"
                + " JOIN m_client c ON c.id=a.client_id JOIN m_office o ON o.id=c.office_id WHERE a.client_id=? AND o.hierarchy LIKE ?"
                + " AND t.transaction_date<=? ORDER BY t.id LIMIT ?",
            (rs, n) ->
                new Transaction(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getInt(3),
                    rs.getDate(4).toLocalDate(),
                    rs.getBigDecimal(5),
                    rs.getBoolean(6),
                    rs.getBoolean(7),
                    rs.getObject(8) == null ? null : rs.getLong(8),
                    rs.getBigDecimal(9)),
            client,
            access.hierarchy(),
            period.through(),
            MAX_TRANSACTIONS + 1);
    var details =
        jdbc.query(
            "SELECT d.id,d.savings_transaction_id,d.tax_component_id,d.amount"
                + " FROM m_savings_account_transaction_tax_details d"
                + " JOIN m_savings_account_transaction t ON t.id=d.savings_transaction_id"
                + " JOIN m_savings_account a ON a.id=t.savings_account_id"
                + " JOIN m_client c ON c.id=a.client_id JOIN m_office o ON o.id=c.office_id WHERE a.client_id=? AND o.hierarchy LIKE ?"
                + " AND t.transaction_date<=? ORDER BY d.id LIMIT ?",
            (rs, n) ->
                new TaxDetail(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4)),
            client,
            access.hierarchy(),
            period.through(),
            MAX_TRANSACTIONS + 1);
    if (transactions.size() > MAX_TRANSACTIONS || details.size() > MAX_TRANSACTIONS)
      reasons.add(Reason.SOURCE_TRANSACTION_LIMIT_EXCEEDED);
    if (accounts.stream().anyMatch(a -> a.observedLedgerBalance() == null)
        || transactions.stream().anyMatch(t -> t.amount() == null))
      reasons.add(Reason.SOURCE_DATA_UNAVAILABLE);
    boolean complete = reasons.isEmpty() || reasons.equals(List.of(Reason.NO_ACCOUNTS));
    if (accounts.stream().map(Account::currency).distinct().count() > 1)
      reasons.add(Reason.CURRENCY_AGGREGATION_UNAPPROVED);
    reasons.add(Reason.HISTORICAL_BALANCE_BASIS_UNAPPROVED);
    var observations =
        complete ? aggregate(accounts, transactions, period) : List.<CurrencyObservation>of();
    String fingerprint =
        ConsolidatedIsrValidation.hash(
            List.of(
                access.tenant(),
                customer,
                period,
                accounts,
                transactions,
                details,
                complete,
                reasons));
    return new Sources(
        accounts, transactions, details, observations, complete, reasons, fingerprint);
  }

  /** Group by native currency AND precision. Never sum an account twice or add transfer flows. */
  static List<CurrencyObservation> aggregate(
      List<Account> accounts, List<Transaction> transactions, Period period) {
    var unique = new HashSet<Long>();
    var groups = new TreeMap<String, List<Account>>();
    for (var a : accounts) {
      if (!unique.add(a.accountId()))
        throw new IllegalArgumentException("Duplicate native account.");
      if (a.currency() == null
          || a.currencyDigits() < 0
          || a.currencyDigits() > 6
          || a.observedLedgerBalance() == null) return List.of();
      groups
          .computeIfAbsent(a.currency() + ":" + a.currencyDigits(), k -> new ArrayList<>())
          .add(a);
    }
    var ids = new HashSet<Long>();
    for (var t : transactions) {
      if (!ids.add(t.id()) || !unique.contains(t.accountId()))
        throw new IllegalArgumentException("Invalid source transaction identity.");
    }
    return groups.values().stream()
        .map(
            group -> {
              var groupIds =
                  group.stream()
                      .map(Account::accountId)
                      .collect(java.util.stream.Collectors.toSet());
              BigDecimal balance =
                  group.stream()
                      .map(Account::observedLedgerBalance)
                      .reduce(BigDecimal.ZERO, BigDecimal::add);
              BigDecimal interest = BigDecimal.ZERO, tax = BigDecimal.ZERO;
              for (var t : transactions) {
                if (!groupIds.contains(t.accountId())
                    || t.reversed()
                    || t.reversal()
                    || t.date().isBefore(period.from())
                    || t.date().isAfter(period.through())) continue;
                if (t.type() == SavingsAccountTransactionType.INTEREST_POSTING.getValue())
                  interest = interest.add(t.amount());
                if (t.type() == SavingsAccountTransactionType.WITHHOLD_TAX.getValue())
                  tax = tax.add(t.amount());
              }
              var first = group.getFirst();
              return new CurrencyObservation(
                  first.currency(), first.currencyDigits(), group.size(), balance, interest, tax);
            })
        .sorted(
            Comparator.comparing(CurrencyObservation::currency)
                .thenComparingInt(CurrencyObservation::currencyDigits))
        .toList();
  }
}
