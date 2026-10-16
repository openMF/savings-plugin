package org.apache.fineract.baseteller.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class TransferValidationTest {
  static TransferRequest request(
      TransferOperation op, TransferAccountType type, Long target, String amount) {
    return new TransferRequest(
        op, 1L, 10L, type, target, amount == null ? null : new BigDecimal(amount), "test-key");
  }

  static TransferData.Account account(
      long id, long owner, String currency, boolean debit, boolean credit) {
    return new TransferData.Account(
        id,
        "account-" + id,
        TransferAccountType.SAVINGS,
        new TransferData.Client(
            owner, "client-" + owner, "Client", "Person", "Active", 1L, "Office", true),
        1L,
        "Product",
        currency,
        2,
        "ACTIVE",
        new BigDecimal("1000"),
        debit,
        credit);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"0", "-1", "0.0000001", "10000000000000", "1E+99"})
  void rejectsInvalidAmounts(String amount) {
    assertThrows(
        RuntimeException.class,
        () ->
            TransferValidation.request(
                request(
                    TransferOperation.BETWEEN_MY_ACCOUNTS,
                    TransferAccountType.SAVINGS,
                    11L,
                    amount),
                true));
  }

  @Test
  void positiveDecimalAndIdempotencyKeyAreRequired() {
    var r =
        request(TransferOperation.BETWEEN_MY_ACCOUNTS, TransferAccountType.SAVINGS, 11L, "12.25");
    assertDoesNotThrow(() -> TransferValidation.request(r, true));
    assertThrows(
        RuntimeException.class,
        () ->
            TransferValidation.request(
                new TransferRequest(
                    r.operationType(), 1L, 10L, r.targetAccountType(), 11L, r.amount(), null),
                true));
    assertDoesNotThrow(
        () ->
            TransferValidation.request(
                new TransferRequest(
                    r.operationType(), 1L, 10L, r.targetAccountType(), 11L, r.amount(), null),
                false));
  }

  @Test
  void rejectsMissingAccountIdentityAndUnknownOperation() {
    assertThrows(RuntimeException.class, () -> TransferValidation.request(null, false));
    assertThrows(
        RuntimeException.class,
        () ->
            TransferValidation.request(
                new TransferRequest(
                    null, 1L, 10L, TransferAccountType.SAVINGS, 11L, BigDecimal.ONE, "key"),
                false));
  }

  @Test
  void validatesOwnAccountOwnershipAndOrderingClient() {
    var r = request(TransferOperation.BETWEEN_MY_ACCOUNTS, TransferAccountType.SAVINGS, 11L, "10");
    assertDoesNotThrow(
        () ->
            TransferValidation.relationship(
                r, account(10, 1, "AAA", true, true), account(11, 1, "AAA", true, true)));
    assertThrows(
        RuntimeException.class,
        () ->
            TransferValidation.relationship(
                r, account(10, 2, "AAA", true, true), account(11, 2, "AAA", true, true)));
    assertThrows(
        RuntimeException.class,
        () ->
            TransferValidation.relationship(
                r, account(10, 1, "AAA", true, true), account(11, 2, "AAA", true, true)));
  }

  @Test
  void validatesThirdPartyOwnership() {
    var r = request(TransferOperation.TO_THIRD_PARTY, TransferAccountType.SAVINGS, 11L, "10");
    assertDoesNotThrow(
        () ->
            TransferValidation.relationship(
                r, account(10, 1, "AAA", true, true), account(11, 2, "AAA", true, true)));
    assertThrows(
        RuntimeException.class,
        () ->
            TransferValidation.relationship(
                r, account(10, 1, "AAA", true, true), account(11, 1, "AAA", true, true)));
  }

  @Test
  void rejectsSelfTransferCurrencyMismatchAndPrecision() {
    var r = request(TransferOperation.BETWEEN_MY_ACCOUNTS, TransferAccountType.SAVINGS, 11L, "10");
    assertThrows(
        RuntimeException.class,
        () ->
            TransferValidation.relationship(
                r, account(10, 1, "AAA", true, true), account(10, 1, "AAA", true, true)));
    assertThrows(
        RuntimeException.class,
        () ->
            TransferValidation.relationship(
                r, account(10, 1, "AAA", true, true), account(11, 1, "BBB", true, true)));
    var fractional =
        request(TransferOperation.BETWEEN_MY_ACCOUNTS, TransferAccountType.SAVINGS, 11L, "1.001");
    assertThrows(
        RuntimeException.class,
        () ->
            TransferValidation.relationship(
                fractional, account(10, 1, "AAA", true, true), account(11, 1, "AAA", true, true)));
  }

  @Test
  void rejectsIneligibleAccounts() {
    var r = request(TransferOperation.BETWEEN_MY_ACCOUNTS, TransferAccountType.SAVINGS, 11L, "10");
    assertThrows(
        RuntimeException.class,
        () ->
            TransferValidation.relationship(
                r, account(10, 1, "AAA", false, true), account(11, 1, "AAA", true, true)));
    assertThrows(
        RuntimeException.class,
        () ->
            TransferValidation.relationship(
                r, account(10, 1, "AAA", true, true), account(11, 1, "AAA", true, false)));
  }

  @Test
  void nativeWithdrawableFundsIncludeFees() {
    assertDoesNotThrow(
        () ->
            TransferValidation.funds(new BigDecimal("99"), BigDecimal.ONE, new BigDecimal("100")));
    assertThrows(
        RuntimeException.class,
        () ->
            TransferValidation.funds(new BigDecimal("100"), BigDecimal.ONE, new BigDecimal("100")));
  }

  @Test
  void fingerprintNormalizesDecimalRepresentationsAndIncludesTargetType() {
    var r =
        request(TransferOperation.BETWEEN_MY_ACCOUNTS, TransferAccountType.SAVINGS, 11L, "10.00");
    assertEquals(
        AccountTransferPlatformService.fingerprint(r),
        AccountTransferPlatformService.fingerprint(
            request(r.operationType(), r.targetAccountType(), 11L, "10")));
    assertNotEquals(
        AccountTransferPlatformService.fingerprint(r),
        AccountTransferPlatformService.fingerprint(
            request(r.operationType(), TransferAccountType.LOAN, 11L, "10")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"amount\":\"NaN\"}",
        "{\"amount\":\"Infinity\"}",
        "null",
        "[]",
        "{}",
        "{\"operationType\":\"unknown\"}"
      })
  void malformedRequestsFailBeforePosting(String json) {
    assertThrows(RuntimeException.class, () -> BaseTellerAccountTransfersApiResource.parse(json));
  }
}
