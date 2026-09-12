/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.prospect.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.function.Consumer;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.prospect.data.ProspectRegistrationStatus;
import org.apache.fineract.prospect.data.ProspectSearchCriteria;
import org.apache.fineract.prospect.data.ProspectSearchRequest;
import org.junit.jupiter.api.Test;

class ProspectSearchValidatorTest {

  private final ProspectSearchValidator validator = new ProspectSearchValidator();

  @Test
  void defaultsPaginationAndDeterministicSort() {
    final ProspectSearchCriteria criteria = validator.validate(new ProspectSearchRequest());

    assertEquals(0, criteria.offset());
    assertEquals(50, criteria.limit());
    assertEquals("lastUpdatedAt", criteria.orderBy());
    assertEquals("DESC", criteria.sortOrder());
    assertNull(criteria.createdFrom());
  }

  @Test
  void normalizesSupportedFilters() {
    final ProspectSearchRequest request = new ProspectSearchRequest();
    request.setQ(" Alice ");
    request.setOfficeId(2L);
    request.setRegistrationStatus(" in_progress ");
    request.setCreatedFrom("2026-09-01");
    request.setCreatedTo("2026-09-03");
    request.setClientId(11L);
    request.setLastCompletedStageCode(" kyc_level_1 ");
    request.setOffset(10);
    request.setLimit(20);
    request.setOrderBy("displayName");
    request.setSortOrder("asc");

    final ProspectSearchCriteria criteria = validator.validate(request);

    assertEquals("Alice", criteria.q());
    assertEquals("IN_PROGRESS", criteria.registrationStatus());
    assertEquals(LocalDate.parse("2026-09-01"), criteria.createdFrom());
    assertEquals(LocalDate.parse("2026-09-03"), criteria.createdTo());
    assertEquals("KYC_LEVEL_1", criteria.lastCompletedStageCode());
    assertEquals("ASC", criteria.sortOrder());
  }

  @Test
  void exposesCentralSupportedRegistrationStatusVocabulary() {
    assertEquals(6, ProspectRegistrationStatus.values().size());
    assertEquals(true, ProspectRegistrationStatus.isSupported("PENDING"));
    assertEquals(false, ProspectRegistrationStatus.isSupported("CLIENT_PENDING"));
  }

  @Test
  void rejectsInvalidPaginationDatesStatusIdsAndSorting() {
    assertInvalid(request -> request.setOffset(-1));
    assertInvalid(request -> request.setLimit(0));
    assertInvalid(request -> request.setLimit(201));
    assertInvalid(request -> request.setOfficeId(0L));
    assertInvalid(request -> request.setClientId(-1L));
    assertInvalid(request -> request.setRegistrationStatus("MAYBE"));
    assertInvalid(request -> request.setCreatedFrom("09/01/2026"));
    assertInvalid(
        request -> {
          request.setCreatedFrom("2026-09-05");
          request.setCreatedTo("2026-09-01");
        });
    assertInvalid(request -> request.setOrderBy("dropTable"));
    assertInvalid(request -> request.setSortOrder("sideways"));
  }

  private void assertInvalid(final Consumer<ProspectSearchRequest> mutation) {
    final ProspectSearchRequest request = new ProspectSearchRequest();
    mutation.accept(request);
    assertThrows(PlatformApiDataValidationException.class, () -> validator.validate(request));
  }
}
