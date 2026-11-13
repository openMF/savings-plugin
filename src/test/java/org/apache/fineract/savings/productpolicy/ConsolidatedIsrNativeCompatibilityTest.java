/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.fineract.portfolio.savings.domain.FixedDepositAccount;
import org.apache.fineract.portfolio.savings.domain.RecurringDepositAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Calls the real pinned domain method; this is a compatibility check, not posting validation. */
class ConsolidatedIsrNativeCompatibilityTest {
  @ParameterizedTest
  @CsvSource({"100,true,true", "100,false,false", "200,true,false", "300,true,false"})
  void automaticInterestWithholdingIsLimitedByNativeDepositType(
      int type, boolean configured, boolean expected) throws Exception {
    Class<? extends SavingsAccount> subtype =
        type == 200
            ? FixedDepositAccount.class
            : type == 300 ? RecurringDepositAccount.class : SavingsAccount.class;
    var constructor = subtype.getDeclaredConstructor();
    constructor.setAccessible(true);
    var account = constructor.newInstance();
    account.setWithHoldTax(configured);
    assertEquals(expected, account.isWithHoldTaxApplicableForInterestPosting());
  }
}
