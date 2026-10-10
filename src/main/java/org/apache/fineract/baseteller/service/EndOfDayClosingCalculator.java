package org.apache.fineract.baseteller.service;

import java.math.BigDecimal;
import java.util.*;
import org.apache.fineract.baseteller.data.*;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.*;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.Currency;
import org.apache.fineract.baseteller.data.EndOfDayClosingRequest.*;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;

/** Pure physical declaration arithmetic. Catalog values and custody are supplied by the server. */
public final class EndOfDayClosingCalculator {
  private EndOfDayClosingCalculator() {}

  public static Balance calculate(
      CurrencyDeclaration declaration,
      Currency currency,
      BigDecimal expectedCash,
      BigDecimal expectedChecks,
      List<Check> eligible) {
    if (declaration == null || declaration.denominations() == null || declaration.checks() == null)
      throw invalid(
          "declaration.required", "Denominations and check selections are required arrays.");
    Map<String, CashExchangeDenominationData> catalog = new HashMap<>();
    currency.denominations().forEach(d -> catalog.put(d.denominationId(), d));
    Set<String> seen = new HashSet<>();
    List<CashExchangeDenominationData> lines = new ArrayList<>();
    BigDecimal coins = BigDecimal.ZERO, bills = BigDecimal.ZERO;
    for (Quantity q : declaration.denominations()) {
      if (q == null || q.denominationId() == null || !seen.add(q.denominationId()))
        throw invalid(
            "denomination.invalid", "Each denomination must have a unique configured identifier.");
      var d = catalog.get(q.denominationId());
      if (d == null)
        throw invalid(
            "denomination.unsupported",
            "Denomination is unknown, disabled or belongs to another currency.");
      long quantity;
      try {
        quantity = q.quantity().longValueExact();
      } catch (ArithmeticException | NullPointerException e) {
        throw invalid(
            "quantity.invalid", "Quantity must be an integer within the supported range.");
      }
      if (quantity < 0) throw invalid("quantity.invalid", "Quantity cannot be negative.");
      if (quantity == 0) continue;
      BigDecimal amount =
          amount(d.value().multiply(BigDecimal.valueOf(quantity)), currency.decimalPlaces());
      if ("COIN".equals(d.type())) coins = coins.add(amount);
      else if (isBill(d.type())) bills = bills.add(amount);
      else throw invalid("catalog.invalid", "Denomination type must identify a bill or coin.");
      lines.add(
          new CashExchangeDenominationData(
              d.denominationId(), d.type(), d.value(), quantity, amount));
    }
    Set<CheckSelection> unique = new HashSet<>();
    List<Check> checks = new ArrayList<>();
    for (CheckSelection selection : declaration.checks()) {
      if (selection == null
          || selection.id() == null
          || selection.id() <= 0
          || !unique.add(selection))
        throw invalid("check.invalid", "Every check selection must be positive and unique.");
      checks.add(
          eligible.stream()
              .filter(c -> c.source().equals(selection.source()) && c.id().equals(selection.id()))
              .findFirst()
              .orElseThrow(
                  () ->
                      invalid(
                          "check.ineligible",
                          "Selected check is unavailable for this cashier and currency.")));
    }
    checks.sort(Comparator.comparing(Check::source).thenComparing(Check::id));
    lines.sort(Comparator.comparing(CashExchangeDenominationData::denominationId));
    BigDecimal vouchers = amount(declaration.vouchers(), currency.decimalPlaces());
    BigDecimal selected =
        checks.stream().map(Check::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal cash = amount(coins.add(bills), currency.decimalPlaces());
    BigDecimal declared = amount(cash.add(selected).add(vouchers), currency.decimalPlaces());
    BigDecimal expected = amount(expectedCash.add(expectedChecks), currency.decimalPlaces());
    BigDecimal difference = declared.subtract(expected);
    return new Balance(
        currency.currencyCode(),
        currency.decimalPlaces(),
        expectedCash,
        expectedChecks,
        BigDecimal.ZERO,
        expected,
        coins,
        bills,
        cash,
        selected,
        vouchers,
        declared,
        difference.negate().max(BigDecimal.ZERO),
        difference.max(BigDecimal.ZERO),
        difference.signum() == 0 ? "BALANCED" : difference.signum() < 0 ? "SHORTAGE" : "OVERAGE",
        List.copyOf(lines),
        List.copyOf(checks),
        null,
        null);
  }

  static boolean isBill(String type) {
    return type != null && Set.of("BILL", "BANKNOTE", "NOTE").contains(type);
  }

  public static BigDecimal amount(BigDecimal value, int places) {
    if (value == null
        || value.signum() < 0
        || value.stripTrailingZeros().scale() > places
        || value.compareTo(new BigDecimal("10000000000000")) >= 0)
      throw invalid(
          "amount.invalid",
          "Amount is negative, missing, over precision or exceeds decimal(19,6).");
    return value;
  }

  public static GeneralPlatformDomainRuleException invalid(String code, String message) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.base.teller.end.of.day." + code, message);
  }
}
