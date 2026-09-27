package org.apache.fineract.baseteller.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.CreditPaymentCheckClassification;
import org.apache.fineract.baseteller.data.CreditPaymentCheckRequest;
import org.apache.fineract.baseteller.data.CreditPaymentMethod;
import org.apache.fineract.baseteller.data.CreditPaymentRequest;
import org.apache.fineract.baseteller.data.CreditPaymentTransitionRequest;
import org.junit.jupiter.api.Test;

class CreditPaymentValidatorTest {

  private final CreditPaymentValidator validator = new CreditPaymentValidator();

  @Test
  void acceptsValidCashPaymentAndBackendValuedDenominations() {
    assertThatCode(() -> validator.validate(cash(null), true)).doesNotThrowAnyException();
  }

  @Test
  void rejectsNegativeQuantity() {
    final CreditPaymentRequest request =
        new CreditPaymentRequest(
            "cash-1", 1L, 2L, CreditPaymentMethod.CASH, new BigDecimal("50"), "USD", 3L,
            null, null, null, null,
            List.of(new BaseTellerDenominationData("50", null, -1L)), null);
    assertThatThrownBy(() -> validator.validate(request, true))
        .hasMessageContaining("quantity cannot be negative");
  }

  @Test
  void rejectsDuplicateDenominationsCaseInsensitively() {
    final CreditPaymentRequest request =
        new CreditPaymentRequest(
            "cash-1", 1L, 2L, CreditPaymentMethod.CASH, new BigDecimal("50"), "USD", 3L,
            null, null, null, null,
            List.of(
                new BaseTellerDenominationData("note-50", null, 1L),
                new BaseTellerDenominationData("NOTE-50", null, 1L)), null);
    assertThatThrownBy(() -> validator.validate(request, true))
        .hasMessageContaining("only once");
  }

  @Test
  void acceptsPendingAndClearedCheckClassifications() {
    assertThatCode(() -> validator.validate(check(CreditPaymentCheckClassification.SUBJECT_TO_COLLECTION), true))
        .doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(check(CreditPaymentCheckClassification.CLEARED_FUNDS), true))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsCheckWithoutBankOrNumber() {
    final CreditPaymentRequest request =
        new CreditPaymentRequest(
            "check-1", 1L, 2L, CreditPaymentMethod.CHECK, new BigDecimal("50"), "USD", 4L,
            null, null, null, null, List.of(),
            new CreditPaymentCheckRequest(null, "PERSONAL", "", null, null,
                CreditPaymentCheckClassification.SUBJECT_TO_COLLECTION));
    assertThatThrownBy(() -> validator.validate(request, true)).hasMessageContaining("bankId");
  }

  @Test
  void requiresIdempotencyForPostingButNotPreview() {
    assertThatCode(() -> validator.validate(cash(""), false)).doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.validate(cash(""), true)).hasMessageContaining("idempotencyKey");
  }

  @Test
  void returnTransitionRequiresReason() {
    assertThatThrownBy(
            () -> validator.validateTransition(new CreditPaymentTransitionRequest("return-1", null, null, null, ""), true))
        .hasMessageContaining("return reason");
  }

  private static CreditPaymentRequest cash(final String keyOverride) {
    return new CreditPaymentRequest(
        keyOverride == null ? "cash-1" : keyOverride, 1L, 2L, CreditPaymentMethod.CASH,
        new BigDecimal("50"), "USD", 3L, null, null, null, null,
        List.of(new BaseTellerDenominationData("note-50", null, 1L)), null);
  }

  private static CreditPaymentRequest check(final CreditPaymentCheckClassification classification) {
    return new CreditPaymentRequest(
        "check-1", 1L, 2L, CreditPaymentMethod.CHECK, new BigDecimal("50"), "USD", 4L,
        null, null, null, null, List.of(),
        new CreditPaymentCheckRequest(5L, "PERSONAL", "12345", "987", "111", classification));
  }
}
