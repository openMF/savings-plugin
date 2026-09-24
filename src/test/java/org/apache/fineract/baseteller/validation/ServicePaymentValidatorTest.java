package org.apache.fineract.baseteller.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.ServicePaymentPayerType;
import org.apache.fineract.baseteller.data.ServicePaymentQuoteRequest;
import org.apache.fineract.baseteller.data.ServicePaymentRequest;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.junit.jupiter.api.Test;

class ServicePaymentValidatorTest {

  private final ServicePaymentValidator validator = new ServicePaymentValidator();

  @Test
  void acceptsClientQuoteWithoutTrustingAClientName() {
    assertDoesNotThrow(
        () ->
            validator.validateQuote(
                new ServicePaymentQuoteRequest(
                    ServicePaymentPayerType.CLIENT,
                    11L,
                    "untrusted",
                    3L,
                    "INV-42",
                    new BigDecimal("100.00"),
                    "CRC")));
  }

  @Test
  void requiresNormalizedNonClientName() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            validator.validateQuote(
                new ServicePaymentQuoteRequest(
                    ServicePaymentPayerType.NON_CLIENT,
                    null,
                    "   ",
                    3L,
                    "INV-42",
                    BigDecimal.TEN,
                    "CRC")));
  }

  @Test
  void rejectsMissingClientIdentifier() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            validator.validateQuote(
                new ServicePaymentQuoteRequest(
                    ServicePaymentPayerType.CLIENT,
                    null,
                    null,
                    3L,
                    "INV-42",
                    BigDecimal.TEN,
                    "CRC")));
  }

  @Test
  void rejectsZeroAndNegativeBaseAmounts() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> validator.validateQuote(quote(BigDecimal.ZERO)));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> validator.validateQuote(quote(new BigDecimal("-0.01"))));
  }

  @Test
  void rejectsCheckLikeRequestWithoutPaymentType() {
    final ServicePaymentRequest request =
        payment(
            null, List.of(new BaseTellerDenominationData("bill-100", new BigDecimal("100"), 1L)));
    assertThrows(
        GeneralPlatformDomainRuleException.class, () -> validator.validatePayment(request));
  }

  @Test
  void rejectsDuplicateDenominationsAndNegativeQuantity() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            validator.validatePayment(
                payment(
                    1L,
                    List.of(
                        new BaseTellerDenominationData("bill-100", null, 1L),
                        new BaseTellerDenominationData("BILL-100", null, 1L)))));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            validator.validatePayment(
                payment(1L, List.of(new BaseTellerDenominationData("coin-1", null, -1L)))));
  }

  @Test
  void acceptsBillsAndCoinsWithoutClientCalculatedLineTotals() {
    assertDoesNotThrow(
        () ->
            validator.validatePayment(
                payment(
                    1L,
                    List.of(
                        new BaseTellerDenominationData("bill-100", null, 1L),
                        new BaseTellerDenominationData("coin-5", null, 2L)))));
  }

  private static ServicePaymentQuoteRequest quote(final BigDecimal amount) {
    return new ServicePaymentQuoteRequest(
        ServicePaymentPayerType.NON_CLIENT, null, "Ada", 3L, "INV-42", amount, "CRC");
  }

  private static ServicePaymentRequest payment(
      final Long paymentTypeId, final List<BaseTellerDenominationData> denominations) {
    return new ServicePaymentRequest(
        "idem-1",
        ServicePaymentPayerType.NON_CLIENT,
        null,
        "Ada",
        3L,
        "INV-42",
        new BigDecimal("100"),
        "CRC",
        "2026-09-22",
        paymentTypeId,
        denominations);
  }
}
