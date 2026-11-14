/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.UUID;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;

class AnpaValidationTest {
  final LocalDate day = LocalDate.of(2026, 11, 14);

  @Test
  void datesAreStrictAndBounded() {
    assertEquals(day, AnpaSupport.date(null, day));
    for (String value :
        new String[] {
          "2026-2-1", "2026-02-30", "tomorrow", "2026-11-15", "1899-12-31", "+2026-11-14"
        })
      assertThrows(PlatformApiDataValidationException.class, () -> AnpaSupport.date(value, day));
  }

  @Test
  void paginationIsBounded() {
    AnpaSupport.page(0, 200);
    assertThrows(PlatformApiDataValidationException.class, () -> AnpaSupport.page(-1, 100));
    assertThrows(PlatformApiDataValidationException.class, () -> AnpaSupport.page(0, 201));
  }

  @Test
  void rejectsFinancialOverridesUnknownFieldsAndTypes() {
    String key = UUID.randomUUID().toString();
    for (String field : new String[] {"accountId", "balance", "tenant", "loanId", "actorId", "npa"})
      assertThrows(
          PlatformApiDataValidationException.class,
          () -> AnpaSupport.request("{\"idempotencyKey\":\"" + key + "\",\"" + field + "\":1}"));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> AnpaSupport.request("{\"idempotencyKey\":1}"));
  }

  @Test
  void idempotencyAndSupersessionAreValidated() {
    assertThrows(PlatformApiDataValidationException.class, () -> AnpaSupport.request("{}"));
    String key = UUID.randomUUID().toString();
    assertNotNull(AnpaSupport.request("{\"idempotencyKey\":\"" + key + "\"}"));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            AnpaSupport.request(
                "{\"idempotencyKey\":\"" + key + "\",\"supersedesAssessmentId\":\"" + key + "\"}"));
  }
}
