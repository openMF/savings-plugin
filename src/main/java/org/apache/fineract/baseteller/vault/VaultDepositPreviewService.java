/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import static org.apache.fineract.baseteller.vault.VaultDepositPreviewValidation.invalid;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.CashAllocationDenominationData;
import org.apache.fineract.baseteller.service.CashAllocationCalculator;
import org.apache.fineract.infrastructure.core.exception.PlatformInternalServerException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VaultDepositPreviewService {
  private static final BigDecimal AMOUNT_LIMIT = new BigDecimal("10000000000000");
  private final PlatformSecurityContext security;
  private final VaultReadPlatformService vaults;
  private final VaultDepositPreviewValidation validation;

  public VaultDepositPreviewData preview(String json) {
    // Authentication/permission checks precede parsing or exposing configuration errors.
    security.authenticatedUser().validateHasPermissionTo(VaultReadPlatformService.PERMISSION);
    var request = validation.parse(json);
    String officeId = request.officeId() == null ? null : request.officeId().toString();
    var context = vaults.context(officeId);
    if (!request.businessDate().equals(context.businessDate().toString()))
      throw invalid(
          "businessDate",
          "business.date.invalid",
          "businessDate must equal the current Fineract business date.");
    List<VaultDepositPreviewData.Currency> currencies = new ArrayList<>();
    for (int i = 0; i < request.currencies().size(); i++) {
      var selected = request.currencies().get(i);
      String code = selected.currencyCode().toUpperCase(Locale.ROOT);
      String path = "currencies[" + i + "]";
      var currency =
          context.supportedCurrencies().stream()
              .filter(c -> c.code().equals(code))
              .findFirst()
              .orElseThrow(
                  () ->
                      invalid(
                          path + ".currencyCode",
                          "currency.unsupported",
                          "Currency is not enabled for the authorized vault."));
      var catalog = vaults.denominations(officeId, code).denominations();
      currencies.add(calculate(selected, currency, catalog, path));
    }
    currencies.sort(Comparator.comparing(VaultDepositPreviewData.Currency::currencyCode));
    return new VaultDepositPreviewData(
        VaultDepositPreviewData.OperationType.VAULT_CASH_DEPOSIT,
        VaultDepositPreviewData.ValidationStatus.VALID_PREVIEW,
        VaultDepositPreviewData.MovementStatus.PROPOSED,
        context.branch(),
        context.vault(),
        context.businessDate(),
        request.correlationReference(),
        List.copyOf(currencies),
        new VaultDepositPreviewData.ExecutionReadiness(
            false,
            VaultDepositPreviewData.ReadinessStatus.BLOCKED_BY_POLICY,
            VaultData.Status.UNAVAILABLE,
            VaultData.Status.UNAVAILABLE,
            List.of(
                new VaultDepositPreviewData.Blocker(
                    "SOURCE_POLICY_UNAVAILABLE",
                    "Approved source-of-funds and custody rules are not defined for vault"
                        + " deposits."),
                new VaultDepositPreviewData.Blocker(
                    "ACCOUNTING_POLICY_UNAVAILABLE",
                    "Approved deposit accounting mappings are not defined; existing opening"
                        + " mappings do not establish deposit policy."),
                new VaultDepositPreviewData.Blocker(
                    "VAULT_STATUS_UNKNOWN",
                    "The existing office-level vault has no independent activation status."),
                new VaultDepositPreviewData.Blocker(
                    "EXECUTION_NOT_IMPLEMENTED",
                    "Phase 3 financial posting and authorization are unavailable."))));
  }

  private static VaultDepositPreviewData.Currency calculate(
      VaultDepositPreviewRequest.Currency requested,
      VaultData.Currency currency,
      List<VaultData.Denomination> catalog,
      String path) {
    int places = currency.decimalPlaces();
    if (places < 0 || places > 6) throw configuration();
    Map<String, VaultData.Denomination> configured = new HashMap<>();
    for (var d : catalog) {
      if (!d.active()
          || d.type() == null
          || d.denominationId() == null
          || d.denominationId().isBlank()
          || !d.denominationId().equals(d.denominationId().trim())
          || !currency.code().equals(d.currencyCode())
          || d.value() == null
          || d.value().signum() <= 0
          || d.value().stripTrailingZeros().scale() > places
          || configured.put(d.denominationId().toLowerCase(Locale.ROOT), d) != null)
        throw configuration();
    }
    List<BaseTellerDenominationData> quantities = new ArrayList<>();
    for (int i = 0; i < requested.denominations().size(); i++) {
      var submitted = requested.denominations().get(i);
      var denomination = configured.get(submitted.denominationId().trim().toLowerCase(Locale.ROOT));
      if (denomination == null)
        throw invalid(
            path + ".denominations[" + i + "].denominationId",
            "denomination.unsupported",
            "Denomination is unknown, inactive or belongs to another currency.");
      quantities.add(
          new BaseTellerDenominationData(
              denomination.denominationId(), null, submitted.quantity()));
    }
    // Reuse the WEB-1221 calculator with backend catalog values; never pass a client value.
    var lines =
        CashAllocationCalculator.calculate(
            quantities,
            catalog.stream()
                .map(
                    d ->
                        new CashAllocationDenominationData(
                            d.denominationId(), d.type().name(), d.value(), 0L, BigDecimal.ZERO))
                .toList(),
            places);
    var notes = lines(lines, "BANKNOTE", places);
    var coins = lines(lines, "COIN", places);
    BigDecimal notesTotal = total(notes, places), coinsTotal = total(coins, places);
    BigDecimal cash =
        CashAllocationCalculator.total(lines).setScale(places, RoundingMode.UNNECESSARY);
    if (cash.signum() <= 0 || cash.compareTo(AMOUNT_LIMIT) >= 0)
      throw invalid(
          path + ".denominations",
          "amount.out.of.range",
          "Cash total must be positive and below the supported decimal(19,6) monetary limit.");
    var unsupported = new VaultDepositPreviewData.Instrument(false, null);
    return new VaultDepositPreviewData.Currency(
        currency.code(),
        places,
        notes,
        coins,
        notesTotal,
        coinsTotal,
        cash,
        new VaultDepositPreviewData.GeneralTransaction(
            "VAULT",
            new VaultDepositPreviewData.Column(cash, unsupported, unsupported),
            new VaultDepositPreviewData.Column(
                BigDecimal.ZERO.setScale(places), unsupported, unsupported)));
  }

  private static List<VaultDepositPreviewData.Line> lines(
      List<CashAllocationDenominationData> lines, String type, int places) {
    return lines.stream()
        .filter(d -> d.quantity() > 0 && type.equals(d.type()))
        .sorted(
            Comparator.comparing(CashAllocationDenominationData::value)
                .reversed()
                .thenComparing(CashAllocationDenominationData::identifier))
        .map(
            d ->
                new VaultDepositPreviewData.Line(
                    d.identifier(),
                    d.value().setScale(places, RoundingMode.UNNECESSARY),
                    d.quantity(),
                    d.subtotal().setScale(places, RoundingMode.UNNECESSARY)))
        .toList();
  }

  private static BigDecimal total(List<VaultDepositPreviewData.Line> lines, int places) {
    return lines.stream()
        .map(VaultDepositPreviewData.Line::subtotal)
        .reduce(BigDecimal.ZERO.setScale(places), BigDecimal::add);
  }

  private static PlatformInternalServerException configuration() {
    return new PlatformInternalServerException(
        "error.msg.base.teller.vault.preview.catalog.invalid",
        "Currency or denomination configuration is invalid for an exact cash preview.");
  }
}
