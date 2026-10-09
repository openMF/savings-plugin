package org.apache.fineract.baseteller.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.fineract.baseteller.service.CashExchangePlatformService;
import org.junit.jupiter.api.Test;

class CashExchangesApiResourceTest {
  private static final String BODY =
      """
      {"cashierId":1,"currencyCode":"EUR","receivedDenominations":[{"denominationId":"large","quantity":2}],"deliveredDenominations":[{"denominationId":"small","quantity":10}],"idempotencyKey":"unit-key"}
      """;

  @Test
  void requestUsesCatalogIdentifiersAndLongCounts() {
    var r = CashExchangesApiResource.parse(BODY);
    assertEquals(2L, r.receivedDenominations().get(0).quantity());
  }

  @Test
  void rejectsFractionalQuantity() {
    assertThrows(
        RuntimeException.class,
        () -> CashExchangesApiResource.parse(BODY.replace("\"quantity\":2", "\"quantity\":2.5")));
  }

  @Test
  void rejectsOverflowingQuantity() {
    assertThrows(
        RuntimeException.class,
        () ->
            CashExchangesApiResource.parse(
                BODY.replace("\"quantity\":2", "\"quantity\":9223372036854775808")));
  }

  @Test
  void rejectsQuantityStrings() {
    assertThrows(
        RuntimeException.class,
        () -> CashExchangesApiResource.parse(BODY.replace("\"quantity\":2", "\"quantity\":\"2\"")));
  }

  @Test
  void rejectsAuthoritativeAmountsFromCaller() {
    assertThrows(
        RuntimeException.class,
        () ->
            CashExchangesApiResource.parse(
                BODY.replace("\"quantity\":2", "\"quantity\":2,\"value\":500")));
  }

  @Test
  void rejectsUnknownTopLevelFields() {
    assertThrows(
        RuntimeException.class,
        () ->
            CashExchangesApiResource.parse(
                BODY.replace("\"cashierId\":1", "\"cashierId\":1,\"receivedAmount\":1000")));
  }

  @Test
  void rejectsMalformedBody() {
    assertThrows(RuntimeException.class, () -> CashExchangesApiResource.parse("[]"));
  }

  @Test
  void allEndpointsDelegateToSecuredService() {
    var service = mock(CashExchangePlatformService.class);
    var api = new CashExchangesApiResource(service);
    api.context();
    api.denominations(1L, "EUR");
    api.preview(BODY);
    api.create(BODY);
    api.retrieve(1L);
    api.receipt(1L);
    verify(service).context();
    verify(service).denominations(1L, "EUR");
    verify(service).preview(any());
    verify(service).create(any());
    verify(service).retrieve(1L);
    verify(service).receipt(1L);
  }
}
