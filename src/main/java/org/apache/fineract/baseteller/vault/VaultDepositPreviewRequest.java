/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Quantities only: catalog values, the operator and execution permissions are server-owned. */
public record VaultDepositPreviewRequest(
    @Positive Long officeId,
    @NotNull @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String businessDate,
    @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Currency> currencies,
    @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String correlationReference) {
  public record Currency(
      @NotNull @Pattern(regexp = "[A-Za-z]{3}") String currencyCode,
      @NotNull @Size(min = 1, max = 1000) List<@NotNull @Valid Quantity> denominations) {}

  public record Quantity(
      @NotBlank @Size(max = 100) String denominationId,
      @NotNull @PositiveOrZero @Max(1_000_000_000L) Long quantity) {}
}
