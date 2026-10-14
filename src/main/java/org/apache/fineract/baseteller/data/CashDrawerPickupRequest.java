package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.util.List;

/** Custody selection. Monetary values and credentials are deliberately absent. */
public record CashDrawerPickupRequest(
    Long cashierId,
    Long destinationCashierId,
    String idempotencyKey,
    List<Currency> currencies) {
  public record Currency(String currencyCode, List<Quantity> cashDenominations, List<Check> checks) {}
  public record Quantity(String denominationId, BigDecimal quantity) {}
  public record Check(String source, Long id) {}
}
