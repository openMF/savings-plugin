/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class RatePropagationApiResourceTest {
  @Test
  void conflictAndBlockedStartUseStructured409WithoutInventingAppliedEvidence() {
    for (RuntimeException failure :
        new RuntimeException[] {
          new ProductPolicyService.Conflict(),
          new RatePropagationService.NotReady(),
          new org.springframework.dao.CannotAcquireLockException("concurrent"),
          new org.springframework.dao.DuplicateKeyException("duplicate")
        }) {
      var r =
          RatePropagationApiResource.invoke(
              () -> {
                throw failure;
              });
      assertEquals(409, r.getStatus());
      var body = JsonParser.parseString((String) r.getEntity()).getAsJsonObject();
      assertEquals("409", body.get("httpStatusCode").getAsString());
      assertTrue(body.has("errors"));
    }
    var r =
        RatePropagationApiResource.invoke(
            () -> new RatePropagationData.Blocked("UNSUPPORTED", "Disabled", null));
    assertEquals(409, r.getStatus());
    assertTrue(((String) r.getEntity()).contains("UNSUPPORTED"));
  }

  @Test
  void securityValidationAndMissingResourceKeepNativeExceptionMapping() {
    for (RuntimeException e :
        new RuntimeException[] {
          new AccessDeniedException("Denied"),
          new RatePropagationService.NotFound(),
          ProductPolicyValidation.invalid("source", "Invalid")
        })
      assertSame(
          e,
          assertThrows(
              RuntimeException.class,
              () ->
                  RatePropagationApiResource.invoke(
                      () -> {
                        throw e;
                      })));
  }

  @Test
  void routesDelegateToServiceBoundaries() {
    var s = mock(RatePropagationService.class);
    var api = new RatePropagationApiResource(s);
    api.template();
    api.preview(1, "{}");
    api.create(1, "{}");
    api.get("id");
    api.discover("id", "{}", 10);
    api.submit("id", "{}");
    api.approve("id", "{}");
    api.reject("id", "{}");
    api.assess("id", "{}", 10);
    api.start("id", "{}");
    api.cancel("id", "{}");
    api.accounts("id", 0, 10);
    api.audit("id", 0, 10);
    api.attempts("id", 0, 10);
    verify(s).decide("id", true, "{}");
    verify(s).decide("id", false, "{}");
    verify(s).start("id", "{}");
    verify(s).discover("id", "{}", 10);
    verify(s).assess("id", "{}", 10);
    verify(s).accounts("id", 0, 10);
    verify(s).audit("id", 0, 10);
    verify(s).attempts("id", 0, 10);
  }
}
