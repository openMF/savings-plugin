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
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** Resource routing and errors; real service authorization/persistence is tested separately. */
class InterestMatrixApiResourceTest {
  final InterestMatrixService service = mock(InterestMatrixService.class);
  final InterestMatrixApiResource api = new InterestMatrixApiResource(service);

  @Test
  void draftAndMutationsDelegateToProtectedAggregate() {
    var config =
        new InterestMatrixData.Configuration(
            1, 2L, "DRAFT", false, null, InterestMatrixData.blockers());
    when(service.draft(1)).thenReturn(config);
    when(service.save(1, "body")).thenReturn(config);
    when(service.validate(1, "body")).thenReturn(config);
    assertSame(config, api.draft(1));
    assertSame(config, api.save(1, "body").getEntity());
    assertEquals(200, api.validate(1, "body").getStatus());
    verify(service).save(1, "body");
    verify(service).validate(1, "body");
  }

  @Test
  void revisionAndDatabaseConcurrencyErrorsReturn409() {
    when(service.save(1, "body")).thenThrow(new ProductPolicyService.Conflict());
    when(service.validate(1, "body"))
        .thenThrow(new org.springframework.dao.CannotAcquireLockException("busy"));
    assertEquals(409, api.save(1, "body").getStatus());
    assertEquals(409, api.validate(1, "body").getStatus());
  }

  @Test
  void deniedAndValidationErrorsRemainPlatformErrors() {
    when(service.save(1, "body")).thenThrow(new AccessDeniedException("Denied"));
    when(service.validate(1, "body"))
        .thenThrow(ProductPolicyValidation.invalid("matrix", "invalid"));
    assertThrows(AccessDeniedException.class, () -> api.save(1, "body"));
    assertThrows(PlatformApiDataValidationException.class, () -> api.validate(1, "body"));
  }

  @Test
  void selectionUsesExactDecimalAndExplicitDate() {
    api.select(1, "1000.01", "2026-11-03");
    verify(service)
        .selectForReview(1, new java.math.BigDecimal("1000.01"), LocalDate.of(2026, 11, 3));
    api.select(1, "0", null);
    verify(service).selectForReview(1, java.math.BigDecimal.ZERO, null);
  }

  @Test
  void invalidQueryValuesNeverReachService() {
    for (String balance : java.util.List.of("1e2", "-1", "NaN", "0.0000001", "10000000000000"))
      assertThrows(PlatformApiDataValidationException.class, () -> api.select(1, balance, null));
    assertThrows(PlatformApiDataValidationException.class, () -> api.select(1, null, null));
    assertThrows(PlatformApiDataValidationException.class, () -> api.select(1, "1", "2026-02-30"));
    verifyNoInteractions(service);
  }
}
