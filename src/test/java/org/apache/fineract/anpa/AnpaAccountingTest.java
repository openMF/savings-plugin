/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Controlled evidence proves conservative classification, not a native accounting integration
 * claim.
 */
class AnpaAccountingTest {
  static final int DEBIT =
      org.apache.fineract.accounting.journalentry.domain.JournalEntryType.DEBIT.getValue();
  static final int CREDIT =
      org.apache.fineract.accounting.journalentry.domain.JournalEntryType.CREDIT.getValue();
  static final LocalDate DAY = LocalDate.of(2026, 11, 14);
  final AnpaWriteOffPreviewService engine =
      new AnpaWriteOffPreviewService(
          new AnpaHistoryService(),
          new AnpaReceivableClassificationService(),
          new AnpaAccountingReconciliationService());

  static BigDecimal money(String value) {
    return new BigDecimal(value);
  }

  static Loan loan(boolean npa, int status) {
    return new Loan(
        1,
        2L,
        null,
        3,
        4,
        status,
        npa,
        false,
        3,
        "USD",
        2,
        DAY.minusMonths(8),
        DAY.minusMonths(7),
        status == 601 ? DAY : null,
        DAY,
        new Balances(money("1000"), money("100"), money("12"), money("0"), money("1112")));
  }

  static Mapping mapping(long id, int type, long account) {
    return new Mapping(id, type, account, null, null, null, null, null, null, 1, 1, false);
  }

  static Journal journal(
      long id,
      Long transaction,
      long account,
      int direction,
      String amount,
      boolean reversed,
      Long reversal,
      boolean manual) {
    return new Journal(
        id,
        transaction,
        "L" + transaction,
        account,
        DAY,
        direction,
        money(amount),
        "USD",
        reversed,
        reversal,
        manual,
        DAY,
        null,
        null);
  }

  static Transaction transaction(long id, int type, boolean reversed, String amount) {
    return new Transaction(
        id, type, DAY, DAY, reversed, money(amount), null, null, null, null, null, null, null);
  }

  static Evidence evidence(
      Loan loan, List<Mapping> mappings, List<Transaction> transactions, List<Journal> journals) {
    if (transactions.isEmpty())
      transactions =
          journals.stream()
              .map(Journal::loanTransactionId)
              .filter(Objects::nonNull)
              .distinct()
              .map(id -> transaction(id, 10, false, "0"))
              .toList();
    return new Evidence(
        loan, mappings, transactions, List.of(), journals, List.of(), List.of(), List.of(), false);
  }

  Evidence standard(List<Journal> journals) {
    return evidence(
        loan(true, 300),
        List.of(mapping(1, 7, 107), mapping(2, 8, 108), mapping(3, 9, 109)),
        List.of(),
        journals);
  }

  Preview preview(Evidence e) {
    return engine.calculate("one", e, DAY);
  }

  BigDecimal balance(Preview p, Category category) {
    return p.receivables().stream()
        .filter(
            b ->
                b.category() == category
                    && b.currentClassification() == Classification.NATIVE_UNSEGREGATED)
        .findFirst()
        .orElseThrow()
        .netGlReceivable();
  }

  @Test
  void regularNpaUntilWriteoffRetainsPaymentsReversalsAndUnknownOrigin() {
    var e =
        standard(
            List.of(
                journal(1, 1L, 107, DEBIT, "120", false, null, false),
                journal(2, 2L, 107, CREDIT, "20", false, null, false),
                journal(3, 3L, 107, DEBIT, "30", true, 4L, false),
                journal(4, 3L, 107, CREDIT, "30", false, null, false),
                journal(5, 4L, 902, DEBIT, "55", false, null, false),
                journal(6, 5L, 107, DEBIT, "15", true, 7L, false),
                journal(7, 5L, 107, CREDIT, "15", false, null, false)));
    var p = preview(e);
    assertEquals(0, money("100").compareTo(balance(p, Category.INTEREST)));
    assertEquals(7, p.evidence().journals().size());
    assertTrue(
        p.receivables().stream()
            .anyMatch(b -> b.category() == Category.OTHER && b.accountIds().contains(902L)));
    assertNull(p.proposedReceivableExpense());
    assertFalse(p.financiallyExecutable());
    assertTrue(
        p.receivables().stream().allMatch(b -> b.historicalOrigin() == Classification.UNKNOWN));
    assertEquals(money("1000"), p.loan().currentOutstanding().principal());
  }

