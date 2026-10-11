package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.CashExchangeDenominationData;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.*;
import org.apache.fineract.baseteller.data.EndOfDayClosingRequest.*;
import org.junit.jupiter.api.Test;

class EndOfDayClosingCalculatorTest {
  private final Currency currency =
      new Currency(
          "AAA",
          2,
          List.of(
              new CashExchangeDenominationData(
                  "bill", "BILL", new BigDecimal("100"), 0L, BigDecimal.ZERO),
              new CashExchangeDenominationData(
                  "coin", "COIN", new BigDecimal("0.50"), 0L, BigDecimal.ZERO)));

  private Balance calculate(
      List<Quantity> quantities,
      BigDecimal vouchers,
      List<CheckSelection> selections,
      List<Check> checks,
      String expected) {
    return EndOfDayClosingCalculator.calculate(
        new CurrencyDeclaration("AAA", quantities, vouchers, selections),
        currency,
        new BigDecimal(expected),
        BigDecimal.ZERO,
        checks);
  }

  @Test
  void balancedShortageAndOverageUseExplicitNonNegativeAmounts() {
    for (String count : List.of("10", "9.5", "10.5")) {
      // Integral denomination counts, varying coins to produce the 950/1000/1050 examples.
      int coins = count.equals("10") ? 200 : count.equals("9.5") ? 100 : 300;
      var b =
          calculate(
              List.of(
                  new Quantity("bill", new BigDecimal("9")),
                  new Quantity("coin", BigDecimal.valueOf(coins))),
              BigDecimal.ZERO,
              List.of(),
              List.of(),
              "1000");
      assertEquals(0, b.shortage().compareTo(new BigDecimal(count.equals("9.5") ? "50" : "0")));
      assertEquals(0, b.overage().compareTo(new BigDecimal(count.equals("10.5") ? "50" : "0")));
    }
  }

  @Test
  void cashChecksAndVouchersRemainSeparate() {
    var check =
        new Check(
            "CREDIT",
            1L,
            "2026-01-01",
            "AAA",
            "PERSONAL",
            "Bank",
            "reference",
            "user",
            2L,
            new BigDecimal("200"));
    var b =
        calculate(
            List.of(new Quantity("bill", new BigDecimal("5"))),
            new BigDecimal("300"),
            List.of(new CheckSelection("CREDIT", 1L)),
            List.of(check),
            "1000");
    assertEquals(new BigDecimal("500"), b.declaredCash());
    assertEquals(new BigDecimal("200"), b.selectedChecks());
    assertEquals(new BigDecimal("300"), b.vouchers());
    assertEquals(new BigDecimal("1000"), b.declaredTotal());
    assertEquals("BALANCED", b.reconciliationStatus());
  }

  @Test
  void invalidDisabledAndWrongCurrencyDenominationsAreRejected() {
    for (String id : List.of("unknown", "disabled", "otherCurrency"))
      assertThrows(
          RuntimeException.class,
          () ->
              calculate(
                  List.of(new Quantity(id, BigDecimal.ONE)),
                  BigDecimal.ZERO,
                  List.of(),
                  List.of(),
                  "0"));
  }

  @Test
  void negativeFractionalAndOverflowQuantitiesAreRejected() {
    for (String q : List.of("-1", "0.5", "9223372036854775808", "999999999999"))
      assertThrows(
          RuntimeException.class,
          () ->
              calculate(
                  List.of(new Quantity("bill", new BigDecimal(q))),
                  BigDecimal.ZERO,
                  List.of(),
                  List.of(),
                  "0"));
  }

  @Test
  void duplicateDenominationsAreRejectedAndZeroLinesAreOmitted() {
    assertThrows(
        RuntimeException.class,
        () ->
            calculate(
                List.of(new Quantity("bill", BigDecimal.ONE), new Quantity("bill", BigDecimal.ONE)),
                BigDecimal.ZERO,
                List.of(),
                List.of(),
                "0"));
    assertTrue(
        calculate(
                List.of(new Quantity("bill", BigDecimal.ZERO)),
                BigDecimal.ZERO,
                List.of(),
                List.of(),
                "0")
            .denominations()
            .isEmpty());
    assertEquals(
        "BALANCED",
        calculate(List.of(), BigDecimal.ZERO, List.of(), List.of(), "0").reconciliationStatus());
  }

  @Test
  void duplicateMissingReturnedAndOutsideCustodyChecksAreRejected() {
    var check =
        new Check(
            "DEPOSIT", 1L, "2026-01-01", "AAA", "TYPE", "Bank", "ref", "user", 2L, BigDecimal.TEN);
    assertThrows(
        RuntimeException.class,
        () ->
            calculate(
                List.of(),
                BigDecimal.ZERO,
                List.of(new CheckSelection("DEPOSIT", 1L), new CheckSelection("DEPOSIT", 1L)),
                List.of(check),
                "0"));
    for (String source : List.of("RETURNED", "ANOTHER_CASHIER", "ANOTHER_OFFICE", "ALREADY_CLOSED"))
      assertThrows(
          RuntimeException.class,
          () ->
              calculate(
                  List.of(),
                  BigDecimal.ZERO,
                  List.of(new CheckSelection(source, 1L)),
                  List.of(check),
                  "0"));
  }

  @Test
  void vouchersRejectNegativePrecisionAndOverflow() {
    for (String v : List.of("-1", "0.001", "10000000000000"))
      assertThrows(
          RuntimeException.class,
          () -> calculate(List.of(), new BigDecimal(v), List.of(), List.of(), "0"));
  }

  @Test
  void existingBanknoteAndNoteCatalogTypesAreBills() {
    for (String type : List.of("NOTE", "BANKNOTE")) {
      var c =
          new Currency(
              "AAA",
              2,
              List.of(
                  new CashExchangeDenominationData(
                      "note", type, BigDecimal.TEN, 0L, BigDecimal.ZERO)));
      var b =
          EndOfDayClosingCalculator.calculate(
              new CurrencyDeclaration(
                  "AAA", List.of(new Quantity("note", BigDecimal.ONE)), BigDecimal.ZERO, List.of()),
              c,
              BigDecimal.TEN,
              BigDecimal.ZERO,
              List.of());
      assertEquals(BigDecimal.TEN, b.billTotal());
      assertEquals(BigDecimal.ZERO, b.coinTotal());
    }
  }

  @Test
  void currenciesAreIndependent() {
    var a =
        calculate(
            List.of(new Quantity("bill", BigDecimal.ONE)),
            BigDecimal.ZERO,
            List.of(),
            List.of(),
            "50");
    var other =
        new Currency(
            "BBB",
            2,
            List.of(
                new CashExchangeDenominationData(
                    "small", "COIN", new BigDecimal("2"), 0L, BigDecimal.ZERO)));
    var b =
        EndOfDayClosingCalculator.calculate(
            new CurrencyDeclaration(
                "BBB",
                List.of(new Quantity("small", new BigDecimal("5"))),
                BigDecimal.ZERO,
                List.of()),
            other,
            new BigDecimal("60"),
            BigDecimal.ZERO,
            List.of());
    assertEquals(new BigDecimal("50"), a.overage());
    assertEquals(new BigDecimal("50"), b.shortage());
    assertEquals("AAA", a.currencyCode());
    assertEquals("BBB", b.currencyCode());
  }
}
