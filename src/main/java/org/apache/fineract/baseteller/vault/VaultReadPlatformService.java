/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.CashInventoryContextData;
import org.apache.fineract.baseteller.data.CashInventoryCustodianData;
import org.apache.fineract.baseteller.data.CashInventoryCustodianType;
import org.apache.fineract.baseteller.service.CashInventoryReadPlatformService;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformInternalServerException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VaultReadPlatformService {
  public static final String PERMISSION = "READ_BASE_TELLER_CASH_INVENTORY";

  private final PlatformSecurityContext security;
  private final NamedParameterJdbcTemplate jdbc;
  private final CashInventoryReadPlatformService inventory;

  public VaultData.Context context(String officeId) {
    return configured(
        () -> {
          Selection selected = select(officeId);
          List<VaultData.Currency> currencies = currencies(selected.scope());
          var limitations = new ArrayList<String>();
          limitations.add("VAULT_STATUS_UNKNOWN");
          limitations.add("DEPOSIT_EXECUTION_NOT_IMPLEMENTED");
          if (currencies.isEmpty()) limitations.add("CURRENCIES_UNAVAILABLE");
          for (var currency : currencies) {
            if (catalog(currency.code()).isEmpty())
              limitations.add("DENOMINATIONS_UNAVAILABLE:" + currency.code());
          }
          return new VaultData.Context(
              selected.branch(),
              new VaultData.Vault(
                  selected.vault().key(),
                  selected.branch().id(),
                  selected.vault().name(),
                  VaultData.Status.UNKNOWN),
              selected.date(),
              currencies,
              new VaultData.Capabilities(true, true, true, true, true, false),
              new VaultData.Readiness(false, List.copyOf(limitations)));
        });
  }

  public VaultData.Currencies currencies(String officeId) {
    return configured(
        () -> {
          Selection selected = select(officeId);
          var currencies = currencies(selected.scope());
          return new VaultData.Currencies(
              selected.vault().key(), currencies, availability(currencies));
        });
  }

  public VaultData.Denominations denominations(String officeId, String currencyCode) {
    return configured(
        () -> {
          Selection selected = select(officeId);
          String currency = currency(currencyCode, selected.scope());
          var denominations = catalog(currency);
          return new VaultData.Denominations(
              selected.vault().key(), currency, denominations, availability(denominations));
        });
  }

  public VaultData.Inventory inventory(String officeId, String currencyCode) {
    return configured(
        () -> {
          Selection selected = select(officeId);
          String currency = currency(currencyCode, selected.scope());
          var balances = inventory.inventory(selected.vault().key(), "CASH", currency, false);
          return new VaultData.Inventory(
              selected.vault().key(), currency, availability(balances), List.copyOf(balances));
        });
  }

  private Selection select(String requestedOffice) {
    AppUser user = security.authenticatedUser();
    user.validateHasPermissionTo(PERMISSION);
    // Never permit the routing datasource to fall back to a default tenant.
    if (ThreadLocalContextUtil.getTenant() == null)
      throw configuration("tenant.unavailable", "Authenticated tenant context is unavailable.");
    if (user.getOffice() == null || user.getOffice().getHierarchy() == null)
      throw new AccessDeniedException("An authenticated office is required.");
    Long officeId = officeId(requestedOffice, user.getOffice().getId());
    LocalDate date;
    try {
      date = DateUtils.getBusinessLocalDate();
    } catch (IllegalArgumentException e) {
      throw configuration("business.date.unavailable", "Fineract business date is unavailable.");
    }
    if (date == null)
      throw configuration("business.date.unavailable", "Fineract business date is unavailable.");

    var branches =
        jdbc.query(
            "SELECT id,name,hierarchy FROM m_office WHERE id=:id",
            Map.of("id", officeId),
            (rs, row) ->
                new Office(rs.getLong("id"), rs.getString("name"), rs.getString("hierarchy")));
    if (branches.isEmpty()) throw new VaultNotFoundException();
    Office branch = branches.getFirst();
    if (branch.hierarchy() == null
        || !branch.hierarchy().startsWith(user.getOffice().getHierarchy()))
      throw new AccessDeniedException("Office-level vault is outside the authorized branch scope.");

    // Reuse the established cashier/manager custody rules, including READ_GLOBAL_SETTLEMENT.
    CashInventoryContextData scope = inventory.context();
    var vault =
        scope.custodians().stream()
            .filter(c -> c.type() == CashInventoryCustodianType.VAULT)
            .filter(c -> c.key().equals("VAULT:" + officeId))
            .findFirst()
            .orElseThrow(
                () -> new AccessDeniedException("Vault is outside the authorized custody scope."));
    return new Selection(new VaultData.Branch(branch.id(), branch.name()), vault, date, scope);
  }

  private static Long officeId(String value, Long defaultId) {
    if (value == null) return defaultId;
    try {
      if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
      return Long.valueOf(value);
    } catch (NumberFormatException e) {
      throw invalid("officeId", "office.invalid", "officeId must be a positive integer.");
    }
  }

  private static List<VaultData.Currency> currencies(CashInventoryContextData scope) {
    return scope.currencies().stream()
        .map(c -> new VaultData.Currency(c.code(), c.name(), c.decimalPlaces()))
        .toList();
  }

  private static String currency(String value, CashInventoryContextData scope) {
    if (value == null || !value.trim().matches("[A-Za-z]{3}"))
      throw invalid("currencyCode", "currency.invalid", "currencyCode must contain three letters.");
    String code = value.trim().toUpperCase(Locale.ROOT);
    if (scope.currencies().stream().noneMatch(c -> code.equals(c.code())))
      throw invalid(
          "currencyCode", "currency.unsupported", "Currency is not enabled for this tenant.");
    return code;
  }

  private List<VaultData.Denomination> catalog(String currency) {
    var result =
        jdbc.query(
            "SELECT id,identifier,currency_code,value,denomination_type FROM"
                + " m_service_payment_denomination WHERE UPPER(currency_code)=:currency"
                + " AND active=true ORDER BY value DESC,id",
            Map.of("currency", currency),
            (rs, row) -> {
              String type = rs.getString("denomination_type");
              String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
              if (!List.of("BILL", "NOTE", "BANKNOTE", "COIN").contains(normalized))
                throw configuration(
                    "denomination.invalid", "Denomination configuration is invalid.");
              return new VaultData.Denomination(
                  rs.getLong("id"),
                  rs.getString("identifier"),
                  currency,
                  rs.getBigDecimal("value"),
                  "COIN".equals(normalized)
                      ? VaultData.DenominationType.COIN
                      : VaultData.DenominationType.BANKNOTE,
                  true);
            });
    var identifiers = new HashSet<String>();
    var values = new HashSet<java.math.BigDecimal>();
    for (var d : result) {
      if (d.denominationId() == null
          || d.denominationId().isBlank()
          || d.value() == null
          || d.value().signum() <= 0
          || !identifiers.add(d.denominationId())
          || !values.add(d.value().stripTrailingZeros()))
        throw configuration("denomination.invalid", "Denomination configuration is invalid.");
    }
    return List.copyOf(result);
  }

  private static VaultData.Status availability(List<?> values) {
    return values.isEmpty() ? VaultData.Status.UNAVAILABLE : VaultData.Status.AVAILABLE;
  }

  private static PlatformApiDataValidationException invalid(
      String parameter, String code, String message) {
    return new PlatformApiDataValidationException(
        "error.msg.base.teller.vault." + code, message, parameter);
  }

  private static PlatformInternalServerException configuration(String code, String message) {
    return new PlatformInternalServerException("error.msg.base.teller.vault." + code, message);
  }

  private static <T> T configured(Supplier<T> read) {
    try {
      return read.get();
    } catch (DataAccessException e) {
      // The platform's generic mapper otherwise includes the JDBC exception message in JSON.
      throw configuration(
          "configuration.unavailable", "Vault configuration or inventory is unavailable.");
    }
  }

  private record Office(Long id, String name, String hierarchy) {}

  private record Selection(
      VaultData.Branch branch,
      CashInventoryCustodianData vault,
      LocalDate date,
      CashInventoryContextData scope) {}
}
