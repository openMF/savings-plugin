package org.apache.fineract.baseteller.withdrawal;

import java.math.BigDecimal;
import java.util.List;

/** User input only. Currency, values, ownership and source cashier are resolved by the server. */
public record WithdrawalRequest(
    Long clientId,
    Long accountId,
    Method method,
    BigDecimal amount,
    Long paymentTypeId,
    Long identificationTypeId,
    String identificationReference,
    List<Quantity> denominations,
    String idempotencyKey) {
  public enum Method {
    CASH,
    CHECK
  }

  public record Quantity(String denominationId, BigDecimal quantity) {}

  @Override
  public String toString() {
    return "WithdrawalRequest[accountId="
        + accountId
        + ",method="
        + method
        + ",identificationReference=REDACTED]";
  }
}
