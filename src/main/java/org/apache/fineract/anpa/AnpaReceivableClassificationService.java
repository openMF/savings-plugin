/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;

import java.math.BigDecimal;
import java.util.*;
import org.apache.fineract.accounting.common.AccountingConstants.AccrualAccountsForLoan;
import org.apache.fineract.accounting.glaccount.domain.GLAccountType;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryType;
import org.springframework.stereotype.Component;

/**
 * Native placeholders establish the category, not historical regular/NPA origin or approved expense
 * policy.
 */
@Component
public class AnpaReceivableClassificationService {
  record Result(List<AccountBucket> buckets, List<Reason> blockers) {}

  Result classify(Evidence e) {
    var reasons = new LinkedHashSet<Reason>();
    var result = new ArrayList<AccountBucket>();
    var reversed = new HashSet<Long>();
    var journalIds =
        e.journals().stream().map(Journal::id).collect(java.util.stream.Collectors.toSet());
    for (var j : e.journals()) {
      if (j.reversed()) {
        reversed.add(j.id());
        if (j.reversalId() == null) reasons.add(Reason.GL_EVIDENCE_INCOMPLETE);
      }
      if (j.reversalId() != null) {
        reversed.add(j.reversalId());
        if (!journalIds.contains(j.reversalId())) reasons.add(Reason.GL_EVIDENCE_INCOMPLETE);
      }
    }
    var reversedTransactions = new HashSet<Long>();
    e.transactions().stream()
        .filter(Transaction::reversed)
        .forEach(t -> reversedTransactions.add(t.id()));
    var transactionIds =
        e.transactions().stream().map(Transaction::id).collect(java.util.stream.Collectors.toSet());
    var active =
        e.journals().stream()
            .filter(
                j ->
                    !reversed.contains(j.id())
                        && !reversedTransactions.contains(j.loanTransactionId()))
            .toList();
    for (var category : Category.values()) {
      if (category == Category.OTHER) {
        var mapped =
            e.mappings().stream()
                .map(Mapping::accountId)
                .collect(java.util.stream.Collectors.toSet());
        var unclassified = active.stream().filter(j -> !mapped.contains(j.accountId())).toList();
        result.add(
            new AccountBucket(
                category,
                Classification.UNKNOWN,
                MappingStatus.UNCLASSIFIED_SOURCE,
                List.of(),
                unclassified.stream().map(Journal::accountId).distinct().toList(),
                unclassified.stream().map(Journal::id).toList(),
                unclassified.stream()
                    .map(Journal::loanTransactionId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList(),
                null,
                null,
                Classification.UNKNOWN));
        if (!unclassified.isEmpty()) reasons.add(Reason.GL_EVIDENCE_INCOMPLETE);
        continue;
      }
      if (category == Category.ITBMS) {
        result.add(
            unknown(category, Classification.REGULAR, MappingStatus.UNAPPROVED_TAX_SEMANTICS));
        result.add(unknown(category, Classification.NPA, MappingStatus.NATIVE_MAPPING_UNAVAILABLE));
        reasons.add(Reason.TAX_TREATMENT_UNAPPROVED);
        continue;
      }
      int placeholder =
          switch (category) {
            case INTEREST -> AccrualAccountsForLoan.INTEREST_RECEIVABLE.getValue();
            case FEE_COMMISSION -> AccrualAccountsForLoan.FEES_RECEIVABLE.getValue();
            case PENALTY -> AccrualAccountsForLoan.PENALTIES_RECEIVABLE.getValue();
            default -> throw new IllegalStateException();
          };
      var maps =
          e.mappings().stream()
              .filter(m -> m.financialAccountType() == placeholder && base(m))
              .toList();
      MappingStatus status = MappingStatus.NATIVE_UNSEGREGATED;
      if (maps.isEmpty()) {
        reasons.add(Reason.RECEIVABLE_MAPPING_MISSING);
        status = MappingStatus.MISSING;
      } else if (maps.size() != 1 || shared(e, maps.getFirst())) {
        reasons.add(Reason.AMBIGUOUS_MAPPING);
        status = MappingStatus.AMBIGUOUS;
      } else if (!valid(maps.getFirst())) {
        reasons.add(Reason.INVALID_RECEIVABLE_ACCOUNT);
        status = MappingStatus.INVALID_ACCOUNT;
      }
      var accountIds =
          maps.stream().map(Mapping::accountId).filter(Objects::nonNull).distinct().toList();
      var journals = active.stream().filter(j -> accountIds.contains(j.accountId())).toList();
      boolean usable = status.equals(MappingStatus.NATIVE_UNSEGREGATED) && !e.truncated();
      if (journals.stream()
          .anyMatch(
              j ->
                  j.loanTransactionId() != null
                      && !transactionIds.contains(j.loanTransactionId()))) {
        reasons.add(Reason.GL_EVIDENCE_INCOMPLETE);
        usable = false;
      }
      if (journals.stream().anyMatch(j -> !e.loan().currency().equals(j.currency()))) {
        reasons.add(Reason.CURRENCY_MISMATCH);
        usable = false;
      }
      if (journals.stream()
          .anyMatch(
              j ->
                  j.amount() == null
                      || j.amount().signum() < 0
                      || (j.direction() != JournalEntryType.DEBIT.getValue()
                          && j.direction() != JournalEntryType.CREDIT.getValue()))) {
        reasons.add(Reason.GL_EVIDENCE_INCOMPLETE);
        usable = false;
      }
      // No journal lines does not establish a zero receivable balance in incomplete native history.
      if (journals.isEmpty()) {
        reasons.add(Reason.GL_EVIDENCE_INCOMPLETE);
        usable = false;
      }
      BigDecimal net =
          usable
              ? journals.stream()
                  .map(
                      j ->
                          j.direction() == JournalEntryType.DEBIT.getValue()
                              ? j.amount()
                              : j.amount().negate())
                  .reduce(BigDecimal.ZERO, BigDecimal::add)
              : null;
      if (net != null && net.signum() < 0) reasons.add(Reason.RECONCILIATION_DIFFERENCE);
      result.add(
          new AccountBucket(
              category,
              Classification.NATIVE_UNSEGREGATED,
              status,
              maps.stream().map(Mapping::mappingId).toList(),
              accountIds,
              journals.stream().map(Journal::id).toList(),
              journals.stream()
                  .map(Journal::loanTransactionId)
                  .filter(Objects::nonNull)
                  .distinct()
                  .toList(),
              net,
              null,
              Classification.UNKNOWN));
      result.add(
          unknown(
              category, Classification.REGULAR, MappingStatus.APPROVED_SEGREGATION_UNAVAILABLE));
      result.add(unknown(category, Classification.NPA, MappingStatus.NATIVE_MAPPING_UNAVAILABLE));
      reasons.add(Reason.NPA_RECEIVABLE_MAPPING_MISSING);
    }
    reasons.add(Reason.ORIGIN_ALLOCATION_UNAVAILABLE);
    return new Result(List.copyOf(result), List.copyOf(reasons));
  }

  private static AccountBucket unknown(
      Category c, Classification classification, MappingStatus status) {
    return new AccountBucket(
        c,
        classification,
        status,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null,
        null,
        Classification.UNKNOWN);
  }

  private static boolean base(Mapping m) {
    return m.chargeId() == null
        && m.paymentType() == null
        && m.chargeOffReason() == null
        && m.writeOffReason() == null
        && m.capitalizedClassification() == null
        && m.buydownClassification() == null;
  }

  private static boolean valid(Mapping m) {
    return m.accountId() != null
        && Objects.equals(m.accountType(), GLAccountType.ASSET.getValue())
        && Objects.equals(m.accountUsage(), 1)
        && Boolean.FALSE.equals(m.disabled());
  }

  private static boolean shared(Evidence e, Mapping m) {
    return e.mappings().stream()
        .anyMatch(
            other ->
                other.mappingId() != m.mappingId()
                    && Objects.equals(other.accountId(), m.accountId())
                    && other.financialAccountType() != m.financialAccountType());
  }
}
