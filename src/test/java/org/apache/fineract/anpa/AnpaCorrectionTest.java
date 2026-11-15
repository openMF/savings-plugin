/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the Mozilla Public License, v. 2.0. If a copy of the MPL
 * was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;
import static org.apache.fineract.anpa.AnpaAccountingTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Synthetic approved policies and verified allocations exercise arithmetic, not native execution.
 */
class AnpaCorrectionTest {
  final AnpaWriteOffPreviewService engine = new AnpaAccountingTest().engine;

  Loan outstanding(String interest, int digits) {
    var l = loan(true, 300);
    return new Loan(
        l.loanId(),
        l.clientId(),
        l.groupId(),
        l.productId(),
        l.officeId(),
        l.nativeStatus(),
        l.currentNpa(),
        l.chargedOff(),
        l.accountingRule(),
        l.currency(),
        digits,
        l.submittedOn(),
        l.disbursedOn(),
        l.writtenOffOn(),
        l.businessDate(),
        new Balances(
            money("1000"),
            interest == null ? null : money(interest),
            money("0"),
            money("0"),
            null));
  }

  PolicyRule rule(
      Classification origin, Classification current, long account, Boolean reclassified) {
    return new PolicyRule(
        Category.INTEREST,
        origin,
        current,
        account,
        null,
        606,
        origin == Classification.REGULAR,
        reclassified,
        null);
  }

  PolicyResolution policy(List<PolicyRule> rules) {
    return new PolicyResolution(
        "synthetic",
        new PolicyDefinition(
            1, 3, "USD", DAY.minusYears(1), null, "Synthetic fixture approval only", rules),
        true,
        true,
        false,
        List.of());
  }

  AccountBucket bucket(
      Classification origin, Classification current, String amount, long account, long journal) {
    return new AccountBucket(
        Category.INTEREST,
        current,
        MappingStatus.VERIFIED_SOURCE_EVIDENCE,
        List.of(),
        List.of(account),
        List.of(journal),
        List.of(journal),
        amount == null ? null : money(amount),
        null,
        origin);
  }

