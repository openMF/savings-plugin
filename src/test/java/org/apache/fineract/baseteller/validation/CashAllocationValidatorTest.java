package org.apache.fineract.baseteller.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.CashAllocationRequest;
import org.apache.fineract.baseteller.data.CashAllocationType;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.junit.jupiter.api.Test;

class CashAllocationValidatorTest {

  private final CashAllocationValidator validator = new CashAllocationValidator();

  @Test
  void acceptsAllThreeHierarchicalOperationShapes() {
    assertDoesNotThrow(() -> validator.validate(request(CashAllocationType.SAFE_VAULT_OPENING, null, null)));
    assertDoesNotThrow(() -> validator.validate(request(CashAllocationType.HEAD_CASHIER_ALLOCATION, null, 10L)));
    assertDoesNotThrow(() -> validator.validate(request(CashAllocationType.OPERATIONAL_TELLER_ALLOCATION, 10L, 11L)));
  }

  @Test
  void safeOpeningRejectsCashierIdsAndHeadAllocationRejectsSourceCashier() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> validator.validate(request(CashAllocationType.SAFE_VAULT_OPENING, null, 10L)));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> validator.validate(request(CashAllocationType.HEAD_CASHIER_ALLOCATION, 9L, 10L)));
  }

  @Test
  void operationalAllocationRequiresDifferentSourceAndDestination() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> validator.validate(request(CashAllocationType.OPERATIONAL_TELLER_ALLOCATION, 10L, 10L)));
  }

  @Test
  void rejectsNegativeQuantityAndDuplicateIdentifiersCaseInsensitively() {
    final CashAllocationRequest negative =
        withDenominations(List.of(new BaseTellerDenominationData("coin", null, -1L)));
    final CashAllocationRequest duplicate =
        withDenominations(
            List.of(
                new BaseTellerDenominationData("bill", null, 1L),
                new BaseTellerDenominationData("BILL", null, 1L)));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validate(negative));
    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validate(duplicate));
  }

  @Test
  void authoritativeTotalMustMatchAndRespectCurrencyPrecision() {
    final CashAllocationRequest request = request(CashAllocationType.SAFE_VAULT_OPENING, null, null);

    assertDoesNotThrow(
        () -> validator.validateAuthoritativeTotal(request, new BigDecimal("100.00"), 2));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> validator.validateAuthoritativeTotal(request, new BigDecimal("99.99"), 2));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            validator.validateAuthoritativeTotal(
                new CashAllocationRequest(
                    request.idempotencyKey(),
                    request.operationType(),
                    request.officeId(),
                    null,
                    null,
                    request.businessDate(),
                    request.currencyCode(),
                    new BigDecimal("100.001"),
                    request.denominations(),
                    null),
                new BigDecimal("100.001"),
                2));
  }

  private static CashAllocationRequest request(
      final CashAllocationType type, final Long source, final Long destination) {
    return new CashAllocationRequest(
        "allocation-1",
        type,
        1L,
        source,
        destination,
        LocalDate.of(2026, 9, 24),
        "CRC",
        new BigDecimal("100.00"),
        List.of(new BaseTellerDenominationData("bill-100", null, 1L)),
        null);
  }

  private static CashAllocationRequest withDenominations(
      final List<BaseTellerDenominationData> denominations) {
    final CashAllocationRequest base = request(CashAllocationType.SAFE_VAULT_OPENING, null, null);
    return new CashAllocationRequest(
        base.idempotencyKey(),
        base.operationType(),
        base.officeId(),
        null,
        null,
        base.businessDate(),
        base.currencyCode(),
        base.amount(),
        denominations,
        null);
  }
}
