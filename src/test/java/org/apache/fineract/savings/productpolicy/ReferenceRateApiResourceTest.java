/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Resource delegation and conflict/error contracts; genuine deployed HTTP remains a runtime gate.
 */
class ReferenceRateApiResourceTest {
  @Test
  void lifecycleDelegatesToMandatoryServiceBoundaries() {
    var service = mock(ReferenceRateService.class);
    var api = new ReferenceRateApiResource(service);
    assertEquals(200, api.define("{}").getStatus());
    assertEquals(200, api.capture("CETES_TEST", "{}").getStatus());
    assertEquals(200, api.submit("CETES_TEST", 1, "{}").getStatus());
    assertEquals(200, api.approve("CETES_TEST", 1, "{}").getStatus());
    assertEquals(200, api.reject("CETES_TEST", 1, "{}").getStatus());
    api.template();
    api.definitions();
    api.definition("CETES_TEST");
    api.pending();
    api.history("CETES_TEST");
    api.fixing("CETES_TEST", 1);
    api.audit("CETES_TEST");
    api.effective("CETES_TEST", "TEST_TENOR", "2026-11-01");
    verify(service).define("{}");
    verify(service).capture("CETES_TEST", "{}");
    verify(service).submit("CETES_TEST", 1, "{}");
    verify(service).decide("CETES_TEST", 1, true, "{}");
    verify(service).decide("CETES_TEST", 1, false, "{}");
    verify(service).resolve("CETES_TEST", "TEST_TENOR", LocalDate.of(2026, 11, 1));
  }

  @Test
  void revisionDuplicatesAndDatabaseConcurrencyReturnStructured409() {
    for (RuntimeException failure :
        new RuntimeException[] {
          new ProductPolicyService.Conflict(),
          new DuplicateKeyException("duplicate"),
          new CannotAcquireLockException("concurrent")
        }) {
      var response =
          ReferenceRateApiResource.invoke(
              () -> {
                throw failure;
              });
      assertEquals(409, response.getStatus());
      var body = (Map<?, ?>) response.getEntity();
      assertEquals("409", body.get("httpStatusCode"));
      assertEquals(
          "error.msg.savings.reference.rate.conflict", body.get("userMessageGlobalisationCode"));
      assertTrue(body.containsKey("errors"));
    }
  }

  @Test
  void authenticationAndValidationKeepFineractExceptionMapping() {
    assertThrows(
        AccessDeniedException.class,
        () ->
            ReferenceRateApiResource.invoke(
                () -> {
                  throw new AccessDeniedException("Denied");
                }));
    var invalid = ProductPolicyValidation.invalid("tenor", "Unsupported tenor");
    assertSame(
        invalid,
        assertThrows(
            RuntimeException.class,
            () ->
                ReferenceRateApiResource.invoke(
                    () -> {
                      throw invalid;
                    })));
    assertThrows(
        ReferenceRateService.NotFound.class,
        () ->
            ReferenceRateApiResource.invoke(
                () -> {
                  throw new ReferenceRateService.NotFound();
                }));
  }

  @Test
  void formulaResourceParsesOnlyIsoPreviewDatesAndUsesDraftService() {
    var service = mock(ReferenceRateFormulaService.class);
    var api = new ReferenceRateFormulaApiResource(service);
    api.draft(1);
    assertEquals(200, api.save(1, "{}").getStatus());
    api.preview(1, "2026-11-01");
    api.preview(1, null);
    verify(service).draft(1);
    verify(service).save(1, "{}");
    verify(service).preview(1, LocalDate.of(2026, 11, 1));
    verify(service).preview(1, null);
    assertThrows(RuntimeException.class, () -> api.preview(1, "2026-02-30"));
    assertThrows(RuntimeException.class, () -> api.preview(1, "2026-1-01"));
  }
}
