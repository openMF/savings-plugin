/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

/** A proposed source is input for validation, never authorization to move cash. */
public record VaultDepositExecutionRequest(
    VaultDepositPreviewRequest deposit, String vaultKey, String idempotencyKey, Source source) {
  public enum SourceType {
    INTERNAL_CUSTODY_TRANSFER,
    EXTERNAL_CASH_RECEIPT
  }

  public record Source(SourceType type, String reference) {}
}
