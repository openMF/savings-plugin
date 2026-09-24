package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.ServicePaymentCommissionType;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.junit.jupiter.api.Test;

class ServicePaymentCalculatorTest {

  @Test
  void calculatesFixedCommissionAndVatAuthoritatively() {
    final ServicePaymentAmounts result =
        ServicePaymentCalculator.calculate(
            new BigDecimal("100.00"),
            ServicePaymentCommissionType.FIXED,
            new BigDecimal("5.00"),
            new BigDecimal("13.00"),
            2);

    assertEquals(new BigDecimal("100.00"), result.baseAmount());
    assertEquals(new BigDecimal("5.00"), result.commission());
    assertEquals(new BigDecimal("0.65"), result.commissionVat());
    assertEquals(new BigDecimal("105.65"), result.totalToPay());
  }

  @Test
  void calculatesPercentageCommissionWithCurrencyRounding() {
    final ServicePaymentAmounts result =
        ServicePaymentCalculator.calculate(
            new BigDecimal("99.99"),
            ServicePaymentCommissionType.PERCENTAGE,
            new BigDecimal("2.50"),
            new BigDecimal("13"),
            2);

    assertEquals(new BigDecimal("2.50"), result.commission());
    assertEquals(new BigDecimal("0.32"), result.commissionVat());
    assertEquals(new BigDecimal("102.81"), result.totalToPay());
  }

  @Test
  void supportsZeroCommissionWithoutInventingVat() {
    final ServicePaymentAmounts result =
        ServicePaymentCalculator.calculate(
            new BigDecimal("100"),
            ServicePaymentCommissionType.NONE,
            BigDecimal.ZERO,
            new BigDecimal("13"),
            2);

    assertEquals(new BigDecimal("0.00"), result.commission());
    assertEquals(new BigDecimal("0.00"), result.commissionVat());
    assertEquals(new BigDecimal("100.00"), result.totalToPay());
  }

  @Test
  void rejectsInvalidPrecisionAndNegativeConfiguration() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            ServicePaymentCalculator.calculate(
                new BigDecimal("1.001"),
                ServicePaymentCommissionType.NONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                2));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            ServicePaymentCalculator.calculate(
                BigDecimal.ONE,
                ServicePaymentCommissionType.FIXED,
                new BigDecimal("-1"),
                BigDecimal.ZERO,
                2));
  }

  @Test
  void calculatesExactAndExcessTenderFromBillsAndCoins() {
    final ServicePaymentCashAmounts exact =
        ServicePaymentCalculator.calculateCash(
            new BigDecimal("105.50"),
            List.of(denomination("bill-100", "100", 1), denomination("coin-050", "0.50", 11)));
    final ServicePaymentCashAmounts excess =
        ServicePaymentCalculator.calculateCash(
            new BigDecimal("105.50"), List.of(denomination("bill-200", "200", 1)));

    assertEquals(new BigDecimal("105.50"), exact.amountReceived());
    assertEquals(new BigDecimal("0.00"), exact.change());
    assertEquals(new BigDecimal("94.50"), excess.change());
  }

  @Test
  void rejectsInsufficientTender() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            ServicePaymentCalculator.calculateCash(
                new BigDecimal("100.00"), List.of(denomination("bill-50", "50", 1))));
  }

  private static BaseTellerDenominationData denomination(
      final String id, final String value, final long quantity) {
    return new BaseTellerDenominationData(id, new BigDecimal(value), quantity);
  }
}
