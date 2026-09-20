/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.creditapplication.data.CreditOriginationStageEventCommand;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditOriginationStageEventServiceImpl implements CreditOriginationStageEventService {

  private static final Set<String> ALLOWED_STATUSES =
      Set.of(
          "PENDING",
          "IN_PROGRESS",
          "COMPLETED",
          "BLOCKED",
          "CANCELLED",
          "FAILED",
          "REJECTED",
          "WITHDRAWN");

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final PlatformSecurityContext context;

  @Override
  @Transactional
  public boolean recordStageEvent(final CreditOriginationStageEventCommand command) {
    context.authenticatedUser().validateHasUpdatePermission("LOAN");
    validate(command);
    validateRelationship(
        command.creditApplicationId(),
        command.clientId(),
        context.authenticatedUser().getOffice().getHierarchy() + "%");

    final Long duplicate =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_credit_origination_stage_event WHERE event_key = :eventKey",
            Map.of("eventKey", command.eventKey().trim()),
            Long.class);
    if (duplicate != null && duplicate > 0) {
      return false;
    }

    final MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("loanId", command.creditApplicationId())
            .addValue("clientId", command.clientId())
            .addValue("stage", command.stage().name())
            .addValue("status", normalize(command.status()))
            .addValue(
                "occurredOn",
                Timestamp.from(
                    command.occurredOn().withOffsetSameInstant(ZoneOffset.UTC).toInstant()))
            .addValue("actorId", command.actorId())
            .addValue("source", command.source().trim())
            .addValue("sourceReference", StringUtils.trimToNull(command.sourceReference()))
            .addValue("eventKey", command.eventKey().trim())
            .addValue("reason", StringUtils.trimToNull(command.reason()));
    jdbcTemplate.update(
        "INSERT INTO m_credit_origination_stage_event"
            + " (loan_id, client_id, stage_code, new_status, occurred_on_utc, recorded_on_utc,"
            + " actor_id, source, source_reference, event_key, reason)"
            + " VALUES (:loanId, :clientId, :stage, :status, :occurredOn, CURRENT_TIMESTAMP,"
            + " :actorId, :source, :sourceReference, :eventKey, :reason)",
        parameters);
    return true;
  }

  private void validate(final CreditOriginationStageEventCommand command) {
    if (command == null
        || command.creditApplicationId() == null
        || command.creditApplicationId() <= 0
        || command.clientId() == null
        || command.clientId() <= 0
        || command.stage() == null
        || !ALLOWED_STATUSES.contains(normalize(command.status()))
        || command.occurredOn() == null
        || StringUtils.isBlank(command.source())
        || StringUtils.isBlank(command.eventKey())
        || command.source().length() > 100
        || command.eventKey().length() > 150
        || (command.sourceReference() != null && command.sourceReference().length() > 255)
        || (command.reason() != null && command.reason().length() > 500)) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.credit.origination.stage.event.invalid",
          "Credit origination stage event data is invalid.");
    }
  }

  private void validateRelationship(
      final Long loanId, final Long clientId, final String officeHierarchy) {
    final Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_loan l"
                + " JOIN m_client c ON c.id = l.client_id"
                + " JOIN m_office o ON o.id = c.office_id"
                + " LEFT JOIN m_office transfer_o ON transfer_o.id = c.transfer_to_office_id"
                + " WHERE l.id = :loanId AND l.client_id = :clientId"
                + " AND (o.hierarchy LIKE :officeHierarchy"
                + " OR transfer_o.hierarchy LIKE :officeHierarchy)",
            Map.of(
                "loanId", loanId,
                "clientId", clientId,
                "officeHierarchy", officeHierarchy),
            Long.class);
    if (count == null || count == 0) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.credit.origination.board.application.client.mismatch",
          "Credit application does not belong to the supplied client.");
    }
  }

  private static String normalize(final String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
