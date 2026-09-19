/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.onboarding.data.AcquisitionBoardData;
import org.apache.fineract.onboarding.data.AcquisitionStageCode;
import org.apache.fineract.onboarding.data.AcquisitionStageDetailsData;
import org.apache.fineract.onboarding.service.AcquisitionBoardAssembler.StageEvidence;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountNotFoundException;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AcquisitionBoardReadPlatformServiceImpl
    implements AcquisitionBoardReadPlatformService {

  private static final String CLIENT_RESOURCE = "CLIENT";
  private static final String SAVINGS_ACCOUNT_RESOURCE = "savingsaccount";
  private static final String ENROLLMENT_STATUS_RESOURCE = "ENROLLMENT_STATUS";
  private static final String COMPLETED = "COMPLETED";

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final PlatformSecurityContext context;

  @Override
  public AcquisitionBoardData retrieve(final Long clientId, final Long savingsAccountId) {
    final AppUser user = context.authenticatedUser();
    user.validateHasReadPermission(CLIENT_RESOURCE);
    user.validateHasReadPermission(SAVINGS_ACCOUNT_RESOURCE);
    user.validateHasReadPermission(ENROLLMENT_STATUS_RESOURCE);
    validateIdentifiers(clientId, savingsAccountId);

    final String officeHierarchy = user.getOffice().getHierarchy() + "%";
    validateVisibleClient(clientId, officeHierarchy);
    final AccountLifecycle account = retrieveAccount(savingsAccountId, officeHierarchy);
    if (!clientId.equals(account.clientId())) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.acquisition.board.account.client.mismatch",
          "Savings account does not belong to the supplied client.");
    }

    final ProspectRegistration prospect = retrieveProspect(clientId);
    final Map<AcquisitionStageCode, StageEvidence> explicitEvents =
        retrieveExplicitEvents(prospect);
    final Map<AcquisitionStageCode, StageEvidence> stages =
        new EnumMap<>(AcquisitionStageCode.class);
    stages.put(
        AcquisitionStageCode.ONBOARDING,
        onboardingEvidence(prospect, explicitEvents.get(AcquisitionStageCode.ONBOARDING)));
    final StageEvidence compliance = explicitEvents.get(AcquisitionStageCode.COMPLIANCE);
    stages.put(
        AcquisitionStageCode.COMPLIANCE,
        compliance == null ? missingEvent(AcquisitionStageCode.COMPLIANCE) : compliance);
    stages.put(
        AcquisitionStageCode.APPROVAL,
        approvalEvidence(explicitEvents.get(AcquisitionStageCode.APPROVAL), account));
    stages.put(AcquisitionStageCode.ACTIVATION, activationEvidence(account));
    stages.putAll(transactionEvidence(savingsAccountId, account.currencyCode()));

    return AcquisitionBoardAssembler.assemble(
        clientId, savingsAccountId, prospect == null ? null : prospect.id(), stages);
  }

  private void validateIdentifiers(final Long clientId, final Long savingsAccountId) {
    if (clientId == null || clientId <= 0) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.acquisition.board.client.id.invalid", "clientId must be a positive number.");
    }
    if (savingsAccountId == null || savingsAccountId <= 0) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.acquisition.board.account.id.invalid",
          "savingsAccountId must be a positive number.");
    }
  }

  private void validateVisibleClient(final Long clientId, final String officeHierarchy) {
    final Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_client c"
                + " JOIN m_office o ON o.id = c.office_id"
                + " LEFT JOIN m_office transfer_o ON transfer_o.id = c.transfer_to_office_id"
                + " WHERE c.id = :clientId"
                + " AND (o.hierarchy LIKE :officeHierarchy"
                + " OR transfer_o.hierarchy LIKE :officeHierarchy)",
            Map.of("clientId", clientId, "officeHierarchy", officeHierarchy),
            Long.class);
    if (count == null || count == 0) {
      throw new ClientNotFoundException(clientId);
    }
  }

  private AccountLifecycle retrieveAccount(
      final Long savingsAccountId, final String officeHierarchy) {
    final List<AccountLifecycle> accounts =
        jdbcTemplate.query(
            "SELECT sa.id, sa.client_id, sa.status_enum, sa.currency_code,"
                + " sa.approvedon_date, sa.approvedon_userid, approved.username approved_by,"
                + " sa.activatedon_date, sa.activatedon_userid, activated.username activated_by"
                + " FROM m_savings_account sa"
                + " JOIN m_client c ON c.id = sa.client_id"
                + " JOIN m_office o ON o.id = c.office_id"
                + " LEFT JOIN m_office transfer_o ON transfer_o.id = c.transfer_to_office_id"
                + " LEFT JOIN m_appuser approved ON approved.id = sa.approvedon_userid"
                + " LEFT JOIN m_appuser activated ON activated.id = sa.activatedon_userid"
                + " WHERE sa.id = :accountId"
                + " AND (o.hierarchy LIKE :officeHierarchy"
                + " OR transfer_o.hierarchy LIKE :officeHierarchy)",
            Map.of("accountId", savingsAccountId, "officeHierarchy", officeHierarchy),
            (rs, rowNumber) ->
                new AccountLifecycle(
                    rs.getLong("client_id"),
                    SavingsAccountStatusType.fromInt(rs.getInt("status_enum")),
                    rs.getString("currency_code"),
                    localDate(rs, "approvedon_date"),
                    nullableLong(rs, "approvedon_userid"),
                    rs.getString("approved_by"),
                    localDate(rs, "activatedon_date"),
                    nullableLong(rs, "activatedon_userid"),
                    rs.getString("activated_by")));
    if (accounts.isEmpty()) {
      throw new SavingsAccountNotFoundException(savingsAccountId);
    }
    return accounts.get(0);
  }

  private ProspectRegistration retrieveProspect(final Long clientId) {
    final List<ProspectRegistration> prospects =
        jdbcTemplate.query(
            "SELECT p.id, p.registration_status FROM m_prospect_registration p"
                + " WHERE p.client_id = :clientId ORDER BY p.created_on_utc DESC, p.id DESC",
            Map.of("clientId", clientId),
            (rs, rowNumber) ->
                new ProspectRegistration(rs.getLong("id"), rs.getString("registration_status")));
    if (prospects.size() > 1) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.acquisition.board.prospect.ambiguous",
          "More than one prospect registration is linked to the client; acquisition data is"
              + " ambiguous.");
    }
    return prospects.isEmpty() ? null : prospects.get(0);
  }

  private StageEvidence onboardingEvidence(
      final ProspectRegistration prospect, final StageEvidence explicitEvent) {
    if (explicitEvent != null) {
      return explicitEvent;
    }
    if (prospect == null) {
      return missingEvent(AcquisitionStageCode.ONBOARDING);
    }
    final boolean completed = COMPLETED.equalsIgnoreCase(prospect.status());
    return new StageEvidence(
        completed,
        null,
        prospect.status(),
        new AcquisitionStageDetailsData(
            "m_prospect_registration.registration_status",
            prospect.status(),
            null,
            null,
            String.valueOf(prospect.id()),
            null,
            null,
            null,
            null,
            null));
  }

  private StageEvidence approvalEvidence(
      final StageEvidence explicitEvent, final AccountLifecycle account) {
    if (explicitEvent != null) {
      return explicitEvent;
    }
    final String accountStatus = account.status().name();
    final boolean completed = account.approvedOn() != null || isAtLeastApproved(account.status());
    final String sourceStatus =
        account.status().isRejected()
            ? "REJECTED"
            : account.status().isApplicationWithdrawnByApplicant() ? "WITHDRAWN" : accountStatus;
    return new StageEvidence(
        completed,
        completed && account.approvedOn() != null ? account.approvedOn().toString() : null,
        sourceStatus,
        new AcquisitionStageDetailsData(
            "m_savings_account.status_enum,m_savings_account.approvedon_date",
            sourceStatus,
            account.approvedById(),
            account.approvedBy(),
            null,
            null,
            null,
            null,
            account.currencyCode(),
            accountStatus));
  }

  private StageEvidence activationEvidence(final AccountLifecycle account) {
    final String accountStatus = account.status().name();
    final boolean completed = account.activatedOn() != null || isAtLeastActivated(account.status());
    final String sourceStatus = completed ? accountStatus : "PENDING";
    return new StageEvidence(
        completed,
        completed && account.activatedOn() != null ? account.activatedOn().toString() : null,
        sourceStatus,
        new AcquisitionStageDetailsData(
            "m_savings_account.status_enum,m_savings_account.activatedon_date",
            sourceStatus,
            account.activatedById(),
            account.activatedBy(),
            null,
            null,
            null,
            null,
            account.currencyCode(),
            accountStatus));
  }

  private Map<AcquisitionStageCode, StageEvidence> retrieveExplicitEvents(
      final ProspectRegistration prospect) {
    if (prospect == null) {
      return Map.of();
    }
    final List<ProspectStageEvent> events =
        jdbcTemplate.query(
            "SELECT e.stage_code, e.new_status, e.occurred_on_utc, e.actor_id,"
                + " actor.username actor_name,"
                + " e.source, e.source_reference, e.reason"
                + " FROM m_prospect_stage_event e"
                + " LEFT JOIN m_appuser actor ON actor.id = e.actor_id"
                + " WHERE e.case_id = :caseId"
                + " AND e.stage_code IN"
                + " ('ONBOARDING', 'COMMERCIAL_REGISTRATION', 'COMPLIANCE', 'APPROVAL')"
                + " ORDER BY e.sequence_no DESC, e.recorded_on_utc DESC, e.id DESC",
            Map.of("caseId", prospect.id()),
            (rs, rowNumber) ->
                new ProspectStageEvent(
                    rs.getString("stage_code"),
                    rs.getString("new_status"),
                    offsetDateTime(rs, "occurred_on_utc"),
                    nullableLong(rs, "actor_id"),
                    rs.getString("actor_name"),
                    rs.getString("source"),
                    rs.getString("source_reference"),
                    rs.getString("reason")));
    final Map<AcquisitionStageCode, StageEvidence> evidence =
        new EnumMap<>(AcquisitionStageCode.class);
    for (ProspectStageEvent event : events) {
      final AcquisitionStageCode stage = canonicalStage(event.stageCode());
      final boolean completed = COMPLETED.equalsIgnoreCase(event.status());
      evidence.putIfAbsent(
          stage,
          new StageEvidence(
              completed,
              completed ? event.occurredOn() : null,
              event.status(),
              new AcquisitionStageDetailsData(
                  "m_prospect_stage_event:" + event.source(),
                  event.status(),
                  event.actorId(),
                  event.actorName(),
                  event.sourceReference(),
                  event.reason(),
                  null,
                  null,
                  null,
                  null)));
    }
    return evidence;
  }

  static AcquisitionStageCode canonicalStage(final String sourceStageCode) {
    final String normalized = sourceStageCode.toUpperCase(Locale.ROOT);
    return "COMMERCIAL_REGISTRATION".equals(normalized)
        ? AcquisitionStageCode.ONBOARDING
        : AcquisitionStageCode.valueOf(normalized);
  }

  private Map<AcquisitionStageCode, StageEvidence> transactionEvidence(
      final Long savingsAccountId, final String currencyCode) {
    final Map<String, Object> parameters = new HashMap<>();
    parameters.put("accountId", savingsAccountId);
    parameters.put("depositType", SavingsAccountTransactionType.DEPOSIT.getValue());
    parameters.put("withdrawalType", SavingsAccountTransactionType.WITHDRAWAL.getValue());
    final List<SavingsMilestoneTransaction> transactions =
        jdbcTemplate.query(
            "SELECT t.id, t.transaction_type_enum, t.transaction_date, t.amount"
                + " FROM m_savings_account_transaction t"
                + " WHERE t.savings_account_id = :accountId AND t.is_reversed = false"
                + " AND t.transaction_type_enum IN (:depositType, :withdrawalType)"
                + " ORDER BY t.transaction_date, t.id",
            parameters,
            (rs, rowNumber) ->
                new SavingsMilestoneTransaction(
                    rs.getLong("id"),
                    rs.getInt("transaction_type_enum"),
                    localDate(rs, "transaction_date"),
                    rs.getBigDecimal("amount")));

    final Map<AcquisitionStageCode, StageEvidence> evidence =
        new EnumMap<>(AcquisitionStageCode.class);
    for (SavingsMilestoneTransaction transaction : transactions) {
      final AcquisitionStageCode stage =
          transaction.type() == SavingsAccountTransactionType.DEPOSIT.getValue()
              ? AcquisitionStageCode.DEPOSIT
              : AcquisitionStageCode.WITHDRAWAL;
      evidence.putIfAbsent(stage, transactionStage(transaction, currencyCode));
    }
    evidence.putIfAbsent(AcquisitionStageCode.DEPOSIT, missingTransaction("DEPOSIT"));
    evidence.putIfAbsent(AcquisitionStageCode.WITHDRAWAL, missingTransaction("WITHDRAWAL"));
    return evidence;
  }

  private StageEvidence transactionStage(
      final SavingsMilestoneTransaction transaction, final String currencyCode) {
    return new StageEvidence(
        true,
        transaction.transactionDate().toString(),
        "POSTED",
        new AcquisitionStageDetailsData(
            "m_savings_account_transaction",
            "POSTED",
            null,
            null,
            String.valueOf(transaction.id()),
            null,
            transaction.id(),
            transaction.amount(),
            currencyCode,
            null));
  }

  private StageEvidence missingEvent(final AcquisitionStageCode stage) {
    return new StageEvidence(
        false,
        null,
        "NOT_RECORDED",
        new AcquisitionStageDetailsData(
            "m_prospect_stage_event",
            "NOT_RECORDED",
            null,
            null,
            stage.name(),
            null,
            null,
            null,
            null,
            null));
  }

  private StageEvidence missingTransaction(final String transactionType) {
    return new StageEvidence(
        false,
        null,
        "NOT_POSTED",
        new AcquisitionStageDetailsData(
            "m_savings_account_transaction",
            "NOT_POSTED",
            null,
            null,
            transactionType,
            null,
            null,
            null,
            null,
            null));
  }

  private static boolean isAtLeastApproved(final SavingsAccountStatusType status) {
    return status.isApproved() || isAtLeastActivated(status);
  }

  private static boolean isAtLeastActivated(final SavingsAccountStatusType status) {
    return status.isActive()
        || status.isTransferInProgress()
        || status.isTransferOnHold()
        || status.isClosed()
        || status.isPreMatureClosure()
        || status.isMatured();
  }

  private static Long nullableLong(final ResultSet rs, final String column) throws SQLException {
    final long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static LocalDate localDate(final ResultSet rs, final String column) throws SQLException {
    final Date date = rs.getDate(column);
    return date == null ? null : date.toLocalDate();
  }

  private static String offsetDateTime(final ResultSet rs, final String column)
      throws SQLException {
    final Timestamp timestamp = rs.getTimestamp(column);
    return timestamp == null
        ? null
        : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC).toString();
  }

  private record AccountLifecycle(
      Long clientId,
      SavingsAccountStatusType status,
      String currencyCode,
      LocalDate approvedOn,
      Long approvedById,
      String approvedBy,
      LocalDate activatedOn,
      Long activatedById,
      String activatedBy) {}

  private record ProspectRegistration(Long id, String status) {}

  private record ProspectStageEvent(
      String stageCode,
      String status,
      String occurredOn,
      Long actorId,
      String actorName,
      String source,
      String sourceReference,
      String reason) {}

  private record SavingsMilestoneTransaction(
      Long id, int type, LocalDate transactionDate, BigDecimal amount) {}
}
