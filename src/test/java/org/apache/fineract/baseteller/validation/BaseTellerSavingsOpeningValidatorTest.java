package org.apache.fineract.baseteller.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerCheckData;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.BaseTellerFundingData;
import org.apache.fineract.baseteller.data.BaseTellerFundingType;
import org.apache.fineract.baseteller.data.BaseTellerSavingsOpeningRequest;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.junit.jupiter.api.Test;

class BaseTellerSavingsOpeningValidatorTest {

  private final BaseTellerSavingsOpeningValidator validator =
      new BaseTellerSavingsOpeningValidator();

  @Test
  void cashFundingRequiresDenominationsToEqualAmount() {
    final BaseTellerSavingsOpeningRequest request =
        request(
            new BaseTellerFundingData(
                BaseTellerFundingType.CASH,
                new BigDecimal("125.00"),
                "USD",
                1L,
                List.of(
                    new BaseTellerDenominationData("100", new BigDecimal("100.00"), 1L),
                    new BaseTellerDenominationData("20", new BigDecimal("20.00"), 1L)),
                null));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validate(request));
  }

  @Test
  void cashFundingAcceptsExactServerCalculatedTotal() {
    final BaseTellerSavingsOpeningRequest request =
        request(
            new BaseTellerFundingData(
                BaseTellerFundingType.CASH,
                new BigDecimal("120.00"),
                "USD",
                1L,
                List.of(
                    new BaseTellerDenominationData("100", new BigDecimal("100.00"), 1L),
                    new BaseTellerDenominationData("20", new BigDecimal("20.00"), 1L)),
                null));

    assertDoesNotThrow(() -> validator.validate(request));
  }

  @Test
  void checkFundingRequiresCheckMetadata() {
    final BaseTellerSavingsOpeningRequest request =
        request(
            new BaseTellerFundingData(
                BaseTellerFundingType.CHECK,
                new BigDecimal("120.00"),
                "USD",
                2L,
                null,
                new BaseTellerCheckData("PERSONAL", "", "12345", null, null)));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> validator.validate(request));
  }

  private static BaseTellerSavingsOpeningRequest request(final BaseTellerFundingData funding) {
    final JsonObject account = new JsonObject();
    account.addProperty("clientId", 11L);
    account.addProperty("productId", 22L);
    return new BaseTellerSavingsOpeningRequest(
        "idem-1", 11L, 22L, account, true, true, "en", "yyyy-MM-dd", "2026-09-13", funding);
  }
}
