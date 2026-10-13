package org.apache.fineract.baseteller.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.fineract.baseteller.service.TransactionReversalPlatformService;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.junit.jupiter.api.Test;

class TransactionReversalsApiResourceTest {
  private final TransactionReversalPlatformService service =
      mock(TransactionReversalPlatformService.class);
  private final TransactionReversalsApiResource api = new TransactionReversalsApiResource(service);

  @Test
  void rejectsAuthoritativeAndUnknownFields() {
    for (String field : java.util.List.of("amount", "currencyCode", "direction", "value")) {
      assertThrows(
          GeneralPlatformDomainRuleException.class, () -> api.reverse("{\"" + field + "\":100}"));
    }
    verifyNoInteractions(service);
  }

  @Test
  void rejectsFractionalAndOverflowingQuantityWithoutTruncation() {
    for (String quantity : java.util.List.of("1.5", "9223372036854775808", "\"10\""))
      assertThrows(
          GeneralPlatformDomainRuleException.class,
          () ->
              api.preview(
                  "{\"cashDenominations\":[{\"denominationId\":\"note\",\"quantity\":"
                      + quantity
                      + "}]}"));
    verifyNoInteractions(service);
  }

  @Test
  void rejectsInvalidDateAndMalformedJson() {
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> api.search("12/10/2026", 0, 25, null, null));
    assertThrows(GeneralPlatformDomainRuleException.class, () -> api.reverse("[]"));
    assertThrows(GeneralPlatformDomainRuleException.class, () -> api.reverse("invalid"));
    verifyNoInteractions(service);
  }

  @Test
  void authorizationNumberIsSeparateFromPrintablePhysicalRequest() {
    api.preview(
        "{\"historyId\":\"SAVINGS_DEPOSIT:1\",\"idempotencyKey\":\"key\",\"authorizationNumber\":\"sensitive\"}");
    var physical =
        org.mockito.ArgumentCaptor.forClass(
            org.apache.fineract.baseteller.data.TransactionReversalRequest.class);
    verify(service).preview(physical.capture(), eq("sensitive"));
    assertFalse(physical.getValue().toString().contains("sensitive"));
  }
}
