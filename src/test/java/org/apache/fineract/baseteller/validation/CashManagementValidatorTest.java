package org.apache.fineract.baseteller.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.CashOperationRequest;
import org.apache.fineract.baseteller.data.CashOperationType;
import org.apache.fineract.baseteller.data.CashierClosingRequest;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.junit.jupiter.api.Test;

class CashManagementValidatorTest {

  private final CashManagementValidator validator = new CashManagementValidator();

  @Test
  void calculatesBanknotesAndCoinsUsingBigDecimal() {
    final BigDecimal total =
        validator.validateDenominations(
            List.of(
                denomination("100", "100.00", 2),
                denomination("coin-25", "0.25", 3),
                denomination("coin-01", "0.01", 5)),
            2);

    assertEquals(new BigDecimal("200.80"), total);
  }

  @Test
  void acceptsEmptyPhysicalCashForCheckOnlyClosing() {
    assertEquals(BigDecimal.ZERO, validator.validateDenominations(List.of(), 2));
  }

  @Test
  void rejectsDuplicateDenominationRegardlessOfScale() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            validator.validateDenominations(
                List.of(denomination("a", "10.0", 1), denomination("b", "10.00", 2)), 2));
  }

  @Test
  void rejectsNegativeQuantity() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> validator.validateDenominations(List.of(denomination("10", "10", -1)), 2));
  }

  @Test
  void rejectsZeroAndNegativeDenominationValues() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> validator.validateDenominations(List.of(denomination("zero", "0", 1)), 2));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> validator.validateDenominations(List.of(denomination("negative", "-1", 1)), 2));
  }

  @Test
  void rejectsDenominationBeyondCurrencyPrecision() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> validator.validateDenominations(List.of(denomination("fraction", "0.001", 1)), 2));
  }

  @Test
  void closingRejectsDuplicateChecks() {
    final CashierClosingRequest request =
        new CashierClosingRequest(
            "close-1", 9L, LocalDate.of(2026, 9, 22), "USD", List.of(), List.of(4L, 4L));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validateClosing(request, 2));
  }

  @Test
  void closingRejectsInvalidCheckId() {
    final CashierClosingRequest request =
        new CashierClosingRequest(
            "close-1", 9L, LocalDate.of(2026, 9, 22), "USD", List.of(), List.of(0L));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validateClosing(request, 2));
  }

  @Test
  void operationRequiresPhysicalAssets() {
    final CashOperationRequest request =
        new CashOperationRequest(
            "deposit-1",
            CashOperationType.BANK_DEPOSIT,
            9L,
            LocalDate.of(2026, 9, 22),
            "USD",
            List.of(),
            List.of(),
            null);

    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validateOperation(request, 2));
  }

  @Test
  void operationAcceptsDepositInTransitWithExactCashCount() {
    final CashOperationRequest request =
        new CashOperationRequest(
            "deposit-1",
            CashOperationType.DEPOSIT_IN_TRANSIT,
            9L,
            LocalDate.of(2026, 9, 22),
            "USD",
            List.of(denomination("20", "20", 3)),
            List.of(),
            "Night safe");

    validator.validateOperation(request, 2);
  }

  private static BaseTellerDenominationData denomination(
      final String id, final String value, final long quantity) {
    return new BaseTellerDenominationData(id, new BigDecimal(value), quantity);
  }
}
