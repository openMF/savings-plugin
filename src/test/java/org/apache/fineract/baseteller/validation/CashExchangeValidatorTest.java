package org.apache.fineract.baseteller.validation;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.CashExchangeDenominationData;
import org.apache.fineract.baseteller.data.CashExchangeQuantityData;
import org.apache.fineract.baseteller.data.CashExchangeRequest;
import org.junit.jupiter.api.Test;

class CashExchangeValidatorTest {
  private CashExchangeRequest request(List<CashExchangeQuantityData> lines) {
    return new CashExchangeRequest(1L, "EUR", lines, lines, "test-key");
  }

  @Test
  void acceptsIntegerCountsAndZeroRows() {
    CashExchangeValidator.validate(
        request(List.of(new CashExchangeQuantityData("note", 0L))), true);
  }

  @Test
  void rejectsNegativeCounts() {
    assertThrows(
        RuntimeException.class,
        () ->
            CashExchangeValidator.validate(
                request(List.of(new CashExchangeQuantityData("note", -1L))), true));
  }

  @Test
  void rejectsDuplicates() {
    assertThrows(
        RuntimeException.class,
        () ->
            CashExchangeValidator.validate(
                request(
                    List.of(
                        new CashExchangeQuantityData("note", 1L),
                        new CashExchangeQuantityData("note", 2L))),
                true));
  }

  @Test
  void rejectsEmptySides() {
    assertThrows(
        RuntimeException.class, () -> CashExchangeValidator.validate(request(List.of()), true));
  }

  @Test
  void rejectsMissingKeyOnCreate() {
    var r = request(List.of(new CashExchangeQuantityData("note", 1L)));
    assertThrows(
        RuntimeException.class,
        () ->
            CashExchangeValidator.validate(
                new CashExchangeRequest(
                    r.cashierId(),
                    r.currencyCode(),
                    r.receivedDenominations(),
                    r.deliveredDenominations(),
                    null),
                true));
  }

  @Test
  void previewDoesNotRequireKey() {
    var r = request(List.of(new CashExchangeQuantityData("note", 1L)));
    CashExchangeValidator.validate(
        new CashExchangeRequest(
            r.cashierId(),
            r.currencyCode(),
            r.receivedDenominations(),
            r.deliveredDenominations(),
            null),
        false);
  }

  @Test
  void equalityIgnoresScale() {
    CashExchangeValidator.balanced(new BigDecimal("1000"), new BigDecimal("1000.00"));
  }

  @Test
  void rejectsOneCentDifference() {
    assertThrows(
        RuntimeException.class,
        () -> CashExchangeValidator.balanced(new BigDecimal("1000"), new BigDecimal("999.99")));
  }

  @Test
  void rejectsZeroExchange() {
    assertThrows(
        RuntimeException.class,
        () -> CashExchangeValidator.balanced(BigDecimal.ZERO, BigDecimal.ZERO));
  }

  @Test
  void rejectsPersistenceOverflow() {
    assertThrows(
        RuntimeException.class,
        () ->
            CashExchangeValidator.balanced(
                new BigDecimal("10000000000000"), new BigDecimal("10000000000000")));
  }

  @Test
  void totalsUseExactDecimalArithmetic() {
    assertEquals(
        0,
        new BigDecimal("0.30")
            .compareTo(
                CashExchangeValidator.total(
                    List.of(
                        new CashExchangeDenominationData(
                            "coin", "COIN", new BigDecimal("0.10"), 3L, new BigDecimal("0.30"))))));
  }
}
