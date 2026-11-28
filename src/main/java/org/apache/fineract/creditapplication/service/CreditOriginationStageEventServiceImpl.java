/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import static org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.COMPLETED;
import static org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.normalize;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.creditapplication.data.CreditOriginationScoreComponent;
import org.apache.fineract.creditapplication.data.CreditOriginationScoreComponentEventCommand;
import org.apache.fineract.creditapplication.data.CreditOriginationStageCode;
import org.apache.fineract.creditapplication.data.CreditOriginationStageEventCommand;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
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
  private static final Set<String> COMPONENT_STATUSES =
      Set.of("PENDING", "IN_PROGRESS", "COMPLETED", "FAILED", "REJECTED");
  private static final CreditOriginationStageCode SCORING =
      CreditOriginationStageCode.PARAMETRIC_SCORE;

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final PlatformSecurityContext context;

  @Override
  @Transactional
  public boolean recordStageEvent(final CreditOriginationStageEventCommand command) {
    final AppUser user = context.authenticatedUser();
    user.validateHasUpdatePermission("LOAN");
    validate(
        command != null
            && command.stage() != null
            && ALLOWED_STATUSES.contains(normalize(command.status())),
        command == null
            ? null
            : new EventFields(
                command.creditApplicationId(),
                command.clientId(),
                command.occurredOn(),
                command.source(),
                command.sourceReference(),
                command.eventKey(),
                command.reason()));
    lockVisibleApplication(command.creditApplicationId(), command.clientId(), user);
    if (isDuplicate(command.eventKey())) {
      return false;
    }
    if (COMPLETED.equals(normalize(command.status()))) {
      validateCompletion(command);
    }
    insert(
        command.creditApplicationId(),
        command.clientId(),
        command.stage(),
        null,
        command.status(),
        command.occurredOn(),
        command.actorId(),
        command.source(),
        command.sourceReference(),
        command.eventKey(),
        command.reason());
    return true;
  }

  @Override
  @Transactional
  public boolean recordScoreComponentEvent(
      final CreditOriginationScoreComponentEventCommand command) {
    final AppUser user = context.authenticatedUser();
    user.validateHasUpdatePermission("LOAN");
    validate(
        command != null
            && command.component() != null
            && COMPONENT_STATUSES.contains(normalize(command.status())),
        command == null
            ? null
            : new EventFields(
                command.creditApplicationId(),
                command.clientId(),
                command.occurredOn(),
                command.source(),
                command.sourceReference(),
                command.eventKey(),
                command.reason()));
    lockVisibleApplication(command.creditApplicationId(), command.clientId(), user);
    if (isDuplicate(command.eventKey())) {
      return false;
    }
    insert(
        command.creditApplicationId(),
        command.clientId(),
        SCORING,
        command.component(),
        command.status(),
        command.occurredOn(),
        command.actorId(),
        command.source(),
        command.sourceReference(),
        command.eventKey(),
        command.reason());
    return true;
  }

  /**
   * Runs under the application row lock, so concurrent evidence and completion writers are
   * serialized and the evidence read here is the evidence the completion is committed against.
   */
  private void validateCompletion(final CreditOriginationStageEventCommand command) {
    final Long loanId = command.creditApplicationId();
    final Long clientId = command.clientId();
    if (command.stage() == SCORING) {
      if (COMPLETED.equals(latestStageStatus(loanId, clientId))) {
        throw new GeneralPlatformDomainRuleException(
            "error.msg.credit.origination.parametric.score.already.completed",
            "Parametric score is already completed for this credit application.",
            loanId);
      }
      final List<String> missing =
          CreditOriginationBoardAssembler.missingMandatoryComponents(
              componentStatusesAsOf(loanId, clientId, command.occurredOn()));
      if (!missing.isEmpty()) {
        throw new GeneralPlatformDomainRuleException(
            "error.msg.credit.origination.parametric.score.requirements.missing",
            "Parametric score cannot be completed until SAT, Credit Bureau and Syntage evidence"
                + " is completed. Missing: "
                + String.join(", ", missing),
            missing.toArray());
      }
    } else if (command.stage().ordinal() > SCORING.ordinal()
        && !COMPLETED.equals(latestStageStatus(loanId, clientId))) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.credit.origination.parametric.score.not.completed",
          "Parametric score must be completed before " + command.stage().name() + ".",
          command.stage().name());
    }
  }

  private String latestStageStatus(final Long loanId, final Long clientId) {
    final List<String> statuses =
        jdbcTemplate.queryForList(
            "SELECT e.new_status FROM m_credit_origination_stage_event e"
                + " WHERE e.loan_id = :loanId AND e.client_id = :clientId"
                + " AND e.stage_code = :stage AND e.component_code IS NULL"
                + " ORDER BY e.occurred_on_utc DESC, e.id DESC",
            Map.of("loanId", loanId, "clientId", clientId, "stage", SCORING.name()),
            String.class);
    return statuses.isEmpty() ? null : normalize(statuses.getFirst());
  }

  /** Latest status per component, ignoring evidence dated after the completion it must support. */
  private Map<CreditOriginationScoreComponent, String> componentStatusesAsOf(
      final Long loanId, final Long clientId, final OffsetDateTime asOf) {
    final Map<CreditOriginationScoreComponent, String> statuses =
        new EnumMap<>(CreditOriginationScoreComponent.class);
    jdbcTemplate.query(
        "SELECT e.component_code, e.new_status FROM m_credit_origination_stage_event e"
            + " WHERE e.loan_id = :loanId AND e.client_id = :clientId"
            + " AND e.stage_code = :stage AND e.component_code IS NOT NULL"
            + " AND e.occurred_on_utc <= :asOf"
            + " ORDER BY e.occurred_on_utc DESC, e.id DESC",
        Map.of("loanId", loanId, "clientId", clientId, "stage", SCORING.name(), "asOf", utc(asOf)),
        rs -> {
          try {
            statuses.putIfAbsent(
                CreditOriginationScoreComponent.valueOf(rs.getString("component_code")),
                normalize(rs.getString("new_status")));
          } catch (IllegalArgumentException ignored) {
            // Unknown component codes are excluded by the table check constraint.
          }
        });
    return statuses;
  }

  private void validate(final boolean typeSpecificValid, final EventFields fields) {
    if (!typeSpecificValid
        || fields.creditApplicationId() == null
        || fields.creditApplicationId() <= 0
        || fields.clientId() == null
        || fields.clientId() <= 0
        || fields.occurredOn() == null
        || StringUtils.isBlank(fields.source())
        || StringUtils.isBlank(fields.eventKey())
        || fields.source().length() > 100
        || fields.eventKey().length() > 150
        || (fields.sourceReference() != null && fields.sourceReference().length() > 255)
        || (fields.reason() != null && fields.reason().length() > 500)) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.credit.origination.stage.event.invalid",
          "Credit origination stage event data is invalid.");
    }
  }

  /**
   * Verifies the loan/client relationship inside the user's office hierarchy, then takes a row lock
   * on the loan so evidence and completion writers for one application are serialized.
   */
  private void lockVisibleApplication(final Long loanId, final Long clientId, final AppUser user) {
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
                "officeHierarchy", user.getOffice().getHierarchy() + "%"),
            Long.class);
    if (count == null || count == 0) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.credit.origination.board.application.client.mismatch",
          "Credit application does not belong to the supplied client.");
    }
    jdbcTemplate.queryForList(
        "SELECT l.id FROM m_loan l WHERE l.id = :loanId FOR UPDATE",
        Map.of("loanId", loanId),
        Long.class);
  }

  private boolean isDuplicate(final String eventKey) {
    final Long duplicate =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_credit_origination_stage_event WHERE event_key = :eventKey",
            Map.of("eventKey", eventKey.trim()),
            Long.class);
    return duplicate != null && duplicate > 0;
  }

  private void insert(
      final Long loanId,
      final Long clientId,
      final CreditOriginationStageCode stage,
      final CreditOriginationScoreComponent component,
      final String status,
      final OffsetDateTime occurredOn,
      final Long actorId,
      final String source,
      final String sourceReference,
      final String eventKey,
      final String reason) {
    final MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("loanId", loanId)
            .addValue("clientId", clientId)
            .addValue("stage", stage.name())
            .addValue("component", component == null ? null : component.name())
            .addValue("status", normalize(status))
            .addValue("occurredOn", utc(occurredOn))
            .addValue("actorId", actorId)
            .addValue("source", source.trim())
            .addValue("sourceReference", StringUtils.trimToNull(sourceReference))
            .addValue("eventKey", eventKey.trim())
            .addValue("reason", StringUtils.trimToNull(reason));
    jdbcTemplate.update(
        "INSERT INTO m_credit_origination_stage_event"
            + " (loan_id, client_id, stage_code, component_code, new_status, occurred_on_utc,"
            + " recorded_on_utc, actor_id, source, source_reference, event_key, reason)"
            + " VALUES (:loanId, :clientId, :stage, :component, :status, :occurredOn,"
            + " CURRENT_TIMESTAMP, :actorId, :source, :sourceReference, :eventKey, :reason)",
        parameters);
  }

  /** Binds the UTC wall-clock value expected by the *_utc columns, independent of JVM zone. */
  private static LocalDateTime utc(final OffsetDateTime value) {
    return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }

  private record EventFields(
      Long creditApplicationId,
      Long clientId,
      OffsetDateTime occurredOn,
      String source,
      String sourceReference,
      String eventKey,
      String reason) {}
}
