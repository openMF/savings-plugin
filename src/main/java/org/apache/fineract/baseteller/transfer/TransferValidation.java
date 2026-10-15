package org.apache.fineract.baseteller.transfer;

import java.math.BigDecimal;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;

/** Pure validation shared by preview and posting; financial rules remain in native Fineract. */
public final class TransferValidation {
  private TransferValidation() {}

  public static void request(TransferRequest request, boolean posting) {
    if (request == null
        || request.operationType() == null
        || request.targetAccountType() == null
        || request.orderingClientId() == null
        || request.orderingClientId() <= 0
        || request.sourceAccountId() == null
        || request.sourceAccountId() <= 0
        || request.targetAccountId() == null
        || request.targetAccountId() <= 0) {
      throw invalid(
          "request", "Operation, ordering client, source and qualified target are required.");
    }
    if (request.amount() == null
        || request.amount().signum() <= 0
        || request.amount().precision() - request.amount().scale() > 13
        || request.amount().scale() > 6) {
      throw invalid(
          "amount",
          "Amount must be a positive decimal with at most 13 integer digits and 6 decimal places.");
    }
    if (posting
        && (request.idempotencyKey() == null
            || !request.idempotencyKey().matches("[A-Za-z0-9._:-]{1,100}"))) {
      throw invalid(
          "idempotency.key",
          "A 1-100 character idempotencyKey is required (letters, digits, . _ : -).");
    }
  }

  public static void relationship(
      TransferRequest request, TransferData.Account source, TransferData.Account target) {
    if (!source.owner().clientId().equals(request.orderingClientId())) {
      throw invalid("source.owner", "Source must belong to the selected ordering client.");
    }
    if (target.accountType() == TransferAccountType.SAVINGS
        && source.accountId().equals(target.accountId())) {
      throw invalid("same.account", "Source and destination must differ.");
    }
    boolean sameOwner = source.owner().clientId().equals(target.owner().clientId());
    if ((request.operationType() == TransferOperation.BETWEEN_MY_ACCOUNTS) != sameOwner) {
      throw invalid("ownership", "Account owners do not match the selected operation type.");
    }
    if (!source.canDebit() || !target.canCredit()) {
      throw invalid("ineligible", "Source or destination is not eligible for this transfer.");
    }
    if (!source.currencyCode().equals(target.currencyCode())) {
      throw invalid(
          "currency", "Cross-currency transfers are unsupported; account currencies must match.");
    }
    if (request.amount().scale() > source.decimalPlaces()) {
      throw invalid("amount.scale", "Amount exceeds currency precision.");
    }
  }

  public static void funds(BigDecimal amount, BigDecimal fee, BigDecimal withdrawable) {
    if (withdrawable == null || amount.add(fee).compareTo(withdrawable) > 0) {
      throw invalid(
          "insufficient.funds",
          "Insufficient native withdrawable funds, including transfer withdrawal fee.");
    }
  }

  public static GeneralPlatformDomainRuleException invalid(String code, String message) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.base.teller.account.transfer." + code, message);
  }
}
