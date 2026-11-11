/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.GuaranteeSweepData.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GuaranteeSweepValidationTest {
  static final String REQUEST =
      "{\"idempotencyKey\":\"manual-test-01\",\"savingsAccountId\":901,\"amount\":10.00,\"reason\":\"Collections"
          + " review\"}";

  @Test
  void exactAmountsAndCanonicalIdempotencyBindTheEntireFinancialIntent() {
    var request = GuaranteeSweepValidation.parse(REQUEST, Operation.APPLY);
    assertEquals(new BigDecimal("10.00"), request.amount());
    var equivalent =
        GuaranteeSweepValidation.parse(REQUEST.replace("10.00", "10.0"), Operation.APPLY);
    assertEquals(
        GuaranteeSweepValidation.fingerprint(1, 2, request, Operation.APPLY),
        GuaranteeSweepValidation.fingerprint(1, 2, equivalent, Operation.APPLY));
    assertNotEquals(
        GuaranteeSweepValidation.fingerprint(1, 2, request, Operation.APPLY),
        GuaranteeSweepValidation.fingerprint(1, 3, request, Operation.APPLY));
    assertNotEquals(
        GuaranteeSweepValidation.fingerprint(1, 2, request, Operation.APPLY),
        GuaranteeSweepValidation.fingerprint(2, 2, request, Operation.APPLY));
  }

  @Test
  void unsafePayloadsNeverBecomeFinancialIntent() {
    for (String payload :
        java.util.List.of(
            REQUEST.replace("10.00", "0"),
            REQUEST.replace("10.00", "-1"),
            REQUEST.replace("10.00", "0.0000001"),
            REQUEST.replace("10.00", "1e99"),
            REQUEST.replace("10.00", "\"10.00\""),
            REQUEST.replace("901", "0"),
            REQUEST.replace("901", "1.1"),
            REQUEST.replace("901", "null"),
            REQUEST.replace("manual-test-01", "short"),
            REQUEST.replace("Collections review", ""),
            REQUEST.replace("10.00", "10,\"override\":true"),
            REQUEST.replace("10.00", "10,\"availableBalance\":10000"),
            REQUEST.replace("10.00", "10,\"amount\":10"),
            REQUEST + "{}",
            "null",
            "[]")) {
      assertThrows(
          PlatformApiDataValidationException.class,
          () -> GuaranteeSweepValidation.parse(payload, Operation.APPLY),
          payload);
    }
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> GuaranteeSweepValidation.parse(REQUEST, Operation.RELEASE));
    assertNull(
        GuaranteeSweepValidation.parse(REQUEST.replace(",\"amount\":10.00", ""), Operation.RELEASE)
            .amount());
  }

  @Test
  void noCapabilityOrDefaultSchedulerCanEnablePosting() {
    var c = capabilities();
    assertTrue(c.eligibilityDiagnostics());
    assertFalse(c.manualSweep());
    assertFalse(c.automaticSweep());
    assertFalse(c.guaranteeAllocation());
    assertFalse(c.guaranteeRelease());
    assertFalse(c.financialEnforcement());
    assertFalse(MainAccountData.capabilities().guaranteeAllocation());
    new ApplicationContextRunner()
        .withUserConfiguration(GuaranteeSweepDiagnosticJobConfig.class)
        .run(context -> assertFalse(context.containsBean("savingsGuaranteeSweepDiagnosticJob")));
  }
}
