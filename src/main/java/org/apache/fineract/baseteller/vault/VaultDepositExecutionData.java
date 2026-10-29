/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import java.util.List;

/**
 * Durable blocked attempt evidence. attemptReference is explicitly NOT a financial transaction ID.
 */
public record VaultDepositExecutionData(
    String attemptReference,
    Status status,
    String transactionId,
    VaultDepositPreviewData.OperationType operationType,
    VaultData.Branch branch,
    VaultData.Vault vault,
    String businessDate,
    VaultDepositExecutionRequest.Source proposedSource,
    Long initiatedBy,
    String createdAt,
    String correlationReference,
    List<VaultDepositPreviewData.Currency> currencies,
    AccountingStatus accountingStatus,
    boolean executionAllowed,
    List<VaultDepositPreviewData.Blocker> blockers) {
  public enum Status {
    BLOCKED
  }

  public enum AccountingStatus {
    NOT_POSTED
  }
}
