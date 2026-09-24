package org.apache.fineract.baseteller.validation;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.ServicePaymentPayerType;
import org.apache.fineract.baseteller.data.ServicePaymentQuoteRequest;
import org.apache.fineract.baseteller.data.ServicePaymentRequest;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.springframework.stereotype.Component;

@Component
public class ServicePaymentValidator {

  public void validateQuote(final ServicePaymentQuoteRequest request) {
    if (request == null) throw invalid("request.required", "Request body is required.");
    validateCore(
        request.payerType(),
        request.clientId(),
        request.payerName(),
        request.serviceId(),
        request.serviceReference(),
        request.baseAmount(),
        request.currencyCode());
  }

  public void validatePayment(final ServicePaymentRequest request) {
    if (request == null) throw invalid("request.required", "Request body is required.");
    if (StringUtils.isBlank(request.idempotencyKey()) || request.idempotencyKey().length() > 100) {
      throw invalid(
          "idempotency.key.invalid",
          "idempotencyKey is required and must not exceed 100 characters.");
    }
    validateCore(
        request.payerType(),
        request.clientId(),
        request.payerName(),
        request.serviceId(),
        request.serviceReference(),
        request.baseAmount(),
        request.currencyCode());
    if (request.paymentTypeId() == null || request.paymentTypeId() <= 0) {
      throw invalid("payment.type.required", "paymentTypeId is required.");
    }
    validateDenominationShape(request.denominations());
  }

  private void validateCore(
      final ServicePaymentPayerType payerType,
      final Long clientId,
      final String payerName,
      final Long serviceId,
      final String reference,
      final BigDecimal baseAmount,
      final String currency) {
    if (payerType == null) throw invalid("payer.type.required", "payerType is required.");
    if (payerType == ServicePaymentPayerType.CLIENT && (clientId == null || clientId <= 0)) {
      throw invalid("client.id.required", "clientId is required for a client payer.");
    }
    if (payerType == ServicePaymentPayerType.NON_CLIENT
        && (StringUtils.isBlank(payerName) || payerName.trim().length() > 250)) {
      throw invalid(
          "payer.name.invalid", "payerName is required and must not exceed 250 characters.");
    }
    if (serviceId == null || serviceId <= 0)
      throw invalid("service.id.required", "serviceId is required.");
    if (StringUtils.isBlank(reference) || reference.trim().length() > 200) {
      throw invalid(
          "reference.invalid", "serviceReference is required and must not exceed 200 characters.");
    }
    if (baseAmount == null || baseAmount.signum() <= 0) {
      throw invalid("amount.invalid", "baseAmount must be greater than zero.");
    }
    if (StringUtils.isBlank(currency) || currency.trim().length() != 3) {
      throw invalid("currency.invalid", "A three-character currencyCode is required.");
    }
  }

  private void validateDenominationShape(final List<BaseTellerDenominationData> denominations) {
    if (denominations == null || denominations.isEmpty()) {
      throw invalid("denominations.required", "Cash denominations are required.");
    }
    final Set<String> identifiers = new HashSet<>();
    for (BaseTellerDenominationData item : denominations) {
      if (item == null || StringUtils.isBlank(item.denominationId())) {
        throw invalid(
            "denomination.identifier.required", "Every denomination must have an identifier.");
      }
      if (!identifiers.add(item.denominationId().trim().toLowerCase())) {
        throw invalid("denomination.duplicate", "Each denomination may occur only once.");
      }
      if (item.quantity() == null || item.quantity() < 0) {
        throw invalid(
            "denomination.quantity.invalid", "Denomination quantity must be zero or greater.");
      }
    }
  }

  private static GeneralPlatformDomainRuleException invalid(
      final String code, final String message) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.base.teller.service.payment." + code, message);
  }
}