  @Test
  void npaExitDoesNotEraseOriginOrApproveManualReclassification() {
    var e =
        evidence(
            loan(false, 300),
            List.of(mapping(1, 7, 107)),
            List.of(),
            List.of(
                journal(1, 1L, 107, DEBIT, "50", false, null, false),
                    journal(2, 2L, 902, DEBIT, "70", false, null, false),
                journal(3, 3L, 902, CREDIT, "20", false, null, false),
                    journal(4, null, 902, CREDIT, "50", false, null, true),
                journal(5, null, 107, DEBIT, "50", false, null, true),
                    journal(6, 4L, 107, DEBIT, "25", false, null, false)));
    var p = preview(e);
    assertEquals(0, money("125").compareTo(balance(p, Category.INTEREST)));
    assertEquals(2, p.possibleReclassifications().size());
    assertTrue(p.blockers().contains(Reason.UNAPPROVED_RECLASSIFICATION_RULE));
    assertTrue(p.history().confirmedTransitions().isEmpty());
    assertFalse(p.history().currentNpa());
    assertNull(p.proposedReceivableExpense());
  }

  @Test
  void feesWaiversPaymentsAndTaxAreNotCombinedOrCountedTwice() {
    var base =
        standard(
            List.of(
                journal(1, 1L, 108, DEBIT, "30", false, null, false),
                journal(2, 2L, 108, CREDIT, "10", false, null, false),
                journal(3, 3L, 108, CREDIT, "8", false, null, false),
                journal(4, 4L, 108, DEBIT, "99", true, 5L, false),
                journal(5, 4L, 108, CREDIT, "99", false, null, false)));
    var charges =
        List.of(
            new Charge(
                1,
                2,
                false,
                true,
                false,
                money("30"),
                money("10"),
                money("8"),
                money("0"),
                money("12"),
                money("2.10"),
                "USD"),
            new Charge(
                2,
                3,
                false,
                true,
                false,
                money("5"),
                money("0"),
                money("0"),
                money("0"),
                money("5"),
                money("0"),
                "USD"));
    var e =
        new Evidence(
            base.loan(),
            base.mappings(),
            base.transactions(),
            base.relations(),
            base.journals(),
            charges,
            List.of(new ChargePayment(1, 2, 1, money("10"))),
            List.of(new Tax(1, 1, 10, money("2.10"), 501L, 502L)),
            false);
    var p = preview(e);
    assertEquals(0, money("12").compareTo(balance(p, Category.FEE_COMMISSION)));
    assertTrue(
        p.receivables().stream()
            .filter(b -> b.category() == Category.ITBMS)
            .allMatch(b -> b.netGlReceivable() == null));
    assertTrue(p.blockers().contains(Reason.TAX_TREATMENT_UNAPPROVED));
    assertEquals(1, p.evidence().taxes().size());
  }

  @Test
  void missingMappingDoesNotBecomeZero() {
    var p = preview(evidence(loan(true, 300), List.of(), List.of(), List.of()));
    assertNull(balance(p, Category.INTEREST));
    assertTrue(p.blockers().contains(Reason.RECEIVABLE_MAPPING_MISSING));
  }

