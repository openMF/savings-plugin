package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.baseteller.data.TransactionReversalData.*;
import org.apache.fineract.baseteller.data.TransactionReversalRequest;
import org.apache.fineract.baseteller.data.TransactionReversalRequest.Quantity;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.junit.jupiter.api.Test;

class TransactionReversalPlatformServiceTest {
  private static final BigDecimal AMOUNT = new BigDecimal("1000");
  private static final List<Denomination> CATALOG =
      List.of(new Denomination("note", new BigDecimal("100"), "BANKNOTE", "AAA"));

  @Test
  void authoritativeTotalIgnoresCallerValuesAndUsesDecimalArithmetic() {
    var lines =
        TransactionReversalPlatformService.validateQuantities(
            List.of(new Quantity("note", 10L)), AMOUNT, CATALOG);
    assertEquals(new BigDecimal("100"), lines.getFirst().value());
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            TransactionReversalPlatformService.validateQuantities(
                List.of(new Quantity("note", 9L)), AMOUNT, CATALOG));
  }

  @Test
  void rejectsMissingUnknownDuplicateNonpositiveAndOverflowingQuantities() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> TransactionReversalPlatformService.validateQuantities(null, AMOUNT, CATALOG));
    for (var lines :
        List.of(
            List.of(new Quantity("other", 10L)),
            List.of(new Quantity("note", 0L)),
            List.of(new Quantity("note", -1L)),
            List.of(new Quantity("note", Long.MAX_VALUE)),
            List.of(new Quantity("note", 5L), new Quantity("note", 5L))))
      assertThrows(
          GeneralPlatformDomainRuleException.class,
          () -> TransactionReversalPlatformService.validateQuantities(lines, AMOUNT, CATALOG));
  }

  @Test
  void checkOnlyCannotAddCashAndCashCannotOmitBreakdown() {
    assertTrue(
        TransactionReversalPlatformService.validateQuantities(null, BigDecimal.ZERO, CATALOG)
            .isEmpty());
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            TransactionReversalPlatformService.validateQuantities(
                List.of(new Quantity("note", 1L)), BigDecimal.ZERO, CATALOG));
  }

  private TransactionReversalHandler.Source checkSource(boolean eligible) {
    return new TransactionReversalHandler.Source(
        1L,
        1L,
        1L,
        1L,
        1L,
        LocalDate.of(2026, 1, 1),
        "CLEARED",
        "AAA",
        AMOUNT,
        BigDecimal.ZERO,
        AMOUNT,
        "IN",
        "cashier",
        "PAY_CREDIT",
        null,
        List.of(new Check(7L, 8L, "Bank", "ABC123", AMOUNT, "AAA", eligible)),
        List.of());
  }

  @Test
  void validCheckMatchesOriginalIdentityAndAmount() {
    assertDoesNotThrow(
        () ->
            TransactionReversalPlatformService.validateChecks(
                List.of(new TransactionReversalRequest.Check(7L, 8L, "ABC123", AMOUNT)),
                checkSource(true)));
  }

  @Test
  void unrelatedWrongBankWrongNumberWrongAmountAndNonpositiveCheckFail() {
    for (var check :
        List.of(
            new TransactionReversalRequest.Check(99L, 8L, "ABC123", AMOUNT),
            new TransactionReversalRequest.Check(7L, 99L, "ABC123", AMOUNT),
            new TransactionReversalRequest.Check(7L, 8L, "other", AMOUNT),
            new TransactionReversalRequest.Check(7L, 8L, "ABC123", new BigDecimal("999")),
            new TransactionReversalRequest.Check(7L, 8L, "ABC123", BigDecimal.ZERO)))
      assertThrows(
          GeneralPlatformDomainRuleException.class,
          () ->
              TransactionReversalPlatformService.validateChecks(List.of(check), checkSource(true)));
  }

  @Test
  void missingDuplicateAndIneligibleChecksFail() {
    var check = new TransactionReversalRequest.Check(7L, 8L, "ABC123", AMOUNT);
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> TransactionReversalPlatformService.validateChecks(List.of(), checkSource(true)));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            TransactionReversalPlatformService.validateChecks(
                List.of(check, check), checkSource(true)));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            TransactionReversalPlatformService.validateChecks(List.of(check), checkSource(false)));
  }

  @Test
  void canonicalIdsRejectMalformedAndOversizedInput() {
    assertEquals(
        "SAVINGS_DEPOSIT", TransactionReversalPlatformService.sourceType("SAVINGS_DEPOSIT:12"));
    assertEquals(12L, TransactionReversalPlatformService.sourceId("SAVINGS_DEPOSIT:12"));
    for (String id :
        List.of(
            "1",
            "SAVINGS_DEPOSIT:-1",
            "SAVINGS_DEPOSIT:0",
            "SAVINGS_DEPOSIT:01",
            "SAVINGS_DEPOSIT:999999999999999999999",
            "SAVINGS_DEPOSIT:1 OR 1=1"))
      assertThrows(
          GeneralPlatformDomainRuleException.class,
          () -> TransactionReversalPlatformService.sourceId(id));
  }

  @Test
  void fingerprintIsOrderIndependentAndSensitiveToBreakdown() {
    var a =
        new TransactionReversalRequest(
            "SAVINGS_DEPOSIT:1",
            "key",
            List.of(new Quantity("a", 1L), new Quantity("b", 2L)),
            null,
            null);
    var b =
        new TransactionReversalRequest(
            "SAVINGS_DEPOSIT:1",
            "key",
            List.of(new Quantity("b", 2L), new Quantity("a", 1L)),
            List.of(),
            List.of());
    assertEquals(
        TransactionReversalPlatformService.fingerprint(a),
        TransactionReversalPlatformService.fingerprint(b));
    assertNotEquals(
        TransactionReversalPlatformService.fingerprint(a),
        TransactionReversalPlatformService.fingerprint(
            new TransactionReversalRequest(
                "SAVINGS_DEPOSIT:1", "key", List.of(new Quantity("a", 3L)), null, null)));
  }

  @Test
  void authorizationResponseHasNoSecretBearingToString() {
    var issued =
        new TransactionReversalPlatformService.IssuedAuthorization(
            1L, "sensitive-number", java.time.OffsetDateTime.now());
    assertFalse(issued.toString().contains("sensitive-number"));
    assertEquals(64, TransactionReversalPlatformService.hash("sensitive-number").length());
  }

  @Test
  void idempotencyIsMandatoryAndMalformedDeclarationsFailBeforePersistence() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            TransactionReversalPlatformService.validateRequest(
                new TransactionReversalRequest("SAVINGS_DEPOSIT:1", "", null, null, null)));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            TransactionReversalPlatformService.validateRequest(
                new TransactionReversalRequest(
                    "SAVINGS_DEPOSIT:1", "key", List.of(new Quantity(null, 1L)), null, null)));
  }

  @Test
  void sourceHandlersDeclareDistinctAuthoritativeStrategies() {
    var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    var commands = mock(TransactionReversalCommands.class);
    var handlers =
        List.of(
            new SavingsDepositReversalHandler(jdbc, commands),
            new CreditPaymentReversalHandler(jdbc, commands),
            new ServicePaymentReversalHandler(jdbc, commands),
            new CashExchangeReversalHandler(jdbc));
    assertEquals(
        4, handlers.stream().map(TransactionReversalHandler::sourceType).distinct().count());
    assertTrue(handlers.get(0).accountingStrategy().contains("savings undo"));
    assertTrue(handlers.get(1).accountingStrategy().contains("loan repayment undo"));
    assertTrue(handlers.get(2).accountingStrategy().contains("journal reversal"));
    assertTrue(handlers.get(3).accountingStrategy().contains("no accounting"));
  }

  @Test
  void physicalCashDirectionIsTheInverseOfTheOriginalOperation() {
    assertEquals(
        AMOUNT.negate(), TransactionReversalPlatformService.physicalCashEffect("IN", AMOUNT));
    assertEquals(AMOUNT, TransactionReversalPlatformService.physicalCashEffect("OUT", AMOUNT));
    assertEquals(
        BigDecimal.ZERO, TransactionReversalPlatformService.physicalCashEffect("SWAP", AMOUNT));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> TransactionReversalPlatformService.physicalCashEffect("UNKNOWN", AMOUNT));
  }
}
