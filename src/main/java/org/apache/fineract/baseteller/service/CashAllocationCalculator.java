package org.apache.fineract.baseteller.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.CashAllocationDenominationData;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;

public final class CashAllocationCalculator {

  private CashAllocationCalculator() {}

  public static List<CashAllocationDenominationData> calculate(
      final List<BaseTellerDenominationData> requested,
      final List<CashAllocationDenominationData> configured,
      final int decimalPlaces) {
    final Map<String, CashAllocationDenominationData> byIdentifier = new HashMap<>();
    for (CashAllocationDenominationData item : configured) {
      byIdentifier.put(item.identifier().toLowerCase(Locale.ROOT), item);
    }
    final List<CashAllocationDenominationData> result = new ArrayList<>();
    for (BaseTellerDenominationData submitted : requested) {
      final CashAllocationDenominationData item =
          byIdentifier.get(submitted.denominationId().trim().toLowerCase(Locale.ROOT));
      if (item == null) {
        throw invalid(
            "denomination.unsupported",
            "A denomination is not active for the selected currency.");
      }
      if (item.value() == null
          || item.value().signum() <= 0
          || item.value().stripTrailingZeros().scale() > decimalPlaces) {
        throw invalid(
            "denomination.configuration.invalid",
            "Configured denomination is invalid for the selected currency.");
      }
      if (submitted.quantity() == null || submitted.quantity() < 0) {
        throw invalid(
            "denomination.quantity.invalid", "Denomination quantity must be zero or greater.");
      }
      if (submitted.value() != null && submitted.value().compareTo(item.value()) != 0) {
        throw invalid(
            "denomination.value.mismatch",
            "Submitted denomination value does not match backend configuration.");
      }
      result.add(
          new CashAllocationDenominationData(
              item.identifier(),
              item.type(),
              item.value(),
              submitted.quantity(),
              item.value().multiply(BigDecimal.valueOf(submitted.quantity()))));
    }
    return result;
  }

  public static BigDecimal total(final List<CashAllocationDenominationData> denominations) {
    return denominations.stream()
        .map(CashAllocationDenominationData::subtotal)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static GeneralPlatformDomainRuleException invalid(
      final String code, final String message) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.base.teller.cash.allocation." + code, message);
  }
}
