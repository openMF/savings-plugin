/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.validation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.creditapplication.data.CreditApplicationSearchCriteria;
import org.apache.fineract.creditapplication.data.CreditApplicationSearchRequest;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.springframework.stereotype.Component;

/** Validates and normalizes credit-application search parameters. */
@Component
public class CreditApplicationSearchValidator {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;
  private static final String DEFAULT_ORDER_BY = "submittedOnDate";
  private static final String DEFAULT_SORT_ORDER = "DESC";
  private static final Set<String> ORDER_BY_FIELDS =
      Set.of(
          "loanId",
          "accountNo",
          "clientName",
          "clientTypeId",
          "officeId",
          "productId",
          "productName",
          "currencyCode",
          "amount",
          "status",
          "submittedOnDate",
          "stateProvinceId",
          "municipality");

  /** Returns a validated immutable criteria object or raises a Fineract validation exception. */
  public CreditApplicationSearchCriteria validate(final CreditApplicationSearchRequest request) {
    final List<ApiParameterError> errors = new ArrayList<>();
    final int offset = request.getOffset() == null ? 0 : request.getOffset();
    final int limit = request.getLimit() == null ? DEFAULT_LIMIT : request.getLimit();
    final String orderBy =
        StringUtils.isBlank(request.getOrderBy()) ? DEFAULT_ORDER_BY : request.getOrderBy().trim();
    final String sortOrder =
        StringUtils.isBlank(request.getSortOrder())
            ? DEFAULT_SORT_ORDER
            : request.getSortOrder().trim().toUpperCase(Locale.ROOT);
    final String currencyCode = normalizeCurrencyCode(request.getCurrencyCode());
    final String municipality = StringUtils.trimToNull(request.getMunicipality());
    final LocalDate submittedFrom = parseDate("submittedFrom", request.getSubmittedFrom(), errors);
    final LocalDate submittedTo = parseDate("submittedTo", request.getSubmittedTo(), errors);

    validatePositiveId("clientId", request.getClientId(), errors);
    validatePositiveId("officeId", request.getOfficeId(), errors);
    validatePositiveId("clientTypeId", request.getClientTypeId(), errors);
    validatePositiveId("productId", request.getProductId(), errors);
    validatePositiveId("stateProvinceId", request.getStateProvinceId(), errors);

    if (offset < 0) {
      addError(errors, "offset", offset, "Offset must be zero or greater.");
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      addError(errors, "limit", limit, "Limit must be between 1 and " + MAX_LIMIT + ".");
    }
    if (!ORDER_BY_FIELDS.contains(orderBy)) {
      addError(errors, "orderBy", orderBy, "Unsupported credit-application sort field.");
    }
    if (!Set.of("ASC", "DESC").contains(sortOrder)) {
      addError(errors, "sortOrder", sortOrder, "Sort order must be ASC or DESC.");
    }
    if (request.getStatus() != null
        && LoanStatus.fromInt(request.getStatus()) == LoanStatus.INVALID) {
      addError(errors, "status", request.getStatus(), "Unsupported Fineract loan status.");
    }
    validateNonNegative("minAmount", request.getMinAmount(), errors);
    validateNonNegative("maxAmount", request.getMaxAmount(), errors);
    if (request.getMinAmount() != null
        && request.getMaxAmount() != null
        && request.getMinAmount().compareTo(request.getMaxAmount()) > 0) {
      addError(
          errors,
          "maxAmount",
          request.getMaxAmount(),
          "Maximum amount must be greater than or equal to minimum amount.");
    }
    if ((request.getMinAmount() != null || request.getMaxAmount() != null)
        && currencyCode == null) {
      addError(errors, "currencyCode", null, "Currency code is required when filtering by amount.");
    }
    if (currencyCode != null && !currencyCode.matches("[A-Z]{3}")) {
      addError(errors, "currencyCode", currencyCode, "Currency code must contain three letters.");
    }
    if (municipality != null && municipality.length() > 100) {
      addError(
          errors, "municipality", municipality, "Municipality must not exceed 100 characters.");
    }
    if (submittedFrom != null && submittedTo != null && submittedFrom.isAfter(submittedTo)) {
      addError(
          errors,
          "submittedTo",
          submittedTo,
          "Submitted-to date must be on or after submitted-from date.");
    }

    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }

    return new CreditApplicationSearchCriteria(
        request.getClientId(),
        request.getOfficeId(),
        request.getClientTypeId(),
        request.getProductId(),
        request.getStatus(),
        submittedFrom,
        submittedTo,
        request.getMinAmount(),
        request.getMaxAmount(),
        currencyCode,
        request.getStateProvinceId(),
        municipality,
        offset,
        limit,
        orderBy,
        sortOrder);
  }

  /** Exposes the stable API sort names for documentation and focused tests. */
  public Set<String> supportedOrderByFields() {
    return ORDER_BY_FIELDS;
  }

  private LocalDate parseDate(
      final String parameter, final String value, final List<ApiParameterError> errors) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (final DateTimeParseException e) {
      addError(errors, parameter, value, "Date must use ISO format yyyy-MM-dd.");
      return null;
    }
  }

  private void validatePositiveId(
      final String parameter, final Long value, final List<ApiParameterError> errors) {
    if (value != null && value <= 0) {
      addError(errors, parameter, value, "Identifier must be greater than zero.");
    }
  }

  private void validateNonNegative(
      final String parameter, final BigDecimal value, final List<ApiParameterError> errors) {
    if (value != null && value.signum() < 0) {
      addError(errors, parameter, value, "Amount must be zero or greater.");
    }
  }

  private String normalizeCurrencyCode(final String currencyCode) {
    final String normalized = StringUtils.trimToNull(currencyCode);
    return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
  }

  private void addError(
      final List<ApiParameterError> errors,
      final String parameter,
      final Object value,
      final String message) {
    errors.add(
        ApiParameterError.parameterError(
            "validation.msg.creditApplication." + parameter + ".invalid",
            message,
            parameter,
            value == null ? "" : value));
  }
}
