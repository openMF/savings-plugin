/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.creditapplication.data.CreditOriginationBoardData;
import org.apache.fineract.creditapplication.data.CreditOriginationStageCode;
import org.apache.fineract.creditapplication.data.CreditOriginationStageDetailsData;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.StageEvidence;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.domain.ClientStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.exception.LoanNotFoundException;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds a source-qualified projection without duplicating authoritative Fineract loan state. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreditOriginationBoardReadPlatformServiceImpl
    implements CreditOriginationBoardReadPlatformService {

  private static final String LOAN_RESOURCE = "LOAN";
  private static final String COMPLETED = "COMPLETED";

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final PlatformSecurityContext context;

  @Override
  public CreditOriginationBoardData retrieve(final Long creditApplicationId) {
    final AppUser user = context.authenticatedUser();
    user.validateHasReadPermission(LOAN_RESOURCE);
    if (creditApplicationId == null || creditApplicationId <= 0) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.credit.origination.board.application.id.invalid",
          "creditApplicationId must be a positive number.");
    }

    final LoanLifecycle loan =
        retrieveVisibleLoan(creditApplicationId, user.getOffice().getHierarchy() + "%");
    if (loan.clientId() == null) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.credit.origination.board.client.required",
          "The credit origination board is only available for client loan applications.");
    }

    final ProspectRegistration prospect = retrieveLatestProspect(loan.clientId());
    final Map<CreditOriginationStageCode, StageEvidence> explicit =
        retrieveExplicitEvents(loan.loanId(), loan.clientId());
    final Map<CreditOriginationStageCode, StageEvidence> stages =
        new EnumMap<>(CreditOriginationStageCode.class);

    stages.put(
        CreditOriginationStageCode.ONBOARDING,
        preferExplicit(
            explicit.get(CreditOriginationStageCode.ONBOARDING),
            onboardingEvidence(loan, prospect)));
    stages.put(
        CreditOriginationStageCode.COMPLIANCE,
        preferExplicit(
            explicit.get(CreditOriginationStageCode.COMPLIANCE),
            retrieveComplianceEvidence(loan.clientId())));
    putExplicit(stages, explicit, CreditOriginationStageCode.PARAMETRIC_SCORE);
    putExplicit(stages, explicit, CreditOriginationStageCode.FILE_INTEGRATION);
    putExplicit(stages, explicit, CreditOriginationStageCode.CREDIT_ANALYSIS);
    stages.put(
        CreditOriginationStageCode.APPROVAL,
        preferExplicit(explicit.get(CreditOriginationStageCode.APPROVAL), approvalEvidence(loan)));
    putExplicit(stages, explicit, CreditOriginationStageCode.LEGAL_INSTRUMENTATION);
    stages.put(
        CreditOriginationStageCode.DISBURSEMENT,
        preferExplicit(
            explicit.get(CreditOriginationStageCode.DISBURSEMENT), disbursementEvidence(loan)));
    stages.put(
        CreditOriginationStageCode.RECOVERY,
        preferExplicit(explicit.get(CreditOriginationStageCode.RECOVERY), recoveryEvidence(loan)));

    return CreditOriginationBoardAssembler.assemble(
        loan.loanId(), loan.clientId(), prospect == null ? null : prospect.id(), stages);
  }

  private LoanLifecycle retrieveVisibleLoan(final Long loanId, final String officeHierarchy) {
    final List<LoanLifecycle> loans =
        jdbcTemplate.query(
            "SELECT l.id, l.client_id, l.loan_status_id, l.currency_code,"
                + " l.approvedon_date, l.approvedon_userid, approved.username approved_by,"
                + " l.disbursedon_date, l.disbursedon_userid, disbursed.username disbursed_by,"
                + " l.closedon_date, c.status_enum client_status, c.activation_date"
                + " FROM m_loan l"
                + " JOIN m_client c ON c.id = l.client_id"
                + " JOIN m_office o ON o.id = c.office_id"
                + " LEFT JOIN m_office transfer_o ON transfer_o.id = c.transfer_to_office_id"
                + " LEFT JOIN m_appuser approved ON approved.id = l.approvedon_userid"
                + " LEFT JOIN m_appuser disbursed ON disbursed.id = l.disbursedon_userid"
                + " WHERE l.id = :loanId"
                + " AND (o.hierarchy LIKE :officeHierarchy"
                + " OR transfer_o.hierarchy LIKE :officeHierarchy)",
            Map.of("loanId", loanId, "officeHierarchy", officeHierarchy),
            (rs, rowNumber) ->
                new LoanLifecycle(
                    rs.getLong("id"),
                    nullableLong(rs, "client_id"),
                    LoanStatus.fromInt(rs.getInt("loan_status_id")),
                    rs.getString("currency_code"),
                    localDate(rs, "approvedon_date"),
                    nullableLong(rs, "approvedon_userid"),
                    rs.getString("approved_by"),
                    localDate(rs, "disbursedon_date"),
                    nullableLong(rs, "disbursedon_userid"),
                    rs.getString("disbursed_by"),
                    localDate(rs, "closedon_date"),
                    ClientStatus.fromInt(rs.getInt("client_status")),
                    localDate(rs, "activation_date")));
    if (loans.isEmpty()) {
      // Office scoping intentionally makes an invisible application indistinguishable from absent.
      throw new LoanNotFoundException(loanId);
    }
    return loans.getFirst();
  }

  private ProspectRegistration retrieveLatestProspect(final Long clientId) {
    final List<ProspectRegistration> prospects =
        jdbcTemplate.query(
            "SELECT p.id, p.registration_status, p.last_modified_on_utc"
                + " FROM m_prospect_registration p WHERE p.client_id = :clientId"
                + " ORDER BY p.created_on_utc DESC, p.id DESC",
            Map.of("clientId", clientId),
            (rs, rowNumber) ->
                new ProspectRegistration(
                    rs.getLong("id"),
                    rs.getString("registration_status"),
                    offsetDateTime(rs, "last_modified_on_utc")));
    return prospects.isEmpty() ? null : prospects.getFirst();
  }

  private Map<CreditOriginationStageCode, StageEvidence> retrieveExplicitEvents(
      final Long loanId, final Long clientId) {
    final Map<CreditOriginationStageCode, StageEvidence> evidence =
        new EnumMap<>(CreditOriginationStageCode.class);
    jdbcTemplate.query(
        "SELECT e.stage_code, e.new_status, e.occurred_on_utc, e.actor_id, u.username,"
            + " e.source, e.source_reference, e.reason"
            + " FROM m_credit_origination_stage_event e"
            + " LEFT JOIN m_appuser u ON u.id = e.actor_id"
            + " WHERE e.loan_id = :loanId AND e.client_id = :clientId"
            + " ORDER BY e.occurred_on_utc DESC, e.id DESC",
        Map.of("loanId", loanId, "clientId", clientId),
        rs -> {
          final CreditOriginationStageCode code = parseStage(rs.getString("stage_code"));
          if (code != null && !evidence.containsKey(code)) {
            final String status = normalize(rs.getString("new_status"));
            evidence.put(
                code,
                new StageEvidence(
                    COMPLETED.equals(status),
                    COMPLETED.equals(status) ? offsetDateTime(rs, "occurred_on_utc") : null,
                    status,
                    new CreditOriginationStageDetailsData(
                        rs.getString("source"),
                        status,
                        nullableLong(rs, "actor_id"),
                        rs.getString("username"),
                        rs.getString("source_reference"),
                        rs.getString("reason"),
                        null,
                        null,
                        null,
                        null)));
          }
        });
    return evidence;
  }

  private StageEvidence onboardingEvidence(
      final LoanLifecycle loan, final ProspectRegistration prospect) {
    if (prospect != null) {
      final String status = normalize(prospect.status());
      return new StageEvidence(
          COMPLETED.equals(status),
          COMPLETED.equals(status) ? prospect.modifiedOn() : null,
          status,
          new CreditOriginationStageDetailsData(
              "m_prospect_registration.registration_status",
              status,
              null,
              null,
              String.valueOf(prospect.id()),
              null,
              null,
              null,
              null,
              loan.status().name()));
    }
    final String status = clientStatus(loan.clientStatus());
    return new StageEvidence(
        loan.clientStatus().isActive(),
        loan.clientStatus().isActive() ? atStartOfDay(loan.clientActivationDate()) : null,
        status,
        new CreditOriginationStageDetailsData(
            "m_client.status_enum,m_client.activation_date",
            status,
            null,
            null,
            String.valueOf(loan.clientId()),
            null,
            null,
            null,
            null,
            loan.status().name()));
  }

  private StageEvidence retrieveComplianceEvidence(final Long clientId) {
    final List<StageEvidence> results =
        jdbcTemplate.query(
            "SELECT v.id, v.kyc_status, v.kyc_timestamp, v.last_modified_on_utc,"
                + " d.decision_status"
                + " FROM m_client_kyc_verification v"
                + " LEFT JOIN m_client_kyc_decision d ON d.kyc_verification_id = v.id"
                + " WHERE v.client_id = :clientId"
                + " ORDER BY v.created_on_utc DESC, v.id DESC",
            Map.of("clientId", clientId),
            (rs, rowNumber) -> {
              final String rawStatus =
                  rs.getString("decision_status") == null
                      ? rs.getString("kyc_status")
                      : rs.getString("decision_status");
              final String status = complianceStatus(rawStatus);
              final OffsetDateTime occurred =
                  epochMillis(rs, "kyc_timestamp") == null
                      ? epochMillis(rs, "last_modified_on_utc")
                      : epochMillis(rs, "kyc_timestamp");
              return new StageEvidence(
                  COMPLETED.equals(status),
                  COMPLETED.equals(status) ? occurred : null,
                  status,
                  new CreditOriginationStageDetailsData(
                      "m_client_kyc_verification,m_client_kyc_decision",
                      rawStatus,
                      null,
                      null,
                      String.valueOf(rs.getLong("id")),
                      null,
                      null,
                      null,
                      null,
                      null));
            });
    return results.isEmpty() ? StageEvidence.missing() : results.getFirst();
  }

  private StageEvidence approvalEvidence(final LoanLifecycle loan) {
    final boolean completed =
        loan.approvedOn() != null
            || loan.status().isApproved()
            || loan.status().isActive()
            || loan.status().isClosed()
            || loan.status().isOverpaid();
    final String sourceStatus = terminalLoanStatus(loan.status());
    return new StageEvidence(
        completed,
        completed ? atStartOfDay(loan.approvedOn()) : null,
        sourceStatus,
        lifecycleDetails(
            "m_loan.loan_status_id,m_loan.approvedon_date",
            sourceStatus,
            loan.approvedById(),
            loan.approvedBy(),
            loan));
  }

  private StageEvidence disbursementEvidence(final LoanLifecycle loan) {
    final boolean completed = loan.disbursedOn() != null;
    return new StageEvidence(
        completed,
        completed ? atStartOfDay(loan.disbursedOn()) : null,
        terminalLoanStatus(loan.status()),
        lifecycleDetails(
            "m_loan.disbursedon_date",
            loan.status().name(),
            loan.disbursedById(),
            loan.disbursedBy(),
            loan));
  }

  private StageEvidence recoveryEvidence(final LoanLifecycle loan) {
    final List<Repayment> repayments =
        jdbcTemplate.query(
            "SELECT t.id, t.transaction_date, t.amount"
                + " FROM m_loan_transaction t"
                + " WHERE t.loan_id = :loanId AND t.is_reversed = :reversed"
                + " AND t.transaction_type_enum IN (:types)"
                + " ORDER BY t.transaction_date DESC, t.id DESC",
            Map.of(
                "loanId",
                loan.loanId(),
                "reversed",
                false,
                "types",
                LoanTransactionType.getRepaymentLikeTransactionTypes().stream()
                    .map(LoanTransactionType::getValue)
                    .toList()),
            (rs, rowNumber) ->
                new Repayment(
                    rs.getLong("id"),
                    localDate(rs, "transaction_date"),
                    rs.getBigDecimal("amount")));
    final Repayment latest = repayments.isEmpty() ? null : repayments.getFirst();
    final boolean completed = loan.status().isClosedObligationsMet() || loan.status().isOverpaid();
    final String status =
        loan.status().isClosedWrittenOff() ? "WRITTEN_OFF" : terminalLoanStatus(loan.status());
    return new StageEvidence(
        completed,
        completed ? atStartOfDay(loan.closedOn()) : null,
        status,
        new CreditOriginationStageDetailsData(
            "m_loan.loan_status_id,m_loan_transaction",
            status,
            null,
            null,
            latest == null ? null : String.valueOf(latest.id()),
            null,
            latest == null ? null : latest.id(),
            latest == null ? null : latest.amount(),
            loan.currencyCode(),
            loan.status().name()));
  }

  private static CreditOriginationStageDetailsData lifecycleDetails(
      final String source,
      final String sourceStatus,
      final Long actorId,
      final String actorName,
      final LoanLifecycle loan) {
    return new CreditOriginationStageDetailsData(
        source,
        sourceStatus,
        actorId,
        actorName,
        String.valueOf(loan.loanId()),
        null,
        null,
        null,
        loan.currencyCode(),
        loan.status().name());
  }

  private static StageEvidence preferExplicit(
      final StageEvidence explicit, final StageEvidence derived) {
    return explicit == null ? derived : explicit;
  }

  private static void putExplicit(
      final Map<CreditOriginationStageCode, StageEvidence> target,
      final Map<CreditOriginationStageCode, StageEvidence> explicit,
      final CreditOriginationStageCode code) {
    target.put(code, explicit.getOrDefault(code, StageEvidence.missing()));
  }

  private static CreditOriginationStageCode parseStage(final String value) {
    try {
      return CreditOriginationStageCode.valueOf(normalize(value));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private static String complianceStatus(final String value) {
    final String normalized = normalize(value).replace(' ', '_');
    return switch (normalized) {
      case "APPROVED", "COMPLETED" -> COMPLETED;
      case "DECLINED", "REJECTED" -> "REJECTED";
      case "FAILED" -> "FAILED";
      default -> normalized;
    };
  }

  private static String clientStatus(final ClientStatus status) {
    if (status.isRejected()) {
      return "REJECTED";
    }
    if (status.isWithdrawn()) {
      return "WITHDRAWN";
    }
    if (status.isClosed()) {
      return "CANCELLED";
    }
    return status.name();
  }

  private static String terminalLoanStatus(final LoanStatus status) {
    if (status.isRejected()) {
      return "REJECTED";
    }
    if (status.isWithdrawnByClient()) {
      return "WITHDRAWN";
    }
    return status.name();
  }

  private static String normalize(final String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private static Long nullableLong(final ResultSet rs, final String column) throws SQLException {
    final long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static LocalDate localDate(final ResultSet rs, final String column) throws SQLException {
    final Date value = rs.getDate(column);
    return value == null ? null : value.toLocalDate();
  }

  private static OffsetDateTime offsetDateTime(final ResultSet rs, final String column)
      throws SQLException {
    final Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
  }

  private static OffsetDateTime epochMillis(final ResultSet rs, final String column)
      throws SQLException {
    final long value = rs.getLong(column);
    return rs.wasNull() ? null : Instant.ofEpochMilli(value).atOffset(ZoneOffset.UTC);
  }

  private static OffsetDateTime atStartOfDay(final LocalDate value) {
    return value == null ? null : value.atStartOfDay().atOffset(ZoneOffset.UTC);
  }

  private record LoanLifecycle(
      Long loanId,
      Long clientId,
      LoanStatus status,
      String currencyCode,
      LocalDate approvedOn,
      Long approvedById,
      String approvedBy,
      LocalDate disbursedOn,
      Long disbursedById,
      String disbursedBy,
      LocalDate closedOn,
      ClientStatus clientStatus,
      LocalDate clientActivationDate) {}

  private record ProspectRegistration(Long id, String status, OffsetDateTime modifiedOn) {}

  private record Repayment(Long id, LocalDate transactionDate, BigDecimal amount) {}
}
