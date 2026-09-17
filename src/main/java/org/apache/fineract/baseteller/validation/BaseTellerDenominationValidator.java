package org.apache.fineract.baseteller.validation;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;

public final class BaseTellerDenominationValidator {

  private BaseTellerDenominationValidator() {}

  public static BigDecimal validateCash(
      final BigDecimal amount, final List<BaseTellerDenominationData> denominations) {
    if (denominations == null || denominations.isEmpty()) {
      throw invalid("cash.denominations.required", "Cash denominations are required.");
    }
    BigDecimal total = BigDecimal.ZERO;
    for (BaseTellerDenominationData denomination : denominations) {
      if (denomination.value() == null || denomination.value().compareTo(BigDecimal.ZERO) <= 0) {
        throw invalid(
            "cash.denomination.value.invalid", "Denomination value must be greater than zero.");
      }
      if (denomination.quantity() == null || denomination.quantity() < 0) {
        throw invalid(
            "cash.denomination.quantity.invalid",
            "Denomination quantity must be zero or greater.");
      }
      total = total.add(denomination.value().multiply(BigDecimal.valueOf(denomination.quantity())));
    }
    if (total.compareTo(amount) != 0) {
      throw invalid(
          "cash.denominations.total.mismatch",
          "Cash denomination total must match funding amount.");
    }
    return total;
  }

  private static GeneralPlatformDomainRuleException invalid(final String code, final String message) {
    return new GeneralPlatformDomainRuleException("error.msg.base.teller." + code, message);
  }
}
