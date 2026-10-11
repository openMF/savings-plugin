package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.util.List;

/** Physical declarations only. Credentials are deliberately absent from this value object. */
public record EndOfDayClosingRequest(
    Long cashierId,
    String businessDate,
    String idempotencyKey,
    List<CurrencyDeclaration> currencies) {
  public record Quantity(String denominationId, BigDecimal quantity) {}

  public record CheckSelection(String source, Long id) {}

  public record CurrencyDeclaration(
      String currencyCode,
      List<Quantity> denominations,
      BigDecimal vouchers,
      List<CheckSelection> checks) {}
}
