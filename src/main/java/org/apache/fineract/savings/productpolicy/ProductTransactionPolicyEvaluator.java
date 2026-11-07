/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductTransactionPolicyData.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** Pure rules. Eligibility never overrides native blocks, funds checks, or authorization. */
@Component
public class ProductTransactionPolicyEvaluator {
  public Decision evaluate(
      ProductPolicyData.Policy policy,
      Operation operation,
      BigDecimal amount,
      int currencyDigits,
      LocalDate businessDate,
      LocalDate transactionDate) {
    if (policy == null) return unsupported("policy.missing", "policyVersion");
    if (!validMoney(amount, currencyDigits) || amount.signum() <= 0)
      return deny("amount.invalid", "transactionAmount");
    if (operation == null) return unsupported("operation.unsupported", "operation");
    if (policy.withdrawalsAllowed() == null
        || policy.depositsAllowed() == null
        || policy.freezing() == null
        || policy.purpose() == null
        || policy.interestMode() == null
        || !validMoney(policy.maximumBalance(), currencyDigits)
        || policy.maximumBalance().signum() < 0)
      return unsupported("policy.invalid", "policyVersion");
    if (operation != Operation.DEPOSIT && !policy.withdrawalsAllowed())
      return deny("withdrawals.disabled", "withdrawalsAllowed");
    if (operation == Operation.DEPOSIT && !policy.depositsAllowed())
      return deny("deposits.disabled", "depositsAllowed");
    if (policy.freezing() != ProductPolicyData.Freezing.NONE)
      return unsupported("freezing.unsupported", "freezing");
    if (policy.purpose() != ProductPolicyData.Purpose.MAIN)
      return unsupported("purpose.unsupported", "purpose");
    if (policy.interestMode() == ProductPolicyData.InterestMode.TIERED
        || policy.interestMode() == ProductPolicyData.InterestMode.REFERENCE_RATE)
      return unsupported("interest.unsupported", "interestMode");
    if (businessDate == null || !businessDate.equals(transactionDate))
      return unsupported("transaction.date.unsupported", "transactionDate");
    // No approved balance basis, complete credit hook, or cross-path serialization protocol.
    // An under-cap arithmetic preview must never become an executable permission.
    if (operation == Operation.DEPOSIT)
      return unsupported("maximum.balance.enforcement.unsupported", "maximumBalance");
    return allow();
  }

  /** Exact projection primitive for a future approved balance basis; NOT an execution guard. */
  public Decision projectedBalance(
      BigDecimal balance, BigDecimal amount, BigDecimal maximum, int currencyDigits) {
    if (!validMoney(balance, currencyDigits)
        || !validMoney(amount, currencyDigits)
        || amount.signum() <= 0
        || !validMoney(maximum, currencyDigits)
        || maximum.signum() < 0) return deny("amount.invalid", "transactionAmount");
    return balance.add(amount).compareTo(maximum) > 0
        ? deny("maximum.balance.exceeded", "maximumBalance")
        : allow();
  }

  static boolean validMoney(BigDecimal value, int digits) {
    return value != null
        && digits >= 0
        && digits <= 6
        && value.stripTrailingZeros().scale() <= digits
        && (long) value.precision() - value.scale() <= 13;
  }

  static void requireActivationSupported() {
    // All BE-01 policies carry a mandatory maximumBalance, including zero. Zero is not infinity.
    unsupported("activation.unsupported", "maximumBalance").requireAllowed();
  }
}
