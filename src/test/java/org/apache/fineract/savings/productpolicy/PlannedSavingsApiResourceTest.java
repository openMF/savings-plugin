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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;

/** HTTP response contracts only; mocks are not evidence of native financial correctness. */
class PlannedSavingsApiResourceTest {
  @Test
  void blockedReviewsCannotBeRenderedAsSuccessfulFinancialCommands() {
    var service = mock(PlannedSavingsService.class);
    var resource = new PlannedSavingsApiResource(service, mock(SavingsPocketService.class));
    var review =
        new PlannedSavingsData.Review(
            PlannedSavingsIntegrationTest.REVIEW,
            PlannedSavingsIntegrationTest.ID,
            1,
            PlannedSavingsData.ReviewKind.MATURITY,
            LocalDate.of(2026, 11, 1),
            "BLOCKED_PENDING_POLICY",
            false,
            null,
            PlannedSavingsData.BLOCKERS);
    when(service.review(1, PlannedSavingsIntegrationTest.ID, "{}")).thenReturn(review);
    try (var response = resource.review(1, PlannedSavingsIntegrationTest.ID, "{}")) {
      assertEquals(422, response.getStatus());
      var body =
          com.google.gson.JsonParser.parseString((String) response.getEntity()).getAsJsonObject();
      assertFalse(body.get("executionEnabled").getAsBoolean());
      assertTrue(body.get("nativeTransactionId").isJsonNull());
    }
  }

  @Test
  void revisionUniquenessAndLockConflictsHaveExplicitNonFinancialResponse() {
    var service = mock(PlannedSavingsService.class);
    var resource = new PlannedSavingsApiResource(service, mock(SavingsPocketService.class));
    for (var error :
        List.of(
            new PlannedSavingsService.Conflict(),
            new CannotAcquireLockException("race"),
            new DuplicateKeyException("duplicate"))) {
      when(service.save(1, PlannedSavingsIntegrationTest.ID, "{}")).thenThrow(error);
      try (var response = resource.save(1, PlannedSavingsIntegrationTest.ID, "{}")) {
        assertEquals(409, response.getStatus());
      }
      reset(service);
    }
  }
}
