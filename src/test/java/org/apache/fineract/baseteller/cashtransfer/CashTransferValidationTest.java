package org.apache.fineract.baseteller.cashtransfer;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CashTransferValidationTest {
  private static final String VALID =
      """
      {"destinationCashierId":2,"idempotencyKey":"key-1","currencies":[
        {"currencyCode":"AAA","denominations":[{"denominationId":"note","quantity":2}]}]}
      """;

  @Test
  void acceptsOnlySelectionAndKeepsExactQuantities() {
    var request = CashTransferValidation.parse(VALID, true);
    assertEquals(2L, request.destinationCashierId());
    assertEquals(
        2L,
        CashTransferValidation.quantity(
            request.currencies().getFirst().denominations().getFirst().quantity()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"-1", "0.5", "9223372036854775808", "null", "\"2\"", "true", "{}", "[]", "1e100"})
  void rejectsInvalidQuantities(String value) {
    assertThrows(
        RuntimeException.class,
        () ->
            CashTransferValidation.parse(
                VALID.replace("\"quantity\":2", "\"quantity\":" + value), true));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "0", "1.5", "null", "\"2\"", "true", "9223372036854775808"})
  void rejectsInvalidDestinationId(String value) {
    assertThrows(
        RuntimeException.class,
        () ->
            CashTransferValidation.parse(
                VALID.replace("\"destinationCashierId\":2", "\"destinationCashierId\":" + value),
                true));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sourceCashierId",
        "cashierId",
        "amount",
        "total",
        "currentInventory",
        "denominationValue",
        "authorization",
        "checks"
      })
  void rejectsClientOwnedSourceValuesAndChecks(String key) {
    assertThrows(
        RuntimeException.class,
        () -> CashTransferValidation.parse(VALID.replaceFirst("\\{", "{\"" + key + "\":1,"), true));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "key with spaces", "key<script>"})
  void rejectsInvalidKeys(String value) {
    assertThrows(
        RuntimeException.class,
        () -> CashTransferValidation.parse(VALID.replace("key-1", value), true));
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "{}", "[]", "{", "true", "\"body\""})
  void rejectsMalformedBody(String body) {
    assertThrows(RuntimeException.class, () -> CashTransferValidation.parse(body, true));
  }

  @Test
  void rejectsDuplicateDenominationsAndCurrencies() {
    var q = new CashTransferRequest.Quantity("note", BigDecimal.ONE);
    var c = new CashTransferRequest.Currency("AAA", List.of(q, q));
    assertThrows(
        RuntimeException.class,
        () ->
            CashTransferValidation.validate(new CashTransferRequest(2L, "key", List.of(c)), true));
    var valid = new CashTransferRequest.Currency("AAA", List.of(q));
    assertThrows(
        RuntimeException.class,
        () ->
            CashTransferValidation.validate(
                new CashTransferRequest(
                    2L, "key", List.of(valid, new CashTransferRequest.Currency("aaa", List.of(q)))),
                true));
  }

  @Test
  void rejectsEmptyAndZeroTransfer() {
    assertThrows(
        RuntimeException.class,
        () ->
            CashTransferValidation.parse(VALID.replace("\"quantity\":2", "\"quantity\":0"), true));
    assertThrows(
        RuntimeException.class,
        () -> CashTransferValidation.validate(new CashTransferRequest(2L, "key", List.of()), true));
  }

  @Test
  void previewDoesNotRequireKeyButPostingDoes() {
    var json = VALID.replace("\"idempotencyKey\":\"key-1\",", "");
    assertDoesNotThrow(() -> CashTransferValidation.parse(json, false));
    assertThrows(RuntimeException.class, () -> CashTransferValidation.parse(json, true));
  }

  @Test
  void canonicalFingerprintIncludesActorAndDestinationAndIgnoresOrdering() {
    var a = new CashTransferRequest.Quantity("a", new BigDecimal("2.00"));
    var b = new CashTransferRequest.Quantity("b", BigDecimal.ONE);
    var r =
        new CashTransferRequest(
            2L, "key", List.of(new CashTransferRequest.Currency("AAA", List.of(a, b))));
    var ordered =
        new CashTransferRequest(
            2L, "different-key", List.of(new CashTransferRequest.Currency("aaa", List.of(b, a))));
    assertEquals(
        CashTransferValidation.fingerprint(r, 1L), CashTransferValidation.fingerprint(ordered, 1L));
    assertNotEquals(
        CashTransferValidation.fingerprint(r, 1L), CashTransferValidation.fingerprint(r, 2L));
    assertNotEquals(
        CashTransferValidation.fingerprint(r, 1L),
        CashTransferValidation.fingerprint(new CashTransferRequest(3L, "key", r.currencies()), 1L));
  }
}
