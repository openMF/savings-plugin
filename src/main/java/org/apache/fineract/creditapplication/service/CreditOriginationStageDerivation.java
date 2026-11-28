/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import static org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.COMPLETED;
import static org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.normalize;

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
import java.util.Map;
import org.apache.fineract.creditapplication.data.CreditOriginationStageCode;
import org.apache.fineract.creditapplication.data.CreditOriginationStageDetailsData;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.ApplicationContext;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.EventHistory;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.EventRow;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.StageEvidence;
import org.apache.fineract.portfolio.client.domain.ClientStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;

/**
 * Maps authoritative Fineract, prospect, KYC and stage-event records to phase evidence. Both the
 * individual board and the dashboard read rows through these column aliases, so every consumer
 * derives a phase identically.
 */
final class CreditOriginationStageDerivation {

  /** Loan/client/staff columns shared by the board and dashboard queries. */
  static final String LOAN_COLUMNS =
      "l.id AS loan_id, l.client_id, l.product_id, c.office_id, l.loan_status_id,"
          + " l.currency_code, l.approvedon_date, l.approvedon_userid,"
          + " approved.username AS approved_by, l.disbursedon_date, l.disbursedon_userid,"
          + " disbursed.username AS disbursed_by, l.closedon_date,"
          + " c.status_enum AS client_status, c.activation_date,"
          + " l.loan_officer_id, staff.display_name AS loan_officer_name";

  /** Joins required by {@link #LOAN_COLUMNS} plus the office-hierarchy scope predicate. */
  static final String LOAN_JOINS =
      " FROM m_loan l"
          + " JOIN m_client c ON c.id = l.client_id"
          + " JOIN m_office o ON o.id = c.office_id"
          + " LEFT JOIN m_office transfer_o ON transfer_o.id = c.transfer_to_office_id"
          + " LEFT JOIN m_appuser approved ON approved.id = l.approvedon_userid"
          + " LEFT JOIN m_appuser disbursed ON disbursed.id = l.disbursedon_userid"
          + " LEFT JOIN m_staff staff ON staff.id = l.loan_officer_id";

  static final String OFFICE_SCOPE =
      "(o.hierarchy LIKE :officeHierarchy OR transfer_o.hierarchy LIKE :officeHierarchy)";

  static final String EVENT_COLUMNS =
      "e.stage_code AS event_stage_code, e.component_code AS event_component_code,"
          + " e.new_status AS event_status, e.occurred_on_utc AS event_occurred_on,"
          + " e.recorded_on_utc AS event_recorded_on, e.actor_id AS event_actor_id,"
          + " actor.username AS event_actor_name, e.source AS event_source,"
          + " e.source_reference AS event_source_reference, e.reason AS event_reason";

  /** Deterministic newest-first order required by {@link EventHistory}. */
  static final String EVENT_ORDER = "e.occurred_on_utc DESC, e.id DESC";

  private CreditOriginationStageDerivation() {}

  static Map<CreditOriginationStageCode, StageEvidence> derive(
      final LoanLifecycle loan,
      final ProspectRegistration prospect,
      final ComplianceRecord compliance,
      final EventHistory events,
      final Repayment latestRepayment) {
    final Map<CreditOriginationStageCode, StageEvidence> stages =
        new EnumMap<>(CreditOriginationStageCode.class);
    for (CreditOriginationStageCode code : CreditOriginationStageCode.values()) {
      final StageEvidence explicit = events.latest(code);
      stages.put(
          code,
          explicit == null ? derived(code, loan, prospect, compliance, latestRepayment) : explicit);
    }
    return stages;
  }

  static ApplicationContext context(final LoanLifecycle loan, final ProspectRegistration prospect) {
    return new ApplicationContext(
        loan.loanId(),
        loan.clientId(),
        prospect == null ? null : prospect.id(),
        prospect == null ? null : prospect.createdOn(),
        loan.officeId(),
        loan.productId(),
        loan.loanOfficerId(),
        loan.loanOfficerName());
  }

