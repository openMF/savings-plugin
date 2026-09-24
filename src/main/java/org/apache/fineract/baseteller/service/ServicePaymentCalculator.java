package org.apache.fineract.baseteller.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.ServicePaymentCommissionType;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;

public final class ServicePaymentCalculator {

  private ServicePaymentCalculator() {}

  public static ServicePaymentAmounts calculate(
      final BigDecimal baseAmount,
      final ServicePaymentCommissionType commissionType,
      final BigDecimal commissionValue,
      final BigDecimal vatRate,
      final int decimalPlaces) {
    if (baseAmount == null || baseAmount.signum() <= 0) {
      throw invalid("amount.invalid", "baseAmount must be greater than zero.");
    }
    if (baseAmount.stripTrailingZeros().scale() > decimalPlaces) {
      throw invalid(
          "amount.precision.invalid", "baseAmount exceeds the currency decimal precision.");
    }
    if (commissionType == null
        || commissionValue == null
        || commissionValue.signum() < 0
        || vatRate == null
        || vatRate.signum() < 0) {
      throw invalid(
          "service.configuration.invalid",
          "Commission and VAT configuration must not be negative.");
    }
    final BigDecimal normalizedBase = baseAmount.setScale(decimalPlaces, RoundingMode.UNNECESSARY);
    final BigDecimal commission =
        switch (commissionType) {
          case NONE -> BigDecimal.ZERO.setScale(decimalPlaces);
          case FIXED -> commissionValue.setScale(decimalPlaces, RoundingMode.HALF_EVEN);
          case PERCENTAGE ->
              normalizedBase
                  .multiply(commissionValue)
                  .divide(BigDecimal.valueOf(100), decimalPlaces, RoundingMode.HALF_EVEN);
        };
    final BigDecimal vat =
        commission
            .multiply(vatRate)
            .divide(BigDecimal.valueOf(100), decimalPlaces, RoundingMode.HALF_EVEN);
    return new ServicePaymentAmounts(
        normalizedBase, commission, vat, normalizedBase.add(commission).add(vat));
  }

  public static ServicePaymentCashAmounts calculateCash(
      final BigDecimal totalToPay, final List<BaseTellerDenominationData> denominations) {
    final BigDecimal amountReceived =
        denominations.stream()
            .map(item -> item.value().multiply(BigDecimal.valueOf(item.quantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (amountReceived.compareTo(totalToPay) < 0) {
      throw invalid("cash.insufficient", "Cash received is less than totalToPay.");
    }
    return new ServicePaymentCashAmounts(amountReceived, amountReceived.subtract(totalToPay));
  }

  private static GeneralPlatformDomainRuleException invalid(
      final String code, final String message) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.base.teller.service.payment." + code, message);
  }
}
