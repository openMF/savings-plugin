package org.apache.fineract.baseteller.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.apache.fineract.baseteller.data.CashDrawerPickupRequest;
import org.apache.fineract.baseteller.service.CashDrawerPickupPlatformService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CashDrawerPickupsApiResourceTest {
  @Test
  void previewRejectsFrontendTotalsCredentialsAndFractionalQuantities() {
    var service = mock(CashDrawerPickupPlatformService.class);
    var resource = new CashDrawerPickupsApiResource(service);
    String base = """
        {"cashierId":1,"destinationCashierId":2,"currencies":[
        {"currencyCode":"AAA","cashDenominations":[{"denominationId":"bill","quantity":1}],"checks":[]}]}
        """;
    assertThrows(RuntimeException.class, () -> resource.preview(base.replace("\"cashierId\"", "\"total\":100,\"cashierId\"")));
    assertThrows(RuntimeException.class, () -> resource.preview(base.replace("\"cashierId\"", "\"authorizationCredential\":\"secret\",\"cashierId\"")));
    assertThrows(RuntimeException.class, () -> resource.preview(base.replace("\"quantity\":1", "\"quantity\":1.5")));
    org.mockito.Mockito.verifyNoInteractions(service);
  }

  @Test
  void credentialIsPassedSeparatelyFromPersistableRequest() {
    var service = mock(CashDrawerPickupPlatformService.class);
    var resource = new CashDrawerPickupsApiResource(service);
    resource.create("""
        {"cashierId":1,"destinationCashierId":2,"idempotencyKey":"key","currencies":[],
         "authorizationUsername":"head","authorizationCredential":"test-only-secret"}
        """);
    var request = ArgumentCaptor.forClass(CashDrawerPickupRequest.class);
    verify(service).create(request.capture(), eq("head"), eq("test-only-secret"));
    assertFalse(request.getValue().toString().contains("test-only-secret"));
    assertFalse(new com.google.gson.Gson().toJson(request.getValue()).contains("test-only-secret"));
  }

  @Test
  void malformedRequestDoesNotEchoCredential() {
    var resource = new CashDrawerPickupsApiResource(mock(CashDrawerPickupPlatformService.class));
    String secret = "test-only-secret";
    var error = assertThrows(RuntimeException.class, () -> resource.create(
        "{\"authorizationCredential\":\"" + secret + "\",\"" + secret + "\":true}"));
    assertEquals("Invalid pickup request.", error.getMessage());
    assertFalse(error.toString().contains(secret));
  }
}
