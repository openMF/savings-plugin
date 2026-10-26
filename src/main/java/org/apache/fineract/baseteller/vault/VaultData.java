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
import org.apache.fineract.baseteller.data.CashInventoryData;

/** Read-only WEB-1328 contracts. Catalog values never describe physical cash availability. */
public final class VaultData {
  private VaultData() {}

  public enum Status {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN
  }

  public enum DenominationType {
    BANKNOTE,
    COIN
  }

  public record Branch(Long id, String name) {}

  /** The existing office-level inventory custodian; there is no separate vault status model. */
  public record Vault(String key, Long officeId, String name, Status status) {}

  public record Capabilities(
      boolean viewContext,
      boolean viewCurrencies,
      boolean viewDenominations,
      boolean viewInventory,
      boolean previewDeposit,
      boolean executeDeposit) {}

  public record Readiness(boolean depositReady, List<String> limitations) {}

  public record Currency(String code, String name, int decimalPlaces) {}

  public record Context(
      Branch branch,
      Vault vault,
      LocalDate businessDate,
      List<Currency> supportedCurrencies,
      Capabilities capabilities,
      Readiness readiness) {}

  public record Currencies(String vaultKey, List<Currency> currencies, Status status) {}

  public record Denomination(
      Long id,
      String denominationId,
      String currencyCode,
      BigDecimal value,
      DenominationType type,
      boolean active) {}

  public record Denominations(
      String vaultKey, String currencyCode, List<Denomination> denominations, Status status) {}

  /** Empty balances mean unavailable, never an inferred zero or a denomination quantity. */
  public record Inventory(
      String vaultKey, String currencyCode, Status status, List<CashInventoryData> balances) {}
}
