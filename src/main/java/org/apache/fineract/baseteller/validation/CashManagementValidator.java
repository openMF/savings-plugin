package org.apache.fineract.baseteller.validation;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.CashOperationRequest;
import org.apache.fineract.baseteller.data.CashierClosingRequest;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.springframework.stereotype.Component;

@Component
public class CashManagementValidator {

  public void validateClosing(final CashierClosingRequest request, final int decimalPlaces) {
    required(request == null, "closing.request.required", "Closing request is required.");
    required(StringUtils.isBlank(request.idempotencyKey()), "idempotency.required", "idempotencyKey is required.");
    required(request.cashierId() == null, "cashier.required", "cashierId is required.");
    required(request.businessDate() == null, "business.date.required", "businessDate is required.");
    required(StringUtils.isBlank(request.currencyCode()), "currency.required", "currencyCode is required.");
    validateDenominations(request.denominations(), decimalPlaces);
    validateCheckIds(request.checkIds());
  }

  public void validateOperation(final CashOperationRequest request, final int decimalPlaces) {
    required(request == null, "operation.request.required", "Cash operation request is required.");
    required(StringUtils.isBlank(request.idempotencyKey()), "idempotency.required", "idempotencyKey is required.");
    required(request.transactionType() == null, "transaction.type.required", "transactionType is required.");
    required(request.cashierId() == null, "cashier.required", "cashierId is required.");
    required(request.businessDate() == null, "business.date.required", "businessDate is required.");
    required(StringUtils.isBlank(request.currencyCode()), "currency.required", "currencyCode is required.");
    validateDenominations(request.denominations(), decimalPlaces);
    validateCheckIds(request.checkIds());
    required(
        (request.denominations() == null || request.denominations().isEmpty())
            && (request.checkIds() == null || request.checkIds().isEmpty()),
        "operation.assets.required",
        "At least one denomination or eligible check is required.");
  }

  public BigDecimal validateDenominations(
      final List<BaseTellerDenominationData> denominations, final int decimalPlaces) {
    if (denominations == null) {
      return BigDecimal.ZERO;
    }
    final Set<BigDecimal> unique = new HashSet<>();
    BigDecimal total = BigDecimal.ZERO;
    for (BaseTellerDenominationData denomination : denominations) {
      required(denomination == null, "denomination.required", "Denomination line is required.");
      required(
          denomination.value() == null || denomination.value().signum() <= 0,
          "denomination.value.invalid",
          "Denomination value must be greater than zero.");
      required(
          denomination.value().stripTrailingZeros().scale() > decimalPlaces,
          "denomination.precision.invalid",
          "Denomination exceeds currency decimal precision.");
      required(
          denomination.quantity() == null || denomination.quantity() < 0,
          "denomination.quantity.invalid",
          "Denomination quantity must be zero or greater.");
      required(
          !unique.add(denomination.value().stripTrailingZeros()),
          "denomination.duplicate",
          "Each denomination may occur only once.");
      total = total.add(denomination.value().multiply(BigDecimal.valueOf(denomination.quantity())));
    }
    return total;
  }

  private void validateCheckIds(final List<Long> checkIds) {
    if (checkIds == null) {
      return;
    }
    final Set<Long> unique = new HashSet<>();
    for (Long id : checkIds) {
      required(id == null || id <= 0, "check.id.invalid", "Every check id must be positive.");
      required(!unique.add(id), "check.duplicate", "A check cannot be counted twice.");
    }
  }

  private static void required(final boolean invalid, final String code, final String message) {
    if (invalid) {
      throw new GeneralPlatformDomainRuleException("error.msg.base.teller." + code, message);
    }
  }
}
