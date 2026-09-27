package org.apache.fineract.baseteller.validation;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.CreditPaymentCheckClassification;
import org.apache.fineract.baseteller.data.CreditPaymentCheckRequest;
import org.apache.fineract.baseteller.data.CreditPaymentMethod;
import org.apache.fineract.baseteller.data.CreditPaymentRequest;
import org.apache.fineract.baseteller.data.CreditPaymentTransitionRequest;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.springframework.stereotype.Component;

@Component
public class CreditPaymentValidator {

  public void validate(final CreditPaymentRequest request, final boolean requireIdempotencyKey) {
    if (request == null) throw invalid("request.required", "A payment request is required.");
    if (requireIdempotencyKey && StringUtils.isBlank(request.idempotencyKey())) {
      throw invalid("idempotency.required", "idempotencyKey is required.");
    }
    if (request.clientId() == null || request.loanId() == null) {
      throw invalid("account.required", "clientId and loanId are required.");
    }
    if (request.paymentMethod() == null) {
      throw invalid("method.required", "paymentMethod is required.");
    }
    if (request.amount() == null || request.amount().signum() <= 0) {
      throw invalid("amount.invalid", "Payment amount must be greater than zero.");
    }
    if (StringUtils.isBlank(request.currencyCode()) || request.currencyCode().length() != 3) {
      throw invalid("currency.invalid", "A three-letter currencyCode is required.");
    }
    if (request.paymentTypeId() == null) {
      throw invalid("payment.type.required", "paymentTypeId is required.");
    }
    if (request.paymentMethod() == CreditPaymentMethod.CASH) {
      validateDenominations(request.denominations());
      if (request.check() != null) {
        throw invalid("check.unexpected", "Check details are not allowed for a cash payment.");
      }
    } else {
      validateCheck(request.check());
      if (request.denominations() != null && !request.denominations().isEmpty()) {
        throw invalid("cash.unexpected", "Cash denominations are not allowed for a check payment.");
      }
    }
  }

  public void validateTransition(final CreditPaymentTransitionRequest request, final boolean reasonRequired) {
    if (request == null || StringUtils.isBlank(request.idempotencyKey())) {
      throw invalid("idempotency.required", "idempotencyKey is required.");
    }
    if (reasonRequired && StringUtils.isBlank(request.reason())) {
      throw invalid("return.reason.required", "A return reason is required.");
    }
  }

  private void validateDenominations(final List<BaseTellerDenominationData> denominations) {
    if (denominations == null || denominations.isEmpty()) {
      throw invalid("cash.denominations.required", "Cash denominations are required.");
    }
    final Set<String> ids = new HashSet<>();
    for (BaseTellerDenominationData item : denominations) {
      if (item == null || StringUtils.isBlank(item.denominationId())) {
        throw invalid("cash.denomination.id.required", "Every denomination needs an identifier.");
      }
      if (!ids.add(item.denominationId().trim().toLowerCase(Locale.ROOT))) {
        throw invalid("cash.denomination.duplicate", "Each denomination may occur only once.");
      }
      if (item.quantity() == null || item.quantity() < 0) {
        throw invalid("cash.denomination.quantity.invalid", "Denomination quantity cannot be negative.");
      }
      if (item.value() != null && item.value().compareTo(BigDecimal.ZERO) <= 0) {
        throw invalid("cash.denomination.value.invalid", "Denomination value must be positive.");
      }
    }
  }

  private void validateCheck(final CreditPaymentCheckRequest check) {
    if (check == null) throw invalid("check.required", "Check details are required.");
    if (check.bankId() == null) throw invalid("check.bank.required", "bankId is required.");
    if (StringUtils.isBlank(check.checkType()) || StringUtils.isBlank(check.checkNumber())) {
      throw invalid("check.identity.required", "checkType and checkNumber are required.");
    }
    if (check.classification() == null) {
      throw invalid("check.classification.required", "Check classification is required.");
    }
    if (check.classification() != CreditPaymentCheckClassification.SUBJECT_TO_COLLECTION
        && check.classification() != CreditPaymentCheckClassification.CLEARED_FUNDS) {
      throw invalid("check.classification.invalid", "Unsupported check classification.");
    }
  }

  private static GeneralPlatformDomainRuleException invalid(final String code, final String message) {
    return new GeneralPlatformDomainRuleException("error.msg.base.teller.credit.payment." + code, message);
  }
}
