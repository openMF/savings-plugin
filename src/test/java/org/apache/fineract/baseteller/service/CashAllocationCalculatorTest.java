package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.CashAllocationDenominationData;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.junit.jupiter.api.Test;

class CashAllocationCalculatorTest {

  @Test
  void calculatesMultipleBanknotesAndCoinsWithBackendValues() {
    final List<CashAllocationDenominationData> result =
        CashAllocationCalculator.calculate(
            List.of(request("bill-100", null, 2), request("coin-025", null, 3)),
            List.of(config("bill-100", "BANKNOTE", "100.00"), config("coin-025", "COIN", "0.25")),
            2);

    assertEquals(new BigDecimal("200.75"), CashAllocationCalculator.total(result));
    assertEquals(new BigDecimal("200.00"), result.get(0).subtotal());
    assertEquals(new BigDecimal("0.75"), result.get(1).subtotal());
  }

  @Test
  void independentlySupportsDifferentConfiguredCurrenciesAndPrecision() {
    final List<CashAllocationDenominationData> zeroDecimal =
        CashAllocationCalculator.calculate(
            List.of(request("note", null, 3)), List.of(config("note", "BANKNOTE", "500")), 0);
    final List<CashAllocationDenominationData> threeDecimal =
        CashAllocationCalculator.calculate(
            List.of(request("coin", null, 4)), List.of(config("coin", "COIN", "0.125")), 3);

    assertEquals(new BigDecimal("1500"), CashAllocationCalculator.total(zeroDecimal));
    assertEquals(new BigDecimal("0.500"), CashAllocationCalculator.total(threeDecimal));
  }

  @Test
  void zeroQuantityProducesExactZeroSubtotal() {
    final List<CashAllocationDenominationData> result =
        CashAllocationCalculator.calculate(
            List.of(request("bill", null, 0)), List.of(config("bill", "BANKNOTE", "20.00")), 2);

    assertEquals(new BigDecimal("0.00"), result.get(0).subtotal());
  }

  @Test
  void rejectsUnsupportedDenomination() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            CashAllocationCalculator.calculate(
                List.of(request("unknown", null, 1)), List.of(config("bill", "BANKNOTE", "20")), 2));
  }

  @Test
  void rejectsClientValueThatDiffersFromConfiguration() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            CashAllocationCalculator.calculate(
                List.of(request("bill", "50", 1)), List.of(config("bill", "BANKNOTE", "20")), 2));
  }

  @Test
  void rejectsInvalidConfiguredPrecisionAndNegativeQuantity() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            CashAllocationCalculator.calculate(
                List.of(request("coin", null, 1)), List.of(config("coin", "COIN", "0.001")), 2));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            CashAllocationCalculator.calculate(
                List.of(request("bill", null, -1)), List.of(config("bill", "BANKNOTE", "20")), 2));
  }

  private static BaseTellerDenominationData request(
      final String id, final String value, final long quantity) {
    return new BaseTellerDenominationData(id, value == null ? null : new BigDecimal(value), quantity);
  }

  private static CashAllocationDenominationData config(
      final String id, final String type, final String value) {
    return new CashAllocationDenominationData(id, type, new BigDecimal(value), 0L, BigDecimal.ZERO);
  }
}
