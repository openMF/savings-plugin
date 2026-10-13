package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.util.List;

/** Physical declaration. Authorization secrets are never part of a printable value object. */
public record TransactionReversalRequest(
    String historyId,
    String idempotencyKey,
    List<Quantity> cashDenominations,
    List<Quantity> returnedCashDenominations,
    List<Check> checks) {
  public record Quantity(String denominationId, Long quantity) {}

  public record Check(Long id, Long bankId, String checkNumber, BigDecimal amount) {}
}
