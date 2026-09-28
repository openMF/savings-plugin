/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;

class CatalogUpdateCategoryTest {

  @Test
  void parsesCategoryCaseInsensitively() {
    assertEquals(CatalogUpdateCategory.GENERAL, CatalogUpdateCategory.parse("general"));
    assertEquals(CatalogUpdateCategory.ACCOUNTING, CatalogUpdateCategory.parse(" ACCOUNTING "));
    assertEquals(CatalogUpdateCategory.USERS, CatalogUpdateCategory.parse("USERS"));
  }

  @Test
  void rejectsInvalidCategory() {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> CatalogUpdateCategory.parse("not-a-category"));
  }
}