  static LoanLifecycle readLoan(final ResultSet rs) throws SQLException {
    return new LoanLifecycle(
        rs.getLong("loan_id"),
        nullableLong(rs, "client_id"),
        nullableLong(rs, "product_id"),
        nullableLong(rs, "office_id"),
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
        localDate(rs, "activation_date"),
        nullableLong(rs, "loan_officer_id"),
        rs.getString("loan_officer_name"));
  }

  /** Reads prospect_id, prospect_status, prospect_created_on and prospect_modified_on. */
  static ProspectRegistration readProspect(final ResultSet rs) throws SQLException {
    final Long id = nullableLong(rs, "prospect_id");
    return id == null
        ? null
        : new ProspectRegistration(
            id,
            rs.getString("prospect_status"),
            offsetDateTime(rs, "prospect_created_on"),
            offsetDateTime(rs, "prospect_modified_on"));
  }

  /** Reads kyc_id, kyc_status, kyc_decision_status, kyc_timestamp and kyc_modified_on. */
  static ComplianceRecord readCompliance(final ResultSet rs) throws SQLException {
    final Long id = nullableLong(rs, "kyc_id");
    return id == null
        ? null
        : new ComplianceRecord(
            id,
            rs.getString("kyc_status"),
            rs.getString("kyc_decision_status"),
            epochMillis(rs, "kyc_timestamp"),
            epochMillis(rs, "kyc_modified_on"));
  }

  /** Reads {@link #EVENT_COLUMNS}; returns null for the empty side of an outer join. */
  static EventRow readEvent(final ResultSet rs) throws SQLException {
    final String stage = rs.getString("event_stage_code");
    return stage == null
        ? null
        : new EventRow(
            stage,
            rs.getString("event_component_code"),
            rs.getString("event_status"),
            offsetDateTime(rs, "event_occurred_on"),
            offsetDateTime(rs, "event_recorded_on"),
            nullableLong(rs, "event_actor_id"),
            rs.getString("event_actor_name"),
            rs.getString("event_source"),
            rs.getString("event_source_reference"),
            rs.getString("event_reason"));
  }

