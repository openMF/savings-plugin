/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.DiscriminatorValue;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.domain.DepositTermDetail;
import org.apache.fineract.portfolio.savings.domain.FixedDepositProduct;
import org.junit.jupiter.api.Test;

/**
 * Executable evidence against the pinned native binary, including its minimum-relative increments.
 */
class InvestmentProductFineractCompatibilityTest {
  @Test
  void nativeFixedDepositDiscriminatorAndDayFrequencyAreVerified() {
    assertEquals("200", FixedDepositProduct.class.getAnnotation(DiscriminatorValue.class).value());
    assertEquals(0, SavingsPeriodFrequencyType.DAYS.getValue());
    assertEquals(1, SavingsPeriodFrequencyType.WEEKS.getValue());
  }

  @Test
  void nativeIncrementUsesMinimumAsItsOrigin() {
    var nativeTerms =
        DepositTermDetail.createFrom(
            1,
            1825,
            SavingsPeriodFrequencyType.DAYS,
            SavingsPeriodFrequencyType.DAYS,
            30,
            SavingsPeriodFrequencyType.DAYS);
    assertTrue(nativeTerms.isValidInMultiplesOfPeriod(61, SavingsPeriodFrequencyType.DAYS));
    assertFalse(nativeTerms.isValidInMultiplesOfPeriod(60, SavingsPeriodFrequencyType.DAYS));
    var cede =
        DepositTermDetail.createFrom(
            60,
            180,
            SavingsPeriodFrequencyType.DAYS,
            SavingsPeriodFrequencyType.DAYS,
            30,
            SavingsPeriodFrequencyType.DAYS);
    assertTrue(cede.isValidInMultiplesOfPeriod(60, SavingsPeriodFrequencyType.DAYS));
    assertTrue(cede.isValidInMultiplesOfPeriod(90, SavingsPeriodFrequencyType.DAYS));
  }

  @Test
  void nativeDenominationMultiplesOnlyRoundZeroDecimalCurrencies() {
    var tenant = mock(FineractPlatformTenant.class);
    when(tenant.getTenantIdentifier()).thenReturn("be01-currency-compatibility");
    ThreadLocalContextUtil.setTenant(tenant);
    MoneyHelper.initializeTenantRoundingMode(
        "be01-currency-compatibility", RoundingMode.HALF_EVEN.ordinal());
    try {
      var cents = new MonetaryCurrency("MXN", 2, 5);
      var whole = new MonetaryCurrency("JPY", 0, 5);
      assertEquals(new BigDecimal("100.01"), Money.of(cents, new BigDecimal("100.01")).getAmount());
      assertEquals(new BigDecimal("100"), Money.of(whole, new BigDecimal("101")).getAmount());
    } finally {
      MoneyHelper.clearCacheForTenant("be01-currency-compatibility");
      ThreadLocalContextUtil.reset();
    }
  }
}
