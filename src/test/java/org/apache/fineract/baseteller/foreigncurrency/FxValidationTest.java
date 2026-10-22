package org.apache.fineract.baseteller.foreigncurrency;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.junit.jupiter.api.Test;

class FxValidationTest {
  private static final String RATE =
      """
      {"effectiveDate":"2026-10-21","baseCurrency":"USD","quoteCurrency":"EUR",
       "dailyBaseRate":2.34,"branches":[{"officeId":1,"expectedVersion":0,"buyPoints":-1,"sellPoints":3}]}
      """;
  static final String TRADE =
      """
      {"clientId":1,"identifierId":1,"reference":"synthetic","quoteCurrency":"EUR","usdAmount":100,
       "rateDraftId":1,"rateVersion":1,"usdDenominations":[{"denominationId":"usd-note","quantity":1}],
       "quoteDenominations":[{"denominationId":"eur-note","quantity":2}]}
      """;

  @Test
  void exactDraftValuesAreStoredWithoutInterpretingPoints() {
    var r = FxValidation.rate(RATE);
    assertEquals(new BigDecimal("-1"), r.branches().getFirst().buyPoints());
    assertEquals(new BigDecimal("2.34"), r.dailyBaseRate());
    assertFalse(FxValidation.capabilities().publicationEnabled());
  }

  @Test
  void financialOverridesAndForeignScopeCannotBeSmuggledThroughPayloads() {
    for (String field :
        List.of(
            "appliedRate",
            "roundingMode",
            "officeId",
            "cashierId",
            "status",
            "nationalCurrency",
            "execute"))
      assertThrows(
          GeneralPlatformDomainRuleException.class,
          () ->
              FxValidation.trade(
                  TRADE.replace("{\"clientId\"", "{\"" + field + "\":1,\"clientId\"")));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            FxValidation.rate(
                RATE.replace("\"dailyBaseRate\"", "\"active\":true,\"dailyBaseRate\"")));
  }

  @Test
  void invalidDatesPairsRatesVersionsAndPrecisionAreRejected() {
    for (String json :
        List.of(
            RATE.replace("2026-10-21", "2026-02-30"),
            RATE.replace("\"EUR\"", "\"USD\""),
            RATE.replace("2.34", "0"),
            RATE.replace("2.34", "-1"),
            RATE.replace("2.34", "0.1234567890123"),
            RATE.replace("2.34", "1e100"),
            RATE.replace("\"expectedVersion\":0", "\"expectedVersion\":-1"),
            RATE.replace("\"officeId\":1", "\"officeId\":1.5")))
      assertThrows(GeneralPlatformDomainRuleException.class, () -> FxValidation.rate(json));
  }

  @Test
  void malformedTypesDuplicateDenominationsAndInvalidMoneyAreRejected() {
    for (String json :
        List.of(
            "null",
            "[]",
            "{}",
            TRADE.replace("\"usdAmount\":100", "\"usdAmount\":0"),
            TRADE.replace("\"quantity\":1", "\"quantity\":-1"),
            TRADE.replace("\"quantity\":1", "\"quantity\":1.5"),
            TRADE.replace("\"quantity\":1", "\"quantity\":9223372036854775808"),
            TRADE.replace("\"quantity\":1", "\"quantity\":\"1\""),
            TRADE.replace("\"reference\":\"synthetic\"", "\"reference\":\"\""),
            TRADE.replace("\"usdAmount\":100", "\"usdAmount\":1e100"),
            TRADE.replace("\"usdAmount\":100", "\"usdAmount\":-100")))
      assertThrows(GeneralPlatformDomainRuleException.class, () -> FxValidation.trade(json));
    var r = FxValidation.trade(TRADE);
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            FxValidation.validate(
                new FxData.TradeRequest(
                    r.clientId(),
                    r.identifierId(),
                    r.reference(),
                    r.quoteCurrency(),
                    r.usdAmount(),
                    r.rateDraftId(),
                    r.rateVersion(),
                    List.of(r.usdDenominations().getFirst(), r.usdDenominations().getFirst()),
                    r.quoteDenominations())));
  }

  @Test
  void outgoingStockIsRequiredAndCurrenciesCannotBeMixed() {
    var currency = new FxData.Currency("USD", "US Dollar", 2);
    var catalog = List.of(new FxData.Denomination("note", "USD", "NOTE", new BigDecimal("100"), 1));
    var lines = List.of(new FxData.Quantity("note", new BigDecimal("2")));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> FxTellerService.leg(lines, catalog, currency, FxData.Direction.OUTFLOW));
    assertEquals(
        new BigDecimal("200"),
        FxTellerService.leg(lines, catalog, currency, FxData.Direction.INFLOW).denominationTotal());
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            FxTellerService.leg(
                lines, catalog, new FxData.Currency("EUR", "Euro", 2), FxData.Direction.INFLOW));
  }

  @Test
  void incomingOverflowIsRejectedWithoutTruncation() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            FxTellerService.leg(
                List.of(new FxData.Quantity("note", BigDecimal.ONE)),
                List.of(
                    new FxData.Denomination("note", "USD", "NOTE", BigDecimal.ONE, Long.MAX_VALUE)),
                new FxData.Currency("USD", "Dollar", 2),
                FxData.Direction.INFLOW));
  }
}