  @Test
  void duplicateMappingIsAmbiguous() {
    var p =
        preview(
            evidence(
                loan(true, 300),
                List.of(mapping(1, 7, 107), mapping(2, 7, 207)),
                List.of(),
                List.of(journal(1, 1L, 107, DEBIT, "100", false, null, false))));
    assertNull(balance(p, Category.INTEREST));
    assertTrue(p.blockers().contains(Reason.AMBIGUOUS_MAPPING));
  }

  @Test
  void accountSharedWithPrincipalIsNotAReceivable() {
    var p =
        preview(
            evidence(
                loan(true, 300),
                List.of(mapping(1, 7, 107), mapping(2, 2, 107)),
                List.of(),
                List.of(journal(1, 1L, 107, DEBIT, "100", false, null, false))));
    assertTrue(p.blockers().contains(Reason.AMBIGUOUS_MAPPING));
    assertNull(balance(p, Category.INTEREST));
  }

  @Test
  void incorrectAccountTypeIsRejected() {
    var bad = new Mapping(1, 7, 107L, null, null, null, null, null, null, 4, 1, false);
    var p = preview(evidence(loan(true, 300), List.of(bad), List.of(), List.of()));
    assertTrue(p.blockers().contains(Reason.INVALID_RECEIVABLE_ACCOUNT));
  }

  @Test
  void disabledAccountIsRejected() {
    var bad = new Mapping(1, 7, 107L, null, null, null, null, null, null, 1, 1, true);
    var p = preview(evidence(loan(true, 300), List.of(bad), List.of(), List.of()));
    assertTrue(p.blockers().contains(Reason.INVALID_RECEIVABLE_ACCOUNT));
  }

  @Test
  void chargeSpecificMappingDoesNotReplaceBaseReceivable() {
    var bad = new Mapping(1, 7, 107L, 8L, null, null, null, null, null, 1, 1, false);
    var p = preview(evidence(loan(true, 300), List.of(bad), List.of(), List.of()));
    assertTrue(p.blockers().contains(Reason.RECEIVABLE_MAPPING_MISSING));
  }

  @Test
  void reversedTransactionsAndTheirJournalsAreExcluded() {
    var p =
        preview(
            evidence(
                loan(true, 300),
                List.of(mapping(1, 7, 107)),
                List.of(transaction(1, 10, true, "999"), transaction(2, 10, false, "100")),
                List.of(
                    journal(1, 1L, 107, DEBIT, "999", false, null, false),
                    journal(2, 2L, 107, DEBIT, "100", false, null, false))));
    assertEquals(0, money("100").compareTo(balance(p, Category.INTEREST)));
  }

  @Test
  void reversedWriteoffIsRetainedAsEvidence() {
    var p =
        preview(
            evidence(
                loan(false, 300),
                List.of(mapping(1, 7, 107)),
                List.of(transaction(1, 6, true, "100")),
                List.of(
                    journal(1, 1L, 107, CREDIT, "100", true, 2L, false),
                    journal(2, 1L, 107, DEBIT, "100", false, null, false))));
    assertEquals(1, p.evidence().transactions().size());
    assertNull(balance(p, Category.INTEREST));
    assertFalse(p.financiallyExecutable());
  }

  @Test
  void missingReversalEvidenceBlocks() {
    var p = preview(standard(List.of(journal(1, 1L, 107, DEBIT, "100", true, 2L, false))));
    assertTrue(p.blockers().contains(Reason.GL_EVIDENCE_INCOMPLETE));
  }

  @Test
  void currencyMismatchDoesNotProduceConvertedBalance() {
    var j =
        new Journal(
            1, 1L, "L1", 107, DAY, 1, money("100"), "EUR", false, null, false, DAY, null, null);
    var p = preview(standard(List.of(j)));
    assertNull(balance(p, Category.INTEREST));
    assertTrue(p.blockers().contains(Reason.CURRENCY_MISMATCH));
  }

