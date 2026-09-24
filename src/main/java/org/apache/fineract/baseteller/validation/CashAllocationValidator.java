package org.apache.fineract.baseteller.validation;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.CashAllocationRequest;
import org.apache.fineract.baseteller.data.CashAllocationType;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.springframework.stereotype.Component;

@Component
public class CashAllocationValidator {

  public void validate(final CashAllocationRequest request) {
    required(request == null, "request.required", "Cash allocation request is required.");
    required(
        StringUtils.isBlank(request.idempotencyKey()),
        "idempotency.required",
        "idempotencyKey is required.");
    required(
        request.idempotencyKey().length() > 100,
        "idempotency.too.long",
        "idempotencyKey must not exceed 100 characters.");
    required(request.operationType() == null, "operation.required", "operationType is required.");
    required(request.officeId() == null || request.officeId() <= 0, "office.required", "A valid officeId is required.");
    required(request.businessDate() == null, "business.date.required", "businessDate is required.");
    required(StringUtils.isBlank(request.currencyCode()), "currency.required", "currencyCode is required.");
    required(request.currencyCode().trim().length() != 3, "currency.invalid", "currencyCode must contain three characters.");
    required(request.amount() == null || request.amount().signum() <= 0, "amount.invalid", "amount must be greater than zero.");
    required(request.denominations() == null || request.denominations().isEmpty(), "denominations.required", "At least one cash denomination is required.");
    required(request.note() != null && request.note().length() > 500, "note.too.long", "note must not exceed 500 characters.");

    if (request.operationType() == CashAllocationType.SAFE_VAULT_OPENING) {
      required(request.sourceCashierId() != null || request.destinationCashierId() != null,
          "vault.cashier.invalid", "Safe/vault opening must not specify a cashier.");
    } else if (request.operationType() == CashAllocationType.HEAD_CASHIER_ALLOCATION) {
      required(request.sourceCashierId() != null, "head.source.invalid", "Head cashier allocation source is the branch vault.");
      required(request.destinationCashierId() == null || request.destinationCashierId() <= 0,
          "destination.required", "A valid destinationCashierId is required.");
    } else {
      required(request.sourceCashierId() == null || request.sourceCashierId() <= 0,
          "source.required", "A valid head sourceCashierId is required.");
      required(request.destinationCashierId() == null || request.destinationCashierId() <= 0,
          "destination.required", "A valid destinationCashierId is required.");
      required(request.sourceCashierId().equals(request.destinationCashierId()),
          "cashier.same", "Source and destination cashiers must differ.");
    }

    final Set<String> identifiers = new HashSet<>();
    for (BaseTellerDenominationData item : request.denominations()) {
      required(item == null || StringUtils.isBlank(item.denominationId()),
          "denomination.identifier.required", "Every denomination must have an identifier.");
      required(!identifiers.add(item.denominationId().trim().toLowerCase(Locale.ROOT)),
          "denomination.duplicate", "Each denomination may occur only once.");
      required(item.quantity() == null || item.quantity() < 0,
          "denomination.quantity.invalid", "Denomination quantity must be zero or greater.");
      required(item.value() != null && item.value().signum() <= 0,
          "denomination.value.invalid", "Submitted denomination value must be greater than zero.");
    }
  }

  public void validateAuthoritativeTotal(
      final CashAllocationRequest request, final BigDecimal total, final int decimalPlaces) {
    required(request.amount().stripTrailingZeros().scale() > decimalPlaces,
        "amount.precision.invalid", "amount exceeds currency decimal precision.");
    required(total.signum() <= 0, "denominations.total.invalid", "Cash denomination total must be greater than zero.");
    required(total.compareTo(request.amount()) != 0,
        "denominations.total.mismatch", "Cash denomination total must match amount.");
  }

  private static void required(final boolean invalid, final String code, final String message) {
    if (invalid) {
      throw new GeneralPlatformDomainRuleException("error.msg.base.teller.cash.allocation." + code, message);
    }
  }
}
