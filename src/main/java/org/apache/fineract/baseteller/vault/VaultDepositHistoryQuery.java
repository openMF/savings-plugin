/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import java.time.LocalDate;
import java.util.Locale;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;

/** Strings are validated here so malformed HTTP values use the platform's structured errors. */
public record VaultDepositHistoryQuery(
    String officeId,
    String vaultKey,
    String currencyCode,
    String status,
    String fromDate,
    String toDate,
    String reference,
    String offset,
    String limit) {
  private static final String REFERENCE_PATTERN =
      "VDA-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

  Resolved resolve() {
    Long office = officeId == null ? null : number(officeId, "officeId", 1, Long.MAX_VALUE);
    if (vaultKey != null) {
      if (!vaultKey.matches("VAULT:[1-9][0-9]{0,18}")) throw invalid("vaultKey");
      long vaultOffice = number(vaultKey.substring(6), "vaultKey", 1, Long.MAX_VALUE);
      if (office != null && office != vaultOffice) throw invalid("vaultKey");
      office = vaultOffice;
    }
    String currency = null;
    if (currencyCode != null) {
      if (!currencyCode.matches("[A-Za-z]{3}")) throw invalid("currencyCode");
      currency = currencyCode.toUpperCase(Locale.ROOT);
    }
    if (status != null && !"BLOCKED".equals(status)) throw invalid("status");
    LocalDate from = date(fromDate, "fromDate"), to = date(toDate, "toDate");
    if (from != null && to != null && from.isAfter(to)) throw invalid("toDate");
    if (reference != null && !reference.matches(REFERENCE_PATTERN)) throw invalid("reference");
    return new Resolved(
        office,
        currency,
        status,
        from,
        to,
        reference,
        offset == null ? 0 : (int) number(offset, "offset", 0, Integer.MAX_VALUE),
        limit == null ? 25 : (int) number(limit, "limit", 1, 100));
  }

  static void validateReference(String reference) {
    if (reference == null || !reference.matches(REFERENCE_PATTERN))
      throw new VaultDepositHistoryService.AttemptNotFoundException();
  }

  private static long number(String value, String field, long min, long max) {
    try {
      if (!value.matches("0|[1-9][0-9]{0,18}")) throw new NumberFormatException();
      long number = Long.parseLong(value);
      if (number < min || number > max) throw new NumberFormatException();
      return number;
    } catch (NumberFormatException e) {
      throw invalid(field);
    }
  }

  private static LocalDate date(String value, String field) {
    if (value == null) return null;
    try {
      if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid(field);
      return LocalDate.parse(value);
    } catch (java.time.format.DateTimeParseException e) {
      throw invalid(field);
    }
  }

  static PlatformApiDataValidationException invalid(String field) {
    return new PlatformApiDataValidationException(
        "error.msg.base.teller.vault.history.filter.invalid",
        "Invalid vault deposit history parameter: " + field + ".",
        field);
  }

  record Resolved(
      Long officeId,
      String currencyCode,
      String status,
      LocalDate fromDate,
      LocalDate toDate,
      String reference,
      int offset,
      int limit) {}
}
