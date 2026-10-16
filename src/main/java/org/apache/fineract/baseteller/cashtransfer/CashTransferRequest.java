package org.apache.fineract.baseteller.cashtransfer;

import java.math.BigDecimal;
import java.util.List;

/** Physical custody selection; source identity and monetary values are server-owned. */
public record CashTransferRequest(
    Long destinationCashierId, String idempotencyKey, List<Currency> currencies) {
  public record Currency(String currencyCode, List<Quantity> denominations) {}

  public record Quantity(String denominationId, BigDecimal quantity) {}
}
