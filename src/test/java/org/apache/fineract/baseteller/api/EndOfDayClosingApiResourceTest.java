package org.apache.fineract.baseteller.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.fineract.baseteller.service.EndOfDayClosingPlatformService;
import org.junit.jupiter.api.Test;

class EndOfDayClosingApiResourceTest {
  private final EndOfDayClosingPlatformService service = mock(EndOfDayClosingPlatformService.class);
  private final EndOfDayClosingApiResource api = new EndOfDayClosingApiResource(service);
  private final String body =
      """
      {"cashierId":1,"businessDate":"2026-01-01","idempotencyKey":"key","currencies":[
      {"currencyCode":"AAA","denominations":[{"denominationId":"bill","quantity":1}],"vouchers":0,"checks":[]}]}
      """;

  @Test
  void credentialIsSeparatedFromDeclaration() {
    String complete =
        body.strip()
            .replaceFirst(
                "\\{",
                "{\"authorization\":{\"username\":\"primary\",\"credential\":\"test-only-secret\"},");
    api.complete(complete);
    var request =
        org.mockito.ArgumentCaptor.forClass(
            org.apache.fineract.baseteller.data.EndOfDayClosingRequest.class);
    verify(service).complete(request.capture(), eq("primary"), eq("test-only-secret"));
    assertFalse(request.getValue().toString().contains("test-only-secret"));
    assertFalse(new com.google.gson.Gson().toJson(request.getValue()).contains("credential"));
  }

  @Test
  void malformedAuthorizationDoesNotLeakCredentialInException() {
    String malformed =
        body.strip()
            .replaceFirst("\\{", "{\"authorization\":{\"credential\":\"test-only-secret\"},");
    var error = assertThrows(RuntimeException.class, () -> api.complete(malformed));
    assertFalse(error.toString().contains("test-only-secret"));
    assertNull(error.getCause());
    verifyNoInteractions(service);
  }

  @Test
  void fractionalAndOverflowQuantityCannotBeTruncatedByJsonParser() {
    for (String q : new String[] {"0.5", "9223372036854775808", "\"1\""})
      assertThrows(
          RuntimeException.class,
          () -> api.preview(body.replace("\"quantity\":1", "\"quantity\":" + q)));
    verifyNoInteractions(service);
  }

  @Test
  void frontendTotalsAndPreviewCredentialsAreRejected() {
    assertThrows(
        RuntimeException.class,
        () -> api.preview(body.strip().replaceFirst("\\{", "{\"expectedTotal\":0,")));
    assertThrows(
        RuntimeException.class,
        () -> api.preview(body.strip().replaceFirst("\\{", "{\"authorization\":{},")));
    verifyNoInteractions(service);
  }

  @Test
  void previewUsesDeclarationOnlyAndLookupRoutesDelegate() {
    api.preview(body);
    verify(service).preview(any());
    api.context();
    verify(service).context();
    api.checks(1L, "AAA");
    verify(service).checks(1L, "AAA");
    api.retrieve(2L);
    verify(service).retrieve(2L, false);
    api.report(2L);
    verify(service).retrieve(2L, true);
  }
}