  static Long nullableLong(final ResultSet rs, final String column) throws SQLException {
    final long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  static LocalDate localDate(final ResultSet rs, final String column) throws SQLException {
    final Date value = rs.getDate(column);
    return value == null ? null : value.toLocalDate();
  }

  static OffsetDateTime offsetDateTime(final ResultSet rs, final String column)
      throws SQLException {
    // *_utc columns hold UTC wall-clock values; never reinterpret them in the JVM time zone.
    final Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toLocalDateTime().atOffset(ZoneOffset.UTC);
  }

  private static StageEvidence derived(
      final CreditOriginationStageCode code,
      final LoanLifecycle loan,
      final ProspectRegistration prospect,
      final ComplianceRecord compliance,
      final Repayment latestRepayment) {
    return switch (code) {
      case ONBOARDING -> onboardingEvidence(loan, prospect);
      case COMPLIANCE -> complianceEvidence(compliance);
      case APPROVAL -> approvalEvidence(loan);
      case DISBURSEMENT -> disbursementEvidence(loan);
      case RECOVERY -> recoveryEvidence(loan, latestRepayment);
      // Event-only phases have no authoritative Fineract fallback and stay unknown.
      default -> StageEvidence.missing();
    };
  }

  private static StageEvidence onboardingEvidence(
      final LoanLifecycle loan, final ProspectRegistration prospect) {
    if (prospect != null) {
      final String status = normalize(prospect.status());
      final boolean completed = COMPLETED.equals(status);
      return new StageEvidence(
          completed,
          completed ? prospect.modifiedOn() : null,
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
              loan.status().name()),
          prospect.modifiedOn());
    }
    final String status = clientStatus(loan.clientStatus());
    final OffsetDateTime activatedOn = atStartOfDay(loan.clientActivationDate());
    return new StageEvidence(
        loan.clientStatus().isActive(),
        loan.clientStatus().isActive() ? activatedOn : null,
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
            loan.status().name()),
        activatedOn);
  }

  private static StageEvidence complianceEvidence(final ComplianceRecord compliance) {
    if (compliance == null) {
      return StageEvidence.missing();
    }
    final String rawStatus =
        compliance.decisionStatus() == null ? compliance.kycStatus() : compliance.decisionStatus();
    final String status = complianceStatus(rawStatus);
    final OffsetDateTime occurred =
        compliance.kycTimestamp() == null ? compliance.modifiedOn() : compliance.kycTimestamp();
    return new StageEvidence(
        COMPLETED.equals(status),
        COMPLETED.equals(status) ? occurred : null,
        status,
        new CreditOriginationStageDetailsData(
            "m_client_kyc_verification,m_client_kyc_decision",
            rawStatus,
            null,
            null,
            String.valueOf(compliance.id()),
            null,
            null,
            null,
            null,
            null),
        occurred);
  }

  private static StageEvidence approvalEvidence(final LoanLifecycle loan) {
    final boolean completed =
        loan.approvedOn() != null
            || loan.status().isApproved()
            || loan.status().isActive()
            || loan.status().isClosed()
            || loan.status().isOverpaid();
    final String sourceStatus = terminalLoanStatus(loan.status());
    final OffsetDateTime approvedOn = atStartOfDay(loan.approvedOn());
    return new StageEvidence(
        completed,
        completed ? approvedOn : null,
        sourceStatus,
        lifecycleDetails(
            "m_loan.loan_status_id,m_loan.approvedon_date",
            sourceStatus,
            loan.approvedById(),
            loan.approvedBy(),
            loan),
        approvedOn);
  }

  private static StageEvidence disbursementEvidence(final LoanLifecycle loan) {
    final boolean completed = loan.disbursedOn() != null;
    final OffsetDateTime disbursedOn = atStartOfDay(loan.disbursedOn());
    return new StageEvidence(
        completed,
        completed ? disbursedOn : null,
        terminalLoanStatus(loan.status()),
        lifecycleDetails(
            "m_loan.disbursedon_date",
            loan.status().name(),
            loan.disbursedById(),
            loan.disbursedBy(),
            loan),
        disbursedOn);
  }

  private static StageEvidence recoveryEvidence(final LoanLifecycle loan, final Repayment latest) {
    final boolean completed = loan.status().isClosedObligationsMet() || loan.status().isOverpaid();
    final String status =
        loan.status().isClosedWrittenOff() ? "WRITTEN_OFF" : terminalLoanStatus(loan.status());
    final OffsetDateTime closedOn = atStartOfDay(loan.closedOn());
    final OffsetDateTime lastUpdated =
        closedOn != null
            ? closedOn
            : latest == null ? null : atStartOfDay(latest.transactionDate());
    return new StageEvidence(
        completed,
        completed ? closedOn : null,
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
            loan.status().name()),
        lastUpdated);
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

  private static OffsetDateTime epochMillis(final ResultSet rs, final String column)
      throws SQLException {
    final long value = rs.getLong(column);
    return rs.wasNull() ? null : Instant.ofEpochMilli(value).atOffset(ZoneOffset.UTC);
  }

  private static OffsetDateTime atStartOfDay(final LocalDate value) {
    return value == null ? null : value.atStartOfDay().atOffset(ZoneOffset.UTC);
  }

  record LoanLifecycle(
      Long loanId,
      Long clientId,
      Long productId,
      Long officeId,
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
      LocalDate clientActivationDate,
      Long loanOfficerId,
      String loanOfficerName) {}

  record ProspectRegistration(
      Long id, String status, OffsetDateTime createdOn, OffsetDateTime modifiedOn) {}

  record ComplianceRecord(
      Long id,
      String kycStatus,
      String decisionStatus,
      OffsetDateTime kycTimestamp,
      OffsetDateTime modifiedOn) {}

  record Repayment(Long id, LocalDate transactionDate, BigDecimal amount) {}
}
