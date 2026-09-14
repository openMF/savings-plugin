package org.apache.fineract.baseteller.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerCheckClearingStatus;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.BaseTellerDepositCheckRequest;
import org.apache.fineract.baseteller.data.BaseTellerDepositFundingData;
import org.apache.fineract.baseteller.data.BaseTellerDepositRequest;
import org.apache.fineract.baseteller.data.BaseTellerFundingType;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.junit.jupiter.api.Test;

class BaseTellerDepositValidatorTest {

  private final BaseTellerDepositValidator validator = new BaseTellerDepositValidator();

  @Test
  void cashDepositAcceptsExactServerCalculatedDenominationTotal() {
    final BaseTellerDepositRequest request =
        request(
            new BaseTellerDepositFundingData(
                BaseTellerFundingType.CASH,
                new BigDecimal("125.00"),
                "USD",
                1L,
                List.of(
                    new BaseTellerDenominationData("100", new BigDecimal("100.00"), 1L),
                    new BaseTellerDenominationData("25", new BigDecimal("25.00"), 1L)),
                null));

    assertDoesNotThrow(() -> validator.validate(request));
  }

  @Test
  void cashDepositRejectsDenominationMismatch() {
    final BaseTellerDepositRequest request =
        request(
            new BaseTellerDepositFundingData(
                BaseTellerFundingType.CASH,
                new BigDecimal("125.00"),
                "USD",
                1L,
                List.of(new BaseTellerDenominationData("100", new BigDecimal("100.00"), 1L)),
                null));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validate(request));
  }

  @Test
  void cashDepositRejectsMissingDenominations() {
    final BaseTellerDepositRequest request =
        request(
            new BaseTellerDepositFundingData(
                BaseTellerFundingType.CASH,
                new BigDecimal("125.00"),
                "USD",
                1L,
                List.of(),
                null));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validate(request));
  }

  @Test
  void depositRejectsZeroAmount() {
    final BaseTellerDepositRequest request =
        request(
            new BaseTellerDepositFundingData(
                BaseTellerFundingType.CASH,
                BigDecimal.ZERO,
                "USD",
                1L,
                List.of(new BaseTellerDenominationData("100", new BigDecimal("100.00"), 1L)),
                null));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validate(request));
  }

  @Test
  void checkDepositAcceptsMultipleChecksWhenTotalsMatch() {
    final BaseTellerDepositRequest request =
        request(
            new BaseTellerDepositFundingData(
                BaseTellerFundingType.CHECK,
                new BigDecimal("125.00"),
                "USD",
                2L,
                null,
                List.of(
                    check("ABC", "1", new BigDecimal("100.00"), null),
                    check("ABC", "2", new BigDecimal("25.00"), null))));

    assertDoesNotThrow(() -> validator.validate(request));
  }

  @Test
  void checkDepositRejectsMissingCheckNumber() {
    final BaseTellerDepositRequest request =
        request(
            new BaseTellerDepositFundingData(
                BaseTellerFundingType.CHECK,
                new BigDecimal("125.00"),
                "USD",
                2L,
                null,
                List.of(check("ABC", "", new BigDecimal("125.00"), null))));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validate(request));
  }

  @Test
  void checkDepositRejectsMissingBank() {
    final BaseTellerDepositRequest request =
        request(
            new BaseTellerDepositFundingData(
                BaseTellerFundingType.CHECK,
                new BigDecimal("125.00"),
                "USD",
                2L,
                null,
                List.of(check("", "1", new BigDecimal("125.00"), null))));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validate(request));
  }

  @Test
  void checkDepositRejectsInvalidCheckAmount() {
    final BaseTellerDepositRequest request =
        request(
            new BaseTellerDepositFundingData(
                BaseTellerFundingType.CHECK,
                new BigDecimal("125.00"),
                "USD",
                2L,
                null,
                List.of(check("ABC", "1", BigDecimal.ZERO, null))));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validate(request));
  }

  @Test
  void checkDepositRejectsTotalMismatch() {
    final BaseTellerDepositRequest request =
        request(
            new BaseTellerDepositFundingData(
                BaseTellerFundingType.CHECK,
                new BigDecimal("125.00"),
                "USD",
                2L,
                null,
                List.of(check("ABC", "1", new BigDecimal("120.00"), null))));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validate(request));
  }

  @Test
  void clearedCheckIsValidAtRequestValidationLayer() {
    final BaseTellerDepositRequest request =
        request(
            new BaseTellerDepositFundingData(
                BaseTellerFundingType.CHECK,
                new BigDecimal("125.00"),
                "USD",
                2L,
                null,
                List.of(
                    check(
                        "ABC",
                        "1",
                        new BigDecimal("125.00"),
                        BaseTellerCheckClearingStatus.CLEARED))));

    assertDoesNotThrow(() -> validator.validate(request));
  }

  private static BaseTellerDepositRequest request(final BaseTellerDepositFundingData funding) {
    return new BaseTellerDepositRequest(
        "idem-1", 11L, 33L, "en", "yyyy-MM-dd", "2026-09-13", funding);
  }

  private static BaseTellerDepositCheckRequest check(
      final String bank,
      final String checkNumber,
      final BigDecimal amount,
      final BaseTellerCheckClearingStatus clearingStatus) {
    return new BaseTellerDepositCheckRequest(
        "PERSONAL", bank, checkNumber, amount, clearingStatus, null, null);
  }
}