  @Test
  void nativeMismatchIsNotAssignedToRegularInterest() {
    var p = preview(standard(List.of(journal(1, 1L, 107, DEBIT, "85", false, null, false))));
    var r =
        p.reconciliation().stream()
            .filter(v -> v.category() == Category.INTEREST)
            .findFirst()
            .orElseThrow();
    assertEquals(0, money("15").compareTo(r.difference()));
    assertEquals(State.RECONCILIATION_REQUIRED, p.state());
    assertNull(p.proposedReceivableExpense());
  }

  @Test
  void historicalPreviewDoesNotCompareCurrentLoanBalances() {
    var p = engine.calculate("one", standard(List.of()), DAY.minusDays(1));
    assertTrue(p.blockers().contains(Reason.HISTORICAL_SNAPSHOT_UNAVAILABLE));
    assertTrue(p.reconciliation().stream().allMatch(r -> r.difference() == null));
  }

  @Test
  void currentNpaNeverInventsTransitionDates() {
    var p = preview(standard(List.of()));
    assertTrue(p.history().currentNpa());
    assertTrue(p.history().knownPeriods().isEmpty());
    assertEquals(Classification.UNKNOWN, p.history().unknownPeriods().getFirst().state());
    assertEquals(DAY.minusMonths(7), p.history().unknownPeriods().getFirst().from());
  }

  @Test
  void writtenOffLoanRemainsReviewableWithoutNewExecutionEligibility() {
    var e =
        evidence(loan(true, 601), List.of(), List.of(transaction(1, 6, false, "100")), List.of());
    var p = preview(e);
    var eligibility = engine.eligibility(e, p.blockers());
    assertTrue(eligibility.diagnosticEligible());
    assertFalse(eligibility.nativeWriteOffCandidate());
    assertFalse(eligibility.financiallyExecutable());
  }

  @Test
  void incompleteHistoryAndTruncationNeverPass() {
    var b = standard(List.of());
    var e =
        new Evidence(
            b.loan(),
            b.mappings(),
            b.transactions(),
            b.relations(),
            b.journals(),
            b.charges(),
            b.chargePayments(),
            b.taxes(),
            true);
    var p = preview(e);
    assertTrue(p.blockers().contains(Reason.EVIDENCE_LIMIT_EXCEEDED));
    assertEquals(State.INCOMPLETE_EVIDENCE, p.state());
  }

  @Test
  void evidenceFingerprintStableAcrossCaptureTimesAndSensitiveToCorrection() {
    var e = standard(List.of());
    assertEquals(preview(e).sourceFingerprint(), preview(e).sourceFingerprint());
    var changed = standard(List.of(journal(1, 1L, 107, DEBIT, "1", false, null, false)));
    assertNotEquals(preview(e).sourceFingerprint(), preview(changed).sourceFingerprint());
  }

  @Test
  void unmatchedJournalTransactionNeverVerifiesABalance() {
    var e =
        evidence(
            loan(true, 300),
            List.of(mapping(1, 7, 107)),
            List.of(transaction(5, 10, false, "1")),
            List.of(journal(1, 99L, 107, DEBIT, "100", false, null, false)));
    var p = preview(e);
    assertNull(balance(p, Category.INTEREST));
    assertTrue(p.blockers().contains(Reason.GL_EVIDENCE_INCOMPLETE));
  }

  @Test
  void missingGlForNativeAccrualBlocks() {
    var p =
        preview(
            evidence(
                loan(true, 300),
                List.of(mapping(1, 7, 107)),
                List.of(transaction(5, 10, false, "100")),
                List.of()));
    assertTrue(p.blockers().contains(Reason.GL_EVIDENCE_INCOMPLETE));
  }

  @Test
  void dtoRoundTripPreservesExactAmountsAndUnknowns() {
    var p = preview(standard(List.of(journal(1, 1L, 107, DEBIT, "12.123456", false, null, false))));
    assertEquals(p, AnpaSupport.JSON.fromJson(AnpaSupport.JSON.toJson(p), Preview.class));
  }
}
