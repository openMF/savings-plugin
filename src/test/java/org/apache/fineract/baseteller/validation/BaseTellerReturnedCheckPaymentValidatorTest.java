package org.apache.fineract.baseteller.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckPaymentRequest;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.junit.jupiter.api.Test;

class BaseTellerReturnedCheckPaymentValidatorTest {

  private final BaseTellerReturnedCheckPaymentValidator validator =
      new BaseTellerReturnedCheckPaymentValidator();

  @Test
  void acceptsDenominationTotalMatchingCashReceivedWithZeroQuantityRows() {
    final BigDecimal total =
        validator.validate(
            new BaseTellerReturnedCheckPaymentRequest(
                "idem-1",
                "en",
                "yyyy-MM-dd",
                "2026-09-16",
                new BigDecimal("125.00"),
                "USD",
                1L,
                null,
                List.of(
                    new BaseTellerDenominationData("100", new BigDecimal("100.00"), 1L),
                    new BaseTellerDenominationData("25", new BigDecimal("25.00"), 1L),
                    new BaseTellerDenominationData("10", new BigDecimal("10.00"), 0L))));

    assertEquals(new BigDecimal("125.00"), total);
  }

  @Test
  void rejectsDenominationTotalThatDoesNotMatchCashReceived() {
    final BaseTellerReturnedCheckPaymentRequest request =
        new BaseTellerReturnedCheckPaymentRequest(
            "idem-1",
            "en",
            "yyyy-MM-dd",
            "2026-09-16",
            new BigDecimal("125.00"),
            "USD",
            1L,
            null,
            List.of(new BaseTellerDenominationData("100", new BigDecimal("100.00"), 1L)));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validate(request));
  }
}
