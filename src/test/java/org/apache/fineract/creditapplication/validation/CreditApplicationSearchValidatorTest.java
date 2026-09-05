/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Consumer;
import org.apache.fineract.creditapplication.data.CreditApplicationSearchCriteria;
import org.apache.fineract.creditapplication.data.CreditApplicationSearchRequest;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;

class CreditApplicationSearchValidatorTest {

  private final CreditApplicationSearchValidator validator = new CreditApplicationSearchValidator();

  @Test
  void defaultsPaginationAndDeterministicSort() {
    final CreditApplicationSearchCriteria criteria =
        validator.validate(new CreditApplicationSearchRequest());

    assertEquals(0, criteria.offset());
    assertEquals(50, criteria.limit());
    assertEquals("submittedOnDate", criteria.orderBy());
    assertEquals("DESC", criteria.sortOrder());
    assertNull(criteria.submittedFrom());
  }

  @Test
  void normalizesAllSupportedFilters() {
    final CreditApplicationSearchRequest request = new CreditApplicationSearchRequest();
    request.setClientId(1L);
    request.setOfficeId(2L);
    request.setClientTypeId(3L);
    request.setProductId(4L);
    request.setStatus(100);
    request.setSubmittedFrom("2026-09-01");
    request.setSubmittedTo("2026-09-05");
    request.setMinAmount(new BigDecimal("10.00"));
    request.setMaxAmount(new BigDecimal("20.00"));
    request.setCurrencyCode(" mxn ");
    request.setStateProvinceId(5L);
    request.setMunicipality(" Central ");
    request.setOffset(20);
    request.setLimit(10);
    request.setOrderBy("amount");
    request.setSortOrder("asc");

    final CreditApplicationSearchCriteria criteria = validator.validate(request);

    assertEquals(LocalDate.parse("2026-09-01"), criteria.submittedFrom());
    assertEquals(LocalDate.parse("2026-09-05"), criteria.submittedTo());
    assertEquals("MXN", criteria.currencyCode());
    assertEquals("Central", criteria.municipality());
    assertEquals("amount", criteria.orderBy());
    assertEquals("ASC", criteria.sortOrder());
  }

  @Test
  void rejectsInvalidPaginationSortingStatusIdsAndRanges() {
    assertInvalid(request -> request.setOffset(-1));
    assertInvalid(request -> request.setLimit(0));
    assertInvalid(request -> request.setLimit(201));
    assertInvalid(request -> request.setOrderBy("dropTable"));
    assertInvalid(request -> request.setSortOrder("sideways"));
    assertInvalid(request -> request.setStatus(999));
    assertInvalid(request -> request.setClientId(0L));
    assertInvalid(request -> request.setOfficeId(-1L));
    assertInvalid(request -> request.setSubmittedFrom("09/01/2026"));
    assertInvalid(
        request -> {
          request.setSubmittedFrom("2026-09-05");
          request.setSubmittedTo("2026-09-01");
        });
    assertInvalid(request -> request.setMinAmount(new BigDecimal("-0.01")));
    assertInvalid(request -> request.setMaxAmount(new BigDecimal("-0.01")));
    assertInvalid(
        request -> {
          request.setMinAmount(BigDecimal.TEN);
          request.setMaxAmount(BigDecimal.ONE);
          request.setCurrencyCode("USD");
        });
  }

  @Test
  void requiresValidCurrencyForAmountFilters() {
    assertInvalid(request -> request.setMinAmount(BigDecimal.ZERO));
    assertInvalid(request -> request.setCurrencyCode("US"));
    assertInvalid(request -> request.setCurrencyCode("U1D"));
  }

  private void assertInvalid(final Consumer<CreditApplicationSearchRequest> mutation) {
    final CreditApplicationSearchRequest request = new CreditApplicationSearchRequest();
    mutation.accept(request);
    assertThrows(PlatformApiDataValidationException.class, () -> validator.validate(request));
  }
}
