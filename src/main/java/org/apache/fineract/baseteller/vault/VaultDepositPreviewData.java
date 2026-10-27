/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Proposed movements only. No transaction identity, confirmation token, receipt or conversion. */
public record VaultDepositPreviewData(
    OperationType operationType,
    ValidationStatus validationStatus,
    MovementStatus movementStatus,
    VaultData.Branch branch,
    VaultData.Vault vault,
    LocalDate businessDate,
    String correlationReference,
    List<Currency> currencies,
    ExecutionReadiness executionReadiness) {
  public enum OperationType {
    VAULT_CASH_DEPOSIT
  }

  public enum ValidationStatus {
    VALID_PREVIEW
  }

  public enum MovementStatus {
    PROPOSED
  }

  public enum ReadinessStatus {
    BLOCKED_BY_POLICY
  }

  public record Line(String denominationId, BigDecimal value, long quantity, BigDecimal subtotal) {}

  /** Unsupported instruments have null amounts, not fabricated zero-valued movements. */
  public record Instrument(boolean supported, BigDecimal amount) {}

  public record Column(BigDecimal cash, Instrument checks, Instrument vouchers) {}

  public record GeneralTransaction(String perspective, Column inflows, Column outflows) {}

  public record Currency(
      String currencyCode,
      int decimalPlaces,
      List<Line> banknotes,
      List<Line> coins,
      BigDecimal banknotesTotal,
      BigDecimal coinsTotal,
      BigDecimal totalCash,
      GeneralTransaction generalTransaction) {}

  public record Blocker(String code, String message) {}

  public record ExecutionReadiness(
      boolean executionAllowed,
      ReadinessStatus status,
      VaultData.Status sourcePolicy,
      VaultData.Status accountingPolicy,
      List<Blocker> blockers) {}
}
