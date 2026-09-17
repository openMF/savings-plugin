package org.apache.fineract.baseteller.validation;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckPaymentRequest;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BaseTellerReturnedCheckPaymentValidator {

  public BigDecimal validate(final BaseTellerReturnedCheckPaymentRequest request) {
    if (request == null) {
      throw invalid("request.required", "Request body is required.");
    }
    if (StringUtils.isBlank(request.idempotencyKey())) {
      throw invalid("idempotency.key.required", "idempotencyKey is required.");
    }
    if (request.cashReceived() == null || request.cashReceived().compareTo(BigDecimal.ZERO) <= 0) {
      throw invalid("cash.received.invalid", "cashReceived must be greater than zero.");
    }
    if (StringUtils.isBlank(request.currencyCode())) {
      throw invalid("currency.required", "currencyCode is required.");
    }
    if (request.paymentTypeId() == null || request.paymentTypeId() <= 0) {
      throw invalid("payment.type.required", "paymentTypeId is required.");
    }
    return BaseTellerDenominationValidator.validateCash(
        request.cashReceived(), request.denominations());
  }

  private GeneralPlatformDomainRuleException invalid(final String code, final String message) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.base.teller.returned.check." + code, message);
  }
}
