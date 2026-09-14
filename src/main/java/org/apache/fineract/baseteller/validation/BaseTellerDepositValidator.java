package org.apache.fineract.baseteller.validation;

import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.BaseTellerCheckClearingStatus;
import org.apache.fineract.baseteller.data.BaseTellerDepositCheckRequest;
import org.apache.fineract.baseteller.data.BaseTellerDepositFundingData;
import org.apache.fineract.baseteller.data.BaseTellerDepositRequest;
import org.apache.fineract.baseteller.data.BaseTellerFundingType;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BaseTellerDepositValidator {

  public void validate(final BaseTellerDepositRequest request) {
    if (request == null) {
      throw invalid("request.required", "Request body is required.");
    }
    if (StringUtils.isBlank(request.idempotencyKey())) {
      throw invalid("idempotency.key.required", "idempotencyKey is required.");
    }
    if (request.clientId() == null || request.clientId() <= 0) {
      throw invalid("client.required", "clientId must be greater than zero.");
    }
    if (request.savingsAccountId() == null || request.savingsAccountId() <= 0) {
      throw invalid("savings.account.required", "savingsAccountId must be greater than zero.");
    }
    validateFunding(request.funding());
  }

  public void validateFunding(final BaseTellerDepositFundingData funding) {
    if (funding == null) {
      throw invalid("funding.required", "Funding is required.");
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
      BaseTellerDenominationValidator.validateCash(funding.amount(), funding.denominations());
      return;
    }
    validateChecks(funding.amount(), funding.checks());
  }

  private void validateChecks(
      final BigDecimal amount, final List<BaseTellerDepositCheckRequest> checks) {
    if (checks == null || checks.isEmpty()) {
      throw invalid("check.details.required", "At least one check is required.");
    }
    BigDecimal total = BigDecimal.ZERO;
    for (BaseTellerDepositCheckRequest check : checks) {
      if (StringUtils.isBlank(check.checkNumber())) {
        throw invalid("check.number.required", "checkNumber is required.");
      }
      if (StringUtils.isBlank(check.bank())) {
        throw invalid("check.bank.required", "bank is required.");
      }
      if (StringUtils.isBlank(check.checkType())) {
        throw invalid("check.type.required", "checkType is required.");
      }
      if (check.amount() == null || check.amount().compareTo(BigDecimal.ZERO) <= 0) {
        throw invalid("check.amount.invalid", "Check amount must be greater than zero.");
      }
      if (check.clearingStatus() != null
          && check.clearingStatus() != BaseTellerCheckClearingStatus.SUBJECT_TO_COLLECTION
          && check.clearingStatus() != BaseTellerCheckClearingStatus.CLEARED) {
        throw invalid("check.clearing.status.invalid", "Check clearing status is invalid.");
      }
      total = total.add(check.amount());
    }
    if (total.compareTo(amount) != 0) {
      throw invalid("check.total.mismatch", "Check total must match funding amount.");
    }
  }

  private GeneralPlatformDomainRuleException invalid(final String code, final String message) {
    return new GeneralPlatformDomainRuleException("error.msg.base.teller." + code, message);
  }
}
