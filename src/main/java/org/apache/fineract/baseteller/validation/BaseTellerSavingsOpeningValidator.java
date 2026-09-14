package org.apache.fineract.baseteller.validation;

import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.BaseTellerFundingData;
import org.apache.fineract.baseteller.data.BaseTellerFundingType;
import org.apache.fineract.baseteller.data.BaseTellerSavingsOpeningRequest;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BaseTellerSavingsOpeningValidator {

  public void validate(final BaseTellerSavingsOpeningRequest request) {
    if (request == null) {
      throw invalid("request.required", "Request body is required.");
    }
    if (StringUtils.isBlank(request.idempotencyKey())) {
      throw invalid("idempotency.key.required", "idempotencyKey is required.");
    }
    if (request.clientId() == null || request.clientId() <= 0) {
      throw invalid("client.required", "clientId must be greater than zero.");
    }
    if (request.productId() == null || request.productId() <= 0) {
      throw invalid("product.required", "productId must be greater than zero.");
    }
    if (request.savingsAccount() == null) {
      throw invalid("savings.account.required", "savingsAccount is required.");
    }
    validateFunding(request.initialFunding());
  }

  public BigDecimal validateFunding(final BaseTellerFundingData funding) {
    if (funding == null) {
      throw invalid("funding.required", "Initial funding is required.");
    }
    if (funding.type() == null) {
      throw invalid("funding.type.required", "Funding type is required.");
    }
    if (funding.amount() == null || funding.amount().compareTo(BigDecimal.ZERO) <= 0) {
      throw invalid("funding.amount.invalid", "Funding amount must be greater than zero.");
    }
    if (StringUtils.isBlank(funding.currencyCode())) {
      throw invalid("funding.currency.required", "Funding currencyCode is required.");
    }
    if (funding.paymentTypeId() == null || funding.paymentTypeId() <= 0) {
      throw invalid("funding.payment.type.required", "Funding paymentTypeId is required.");
    }
    if (funding.type() == BaseTellerFundingType.CASH) {
      return validateCash(funding.amount(), funding.denominations());
    }
    validateCheck(funding);
    return funding.amount();
  }

  private BigDecimal validateCash(
      final BigDecimal amount, final List<BaseTellerDenominationData> denominations) {
    if (denominations == null || denominations.isEmpty()) {
      throw invalid("cash.denominations.required", "Cash denominations are required.");
    }
    BigDecimal total = BigDecimal.ZERO;
    for (BaseTellerDenominationData denomination : denominations) {
      if (denomination.value() == null || denomination.value().compareTo(BigDecimal.ZERO) <= 0) {
        throw invalid(
            "cash.denomination.value.invalid", "Denomination value must be greater than zero.");
      }
      if (denomination.quantity() == null || denomination.quantity() <= 0) {
        throw invalid(
            "cash.denomination.quantity.invalid",
            "Denomination quantity must be greater than zero.");
      }
      total = total.add(denomination.value().multiply(BigDecimal.valueOf(denomination.quantity())));
    }
    if (total.compareTo(amount) != 0) {
      throw invalid(
          "cash.denominations.total.mismatch",
          "Cash denomination total must match funding amount.");
    }
    return total;
  }

  private void validateCheck(final BaseTellerFundingData funding) {
    if (funding.check() == null) {
      throw invalid("check.details.required", "Check details are required.");
    }
    if (StringUtils.isBlank(funding.check().checkNumber())) {
      throw invalid("check.number.required", "checkNumber is required.");
    }
    if (StringUtils.isBlank(funding.check().bank())) {
      throw invalid("check.bank.required", "bank is required.");
    }
    if (StringUtils.isBlank(funding.check().checkType())) {
      throw invalid("check.type.required", "checkType is required.");
    }
  }

  private GeneralPlatformDomainRuleException invalid(final String code, final String message) {
    return new GeneralPlatformDomainRuleException("error.msg.base.teller." + code, message);
  }
}
