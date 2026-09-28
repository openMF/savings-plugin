/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.data;

import java.util.List;
import java.util.Locale;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;

public enum CatalogUpdateCategory {
  GENERAL,
  ACCOUNTING,
  USERS;

  public static CatalogUpdateCategory parse(final String value) {
    try {
      return valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException exception) {
      throw new PlatformApiDataValidationException(
          List.of(
              ApiParameterError.parameterError(
                  "validation.msg.baseTeller.catalogUpdate.category.invalid",
                  "Category must be one of GENERAL, ACCOUNTING, or USERS.",
                  "category",
                  value == null ? "" : value)),
          exception);
    }
  }
}
