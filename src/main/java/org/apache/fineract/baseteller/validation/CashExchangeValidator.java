package org.apache.fineract.baseteller.validation;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.fineract.baseteller.data.CashExchangeDenominationData;
import org.apache.fineract.baseteller.data.CashExchangeQuantityData;
import org.apache.fineract.baseteller.data.CashExchangeRequest;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;

public final class CashExchangeValidator {
  private CashExchangeValidator() {}

  public static void validate(CashExchangeRequest r, boolean create) {
    if (r == null
        || r.cashierId() == null
        || r.cashierId() <= 0
        || r.currencyCode() == null
        || !r.currencyCode().matches("[A-Za-z]{3}"))
      throw invalid(
          "request.invalid", "A positive cashierId and configured currencyCode are required.");
    if (create
        && (r.idempotencyKey() == null || !r.idempotencyKey().matches("[A-Za-z0-9._:-]{1,100}")))
      throw invalid(
          "idempotency.required",
          "idempotencyKey must contain 1 to 100 letters, digits, dots, underscores, colons or"
              + " hyphens.");
    quantities(r.receivedDenominations());
    quantities(r.deliveredDenominations());
  }

  private static void quantities(List<CashExchangeQuantityData> lines) {
    if (lines == null || lines.isEmpty() || lines.size() > 1000)
      throw invalid("denominations.required", "Each side requires 1 to 1000 denomination entries.");
    Set<String> ids = new HashSet<>();
    for (var line : lines) {
      if (line == null
          || line.denominationId() == null
          || line.denominationId().isBlank()
          || line.denominationId().length() > 100
          || line.quantity() == null
          || line.quantity() < 0)
        throw invalid(
            "quantity.invalid",
            "Denomination identifiers and nonnegative integer quantities are required.");
      if (!ids.add(line.denominationId()))
        throw invalid(
            "denomination.duplicate", "Denomination identifiers must be unique on each side.");
    }
  }

  public static BigDecimal total(List<CashExchangeDenominationData> lines) {
    return lines.stream()
        .map(CashExchangeDenominationData::amount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  public static void balanced(BigDecimal received, BigDecimal delivered) {
    if (received.signum() <= 0 || received.compareTo(delivered) != 0)
      throw invalid(
          "amount.mismatch", "Received and delivered totals must be positive and exactly equal.");
    if (received.precision() - received.scale() > 13 || received.stripTrailingZeros().scale() > 6)
      throw invalid("amount.overflow", "Exchange exceeds supported decimal(19,6) precision.");
  }

  public static GeneralPlatformDomainRuleException invalid(String code, String message) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.base.teller.cash.exchange." + code, message);
  }
}