  ExpenseCategory interest(ExpenseAttribution a) {
    return a.categories().stream()
        .filter(c -> c.category() == Category.INTEREST)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void case1OnlyOutstandingRegularInterestIsEligible() {
    var p =
        policy(
            List.of(
                rule(Classification.REGULAR, Classification.REGULAR, 107, null),
                rule(Classification.NPA, Classification.NPA, 207, null)));
    var result =
        engine.expense(
            outstanding("115", 2),
            List.of(
                bucket(Classification.REGULAR, Classification.REGULAR, "75", 107, 1),
                bucket(Classification.NPA, Classification.NPA, "40", 207, 2)),
            p);
    assertEquals(money("75.00"), interest(result).eligible());
    assertEquals(money("40.00"), interest(result).excluded());
    assertEquals(money("115.00"), interest(result).outstanding());
    assertNull(result.totalExpense(), "Unknown other categories cannot become zero");
    assertFalse(result.financiallyExecutable());
  }

  @Test
  void regularOnlyAndNpaOnlyUseDifferentAmountsWithoutHardcodedBasis() {
    for (String amount : List.of("0", "21.37", "1750.99")) {
      for (var origin : List.of(Classification.REGULAR, Classification.NPA)) {
        var result =
            interest(
                engine.expense(
                    outstanding(amount, 2),
                    List.of(bucket(origin, origin, amount, 107, 1)),
                    policy(List.of(rule(origin, origin, 107, null)))));
        assertEquals(
            origin == Classification.REGULAR ? money(amount).setScale(2) : money("0"),
            result.eligible());
        assertEquals(
            origin == Classification.NPA ? money(amount).setScale(2) : money("0"),
            result.excluded());
      }
    }
  }

  @Test
  void case2PaymentsAndReclassificationPreserveNpaOrigin() {
    var result =
        engine.expense(
            outstanding("105", 2),
            List.of(
                bucket(Classification.REGULAR, Classification.REGULAR, "30", 107, 1),
                bucket(Classification.NPA, Classification.REGULAR, "50", 107, 2),
                bucket(Classification.REGULAR, Classification.REGULAR, "25", 107, 3)),
            policy(
                List.of(
                    rule(Classification.REGULAR, Classification.REGULAR, 107, null),
                    rule(Classification.NPA, Classification.REGULAR, 107, false))));
    assertEquals(money("55.00"), interest(result).eligible());
    assertEquals(money("50.00"), interest(result).excluded());
    assertEquals(money("50.00"), interest(result).reclassifiedNpa());
    assertEquals(money("50.00"), interest(result).npaOutstanding());
  }

  @Test
  void case2RequiresExplicitReclassifiedEligibilityEvenInRegularAccount() {
    var row =
        interest(
            engine.expense(
                outstanding("50", 2),
                List.of(bucket(Classification.NPA, Classification.REGULAR, "50", 107, 1)),
                policy(List.of(rule(Classification.NPA, Classification.REGULAR, 107, null)))));
    assertNull(row.eligible());
    assertTrue(row.blockers().contains(Reason.UNAPPROVED_RECLASSIFICATION_RULE));
  }

  @Test
  void unknownOriginOrDuplicateAllocationCannotBeExpensed() {
    var buckets =
        List.of(
            bucket(Classification.REGULAR, Classification.REGULAR, "50", 107, 1),
            bucket(Classification.REGULAR, Classification.REGULAR, "50", 107, 1));
    var row =
        interest(
            engine.expense(
                outstanding("100", 2),
                buckets,
                policy(List.of(rule(Classification.REGULAR, Classification.REGULAR, 107, null)))));
    assertNull(row.eligible());
    assertTrue(row.blockers().contains(Reason.GL_EVIDENCE_INCOMPLETE));
    var unknown =
        interest(
            engine.expense(
                outstanding("50", 2),
                List.of(bucket(Classification.UNKNOWN, Classification.REGULAR, "50", 107, 1)),
                policy(List.of(rule(Classification.UNKNOWN, Classification.REGULAR, 107, null)))));
    assertTrue(unknown.blockers().contains(Reason.NPA_HISTORY_UNAVAILABLE));
    assertNull(unknown.eligible());
  }

  @Test
  void unknownNegativeOverprecisionAndMismatchedOutstandingStayBlocked() {
    for (String amount : Arrays.asList(null, "-1", "1.001", "99")) {
      var row =
          interest(
              engine.expense(
                  outstanding("100", 2),
                  List.of(bucket(Classification.REGULAR, Classification.REGULAR, amount, 107, 1)),
                  policy(
                      List.of(rule(Classification.REGULAR, Classification.REGULAR, 107, null)))));
      assertNull(row.eligible());
      assertFalse(row.blockers().isEmpty());
    }
  }

  @Test
  void currencyPrecisionUsesExactDecimalWithoutSilentRounding() {
    var row =
        interest(
            engine.expense(
                outstanding("12.345", 3),
                List.of(bucket(Classification.REGULAR, Classification.REGULAR, "12.345", 107, 1)),
                policy(List.of(rule(Classification.REGULAR, Classification.REGULAR, 107, null)))));
    assertEquals(new BigDecimal("12.345"), row.eligible());
  }

  @Test
  void approvalDoesNotVerifyOriginOrNativeIntegration() {
    var l = outstanding("100", 2);
    var p =
        new AnpaAccountingTest()
            .preview(
                evidence(
                    l,
                    List.of(mapping(1, 7, 107)),
                    List.of(),
                    List.of(journal(1, 1L, 107, DEBIT, "100", false, null, false))));
    var result =
        engine.expense(
            l,
            p.receivables(),
            policy(List.of(rule(Classification.REGULAR, Classification.REGULAR, 107, null))));
    assertNull(interest(result).eligible());
    assertNull(result.totalExpense());
    assertFalse(result.financiallyExecutable());
  }

  @Test
  void unapprovedOrInvalidPolicyNeverProducesExpense() {
    for (boolean approved : List.of(true, false)) {
      var p =
          new PolicyResolution(
              null,
              policy(List.of(rule(Classification.REGULAR, Classification.REGULAR, 107, null)))
                  .definition(),
              approved,
              false,
              false,
              List.of());
      assertNull(
          interest(
                  engine.expense(
                      outstanding("10", 2),
                      List.of(bucket(Classification.REGULAR, Classification.REGULAR, "10", 107, 1)),
                      p))
              .eligible());
    }
  }

  @Test
  void controlHashIgnoresObjectKeyOrderButPreservesPayloadMeaning() {
    assertEquals(
        AnpaSupport.controlHash(JsonParser.parseString("{\"a\":1,\"b\":2}")),
        AnpaSupport.controlHash(JsonParser.parseString("{\"b\":2,\"a\":1}")));
    assertNotEquals(
        AnpaSupport.controlHash(Map.of("a", "1")), AnpaSupport.controlHash(Map.of("a", "2")));
    assertThrows(
        RuntimeException.class,
        () -> AnpaSupport.object("{\"amount\":1}", Set.of("idempotencyKey")));
    assertThrows(
        RuntimeException.class,
        () ->
            AnpaSupport.text(
                JsonParser.parseString("{\"reason\":12}").getAsJsonObject(), "reason"));
  }

  @Test
  void effectiveDatesDetectOverlapIncludingUnboundedPolicies() {
    var a = policy(List.of()).definition();
    var b = new PolicyDefinition(2, 3, "USD", DAY, DAY.plusDays(1), "fixture", List.of());
    assertTrue(AnpaAccountingPolicyService.overlaps(a, b));
    var c = new PolicyDefinition(3, 3, "USD", DAY.plusDays(2), null, "fixture", List.of());
    assertFalse(AnpaAccountingPolicyService.overlaps(b, c));
  }

  Evidence writtenOff(boolean reversed, String currency, boolean unbalanced, boolean recovery) {
    var t =
        new Transaction(
            10,
            6,
            DAY,
            DAY,
            reversed,
            money("1115"),
            money("1000"),
            money("115"),
            money("0"),
            money("0"),
            reversed ? DAY : null,
            null,
            null);
    var transactions = new ArrayList<Transaction>();
    transactions.add(t);
    if (recovery) transactions.add(transaction(11, 8, false, "10"));
    var expense = new Mapping(3, 6, 606L, null, null, null, null, null, null, 5, 1, false);
    var debit =
        new Journal(
            3,
            10L,
            "L10",
            606,
            DAY,
            DEBIT,
            money(unbalanced ? "1114" : "1115"),
            currency,
            false,
            null,
            false,
            DAY,
            null,
            null);
    return evidence(
        loan(true, 601),
        List.of(
            mapping(1, 2, 102),
            mapping(2, 7, 107),
            mapping(4, 8, 108),
            mapping(5, 9, 109),
            expense),
        transactions,
        List.of(
            journal(1, 10L, 102, CREDIT, "1000", false, null, false),
            journal(2, 10L, 107, CREDIT, "115", false, null, false),
            debit));
  }

  WriteOffReconciliation reconcile(Evidence e) {
    return new AnpaAccountingReconciliationService().writeOffs(e, null, null, null).getFirst();
  }

  @Test
  void nativeExpenseIsSeparateFromInterestAndApprovedExpectedExpense() {
    var r = reconcile(writtenOff(false, "USD", false, false));
    assertEquals(money("1115"), r.nativeWriteOffAmount());
    assertEquals(money("1115"), r.actualExpenseDebit());
    var interest =
        r.categories().stream()
            .filter(c -> c.category() == Category.INTEREST)
            .findFirst()
            .orElseThrow();
    assertEquals(money("115"), interest.actual());
    assertEquals(money("0"), interest.difference());
    assertNull(r.expectedExpenseDebit());
    assertNull(r.expenseDifference());
    assertEquals("MISSING_HISTORICAL_EVIDENCE", r.state());
  }

  @Test
  void reversedUnbalancedOrWrongCurrencyJournalsCannotVerifyExpense() {
    assertNull(reconcile(writtenOff(true, "USD", false, false)).actualExpenseDebit());
    assertNull(reconcile(writtenOff(false, "USD", true, false)).actualExpenseDebit());
    assertNull(reconcile(writtenOff(false, "EUR", false, false)).actualExpenseDebit());
    assertTrue(
        reconcile(writtenOff(true, "USD", false, false))
            .blockers()
            .contains(Reason.REVERSED_WRITE_OFF));
    var recovered = reconcile(writtenOff(false, "USD", false, true));
    assertEquals(List.of(11L), recovered.recoveryTransactionIds());
    assertTrue(recovered.blockers().contains(Reason.RECOVERY_AFTER_WRITE_OFF));
  }
}
