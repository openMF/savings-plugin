/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.prospect.validation;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.prospect.data.ProspectRegistrationStatus;
import org.apache.fineract.prospect.data.ProspectSearchCriteria;
import org.apache.fineract.prospect.data.ProspectSearchRequest;
import org.springframework.stereotype.Component;

@Component
public class ProspectSearchValidator {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;
  private static final String DEFAULT_ORDER_BY = "lastUpdatedAt";
  private static final String DEFAULT_SORT_ORDER = "DESC";
  private static final Set<String> ORDER_BY_FIELDS =
      Set.of(
          "prospectId",
          "externalRef",
          "displayName",
          "officeId",
          "clientId",
          "registrationStatus",
          "createdAt",
          "submittedAt",
          "lastUpdatedAt",
          "currentStage",
          "lastCompletedStage",
          "stoppedAtStage",
          "pendingCreditCount");

  public ProspectSearchCriteria validate(final ProspectSearchRequest request) {
    final ProspectSearchRequest safeRequest = request == null ? new ProspectSearchRequest() : request;
    final List<ApiParameterError> errors = new ArrayList<>();
    final String q = StringUtils.trimToNull(safeRequest.getQ());
    final String registrationStatus = normalizeStatus(safeRequest.getRegistrationStatus());
    final String lastCompletedStageCode =
        normalizeCode(safeRequest.getLastCompletedStageCode());
    final LocalDate createdFrom = parseDate("createdFrom", safeRequest.getCreatedFrom(), errors);
    final LocalDate createdTo = parseDate("createdTo", safeRequest.getCreatedTo(), errors);
    final int offset = safeRequest.getOffset() == null ? 0 : safeRequest.getOffset();
    final int limit = safeRequest.getLimit() == null ? DEFAULT_LIMIT : safeRequest.getLimit();
    final String orderBy =
        StringUtils.isBlank(safeRequest.getOrderBy())
            ? DEFAULT_ORDER_BY
            : safeRequest.getOrderBy().trim();
    final String sortOrder =
        StringUtils.isBlank(safeRequest.getSortOrder())
            ? DEFAULT_SORT_ORDER
            : safeRequest.getSortOrder().trim().toUpperCase(Locale.ROOT);

    validatePositiveId("officeId", safeRequest.getOfficeId(), errors);
    validatePositiveId("clientId", safeRequest.getClientId(), errors);
    if (q != null && q.length() > 100) {
      addError(errors, "q", q, "Search text must not exceed 100 characters.");
    }
    if (registrationStatus != null && !ProspectRegistrationStatus.isSupported(registrationStatus)) {
      addError(
          errors,
          "registrationStatus",
          registrationStatus,
          "Unsupported prospect registration status.");
    }
    if (lastCompletedStageCode != null && lastCompletedStageCode.length() > 100) {
      addError(
          errors,
          "lastCompletedStageCode",
          lastCompletedStageCode,
          "Stage code must not exceed 100 characters.");
    }
    if (offset < 0) {
      addError(errors, "offset", offset, "Offset must be zero or greater.");
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      addError(errors, "limit", limit, "Limit must be between 1 and " + MAX_LIMIT + ".");
    }
    if (!ORDER_BY_FIELDS.contains(orderBy)) {
      addError(errors, "orderBy", orderBy, "Unsupported prospect sort field.");
    }
    if (!Set.of("ASC", "DESC").contains(sortOrder)) {
      addError(errors, "sortOrder", sortOrder, "Sort order must be ASC or DESC.");
    }
    if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
      addError(
          errors,
          "createdTo",
          createdTo,
          "Created-to date must be on or after created-from date.");
    }

    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }

    return new ProspectSearchCriteria(
        q,
        safeRequest.getOfficeId(),
        registrationStatus,
        createdFrom,
        createdTo,
        safeRequest.getClientId(),
        lastCompletedStageCode,
        offset,
        limit,
        orderBy,
        sortOrder);
  }

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

  private String normalizeStatus(final String value) {
    final String normalized = StringUtils.trimToNull(value);
    return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
  }

  private String normalizeCode(final String value) {
    final String normalized = StringUtils.trimToNull(value);
    return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
  }

  private void addError(
      final List<ApiParameterError> errors,
      final String parameter,
      final Object value,
      final String message) {
    errors.add(
        ApiParameterError.parameterError(
            "validation.msg.prospect." + parameter + ".invalid",
            message,
            parameter,
            value == null ? "" : value));
  }
}
