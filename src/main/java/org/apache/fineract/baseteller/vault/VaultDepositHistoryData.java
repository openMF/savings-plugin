/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import java.math.BigDecimal;
import java.util.List;

/** Read models for persisted attempts. No attempt is a completed financial transaction. */
public final class VaultDepositHistoryData {
  private VaultDepositHistoryData() {}

  public record Page(List<Item> items, long totalFilteredRecords, int offset, int limit) {}

  public record CurrencyAmount(String currencyCode, int decimalPlaces, BigDecimal proposedCash) {}

  public record ReceiptEligibility(boolean eligible, String reason) {}

  public record Item(
      String attemptReference,
      String recordKind,
      VaultDepositExecutionData.Status status,
      VaultDepositPreviewData.OperationType operationType,
      VaultData.Branch branch,
      VaultData.Vault vault,
      String businessDate,
      String createdAt,
      List<CurrencyAmount> currencies,
      ReceiptEligibility receiptEligibility,
      List<String> permittedActions) {}

  /**
   * Original names, quantities and amounts come exclusively from the immutable Phase 3 snapshot.
   */
  public record Detail(
      String recordKind,
      VaultDepositExecutionData attempt,
      ReceiptEligibility receiptEligibility,
      List<String> permittedActions) {}

  public record Audit(String attemptReference, List<Event> events) {}

  /** The attempt row itself is the only persisted audit evidence in Phase 3. */
  public record Event(
      String eventId,
      String eventType,
      Long actorId,
      String timestamp,
      String previousState,
      VaultDepositExecutionData.Status newState) {}
}
