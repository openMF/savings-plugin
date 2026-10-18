package org.apache.fineract.baseteller.withdrawal;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class WithdrawalValidationTest {
  private static final String VALID =
      """
      {"clientId":1,"accountId":2,"method":"CASH","amount":100,"paymentTypeId":3,
       "identificationTypeId":4,"identificationReference":"sensitive-1234",
       "denominations":[{"denominationId":"configured-note","quantity":2}],"idempotencyKey":"test-1"}
      """;

  @Test
  void acceptsOnlyUserInputAndProtectsIdentificationInToString() {
    var r = WithdrawalValidation.parse(VALID, true);
    assertEquals(0, r.amount().compareTo(new BigDecimal("100")));
    assertFalse(r.toString().contains("sensitive-1234"));
    assertEquals("****1234", WithdrawalValidation.mask(r.identificationReference()));
    assertEquals("****", WithdrawalValidation.mask("1234"));
  }

  @Test
  void rejectsUntrustedFieldsWrongJsonTypesAndFractionalIdentifiers() {
    for (String json :
        List.of(
            "null",
            "[]",
            "{}",
            VALID.replace("\"accountId\":2", "\"accountId\":2.5"),
            VALID.replace("\"amount\":100", "\"amount\":\"100\""),
            VALID.replace("\"quantity\":2", "\"quantity\":0.5"),
            VALID.replace("\"method\":\"CASH\"", "\"method\":7"),
            VALID.replace("\"clientId\":1", "\"sourceCashierId\":1,\"clientId\":1"),
            VALID.replace("\"quantity\":2", "\"value\":50,\"quantity\":2")))
      assertThrows(RuntimeException.class, () -> WithdrawalValidation.parse(json, true));
  }

  @Test
  void rejectsInvalidAmountsIdentitiesQuantitiesAndDuplicateRows() {
    for (String amount : List.of("0", "-1", "10000000000000"))
      assertThrows(
          RuntimeException.class,
          () ->
              WithdrawalValidation.parse(
                  VALID.replace("\"amount\":100", "\"amount\":" + amount), true));
    for (String json :
        List.of(
            VALID.replace("sensitive-1234", ""),
            VALID.replace("\"identificationTypeId\":4", "\"identificationTypeId\":0"),
            VALID.replace("\"quantity\":2", "\"quantity\":-1"),
            VALID.replace("\"quantity\":2", "\"quantity\":9223372036854775808"),
            VALID.replace("\"idempotencyKey\":\"test-1\"", "\"idempotencyKey\":\"invalid key\""),
            VALID.replace(
                "\"quantity\":2}",
                "\"quantity\":2},{\"denominationId\":\"configured-note\",\"quantity\":1}")))
      assertThrows(RuntimeException.class, () -> WithdrawalValidation.parse(json, true));
  }

  @Test
  void checkCannotReachPostingAndPreviewDoesNotRequireIdempotencyKey() {
    assertTrue(
        assertThrows(
                RuntimeException.class,
                () -> WithdrawalValidation.parse(VALID.replace("CASH", "CHECK"), true))
            .getMessage()
            .contains("Outgoing check issuance"));
    assertNotNull(
        WithdrawalValidation.parse(VALID.replace(",\"idempotencyKey\":\"test-1\"", ""), false));
    assertThrows(
        RuntimeException.class,
        () ->
            WithdrawalValidation.parse(VALID.replace(",\"idempotencyKey\":\"test-1\"", ""), true));
  }

  @Test
  void fingerprintsNormalizeAmountsQuantitiesAndZeroRowsAndBindActorAndIdentity() {
    var a = WithdrawalValidation.parse(VALID, true);
    var b =
        WithdrawalValidation.parse(
            VALID
                .replace("\"amount\":100", "\"amount\":100.00")
                .replace(
                    "\"quantity\":2}",
                    "\"quantity\":2.0},{\"denominationId\":\"zero\",\"quantity\":0}"),
            true);
    assertEquals(WithdrawalValidation.fingerprint(a, 7L), WithdrawalValidation.fingerprint(b, 7L));
    assertNotEquals(
        WithdrawalValidation.fingerprint(a, 7L), WithdrawalValidation.fingerprint(a, 8L));
    var c = WithdrawalValidation.parse(VALID.replace("sensitive-1234", "other-1234"), true);
    assertNotEquals(
        WithdrawalValidation.fingerprint(a, 7L), WithdrawalValidation.fingerprint(c, 7L));
  }
}
