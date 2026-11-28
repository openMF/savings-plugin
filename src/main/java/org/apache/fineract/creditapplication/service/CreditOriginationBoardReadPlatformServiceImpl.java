/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import static org.apache.fineract.creditapplication.service.CreditOriginationStageDerivation.localDate;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.creditapplication.data.CreditOriginationBoardData;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.EventHistory;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.EventRow;
import org.apache.fineract.creditapplication.service.CreditOriginationStageDerivation.ComplianceRecord;
import org.apache.fineract.creditapplication.service.CreditOriginationStageDerivation.LoanLifecycle;
import org.apache.fineract.creditapplication.service.CreditOriginationStageDerivation.ProspectRegistration;
import org.apache.fineract.creditapplication.service.CreditOriginationStageDerivation.Repayment;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
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
    final EventHistory events = retrieveEventHistory(loan.loanId(), loan.clientId());
    return CreditOriginationBoardAssembler.assemble(
        CreditOriginationStageDerivation.context(loan, prospect),
        CreditOriginationStageDerivation.derive(
            loan,
            prospect,
            retrieveLatestCompliance(loan.clientId()),
            events,
            retrieveLatestRepayment(loan.loanId())),
        events,
        DateUtils.getBusinessLocalDate());
  }

  private LoanLifecycle retrieveVisibleLoan(final Long loanId, final String officeHierarchy) {
    final List<LoanLifecycle> loans =
        jdbcTemplate.query(
            "SELECT "
                + CreditOriginationStageDerivation.LOAN_COLUMNS
                + CreditOriginationStageDerivation.LOAN_JOINS
                + " WHERE l.id = :loanId AND "
                + CreditOriginationStageDerivation.OFFICE_SCOPE,
            Map.of("loanId", loanId, "officeHierarchy", officeHierarchy),
            (rs, rowNumber) -> CreditOriginationStageDerivation.readLoan(rs));
    if (loans.isEmpty()) {
      // Office scoping intentionally makes an invisible application indistinguishable from absent.
      throw new LoanNotFoundException(loanId);
    }
    return loans.getFirst();
  }

  private ProspectRegistration retrieveLatestProspect(final Long clientId) {
    final List<ProspectRegistration> prospects =
        jdbcTemplate.query(
            "SELECT p.id AS prospect_id, p.registration_status AS prospect_status,"
                + " p.created_on_utc AS prospect_created_on,"
                + " p.last_modified_on_utc AS prospect_modified_on"
                + " FROM m_prospect_registration p WHERE p.client_id = :clientId"
                + " ORDER BY p.created_on_utc DESC, p.id DESC",
            Map.of("clientId", clientId),
            (rs, rowNumber) -> CreditOriginationStageDerivation.readProspect(rs));
    return prospects.isEmpty() ? null : prospects.getFirst();
  }

  private ComplianceRecord retrieveLatestCompliance(final Long clientId) {
    final List<ComplianceRecord> results =
        jdbcTemplate.query(
            "SELECT v.id AS kyc_id, v.kyc_status, d.decision_status AS kyc_decision_status,"
                + " v.kyc_timestamp, v.last_modified_on_utc AS kyc_modified_on"
                + " FROM m_client_kyc_verification v"
                + " LEFT JOIN m_client_kyc_decision d ON d.kyc_verification_id = v.id"
                + " WHERE v.client_id = :clientId"
                + " ORDER BY v.created_on_utc DESC, v.id DESC",
            Map.of("clientId", clientId),
            (rs, rowNumber) -> CreditOriginationStageDerivation.readCompliance(rs));
    return results.isEmpty() ? null : results.getFirst();
  }

  private EventHistory retrieveEventHistory(final Long loanId, final Long clientId) {
    final EventHistory history = new EventHistory();
    jdbcTemplate.query(
        "SELECT "
            + CreditOriginationStageDerivation.EVENT_COLUMNS
            + " FROM m_credit_origination_stage_event e"
            + " LEFT JOIN m_appuser actor ON actor.id = e.actor_id"
            + " WHERE e.loan_id = :loanId AND e.client_id = :clientId"
            + " ORDER BY "
            + CreditOriginationStageDerivation.EVENT_ORDER,
        Map.of("loanId", loanId, "clientId", clientId),
        rs -> {
          final EventRow row = CreditOriginationStageDerivation.readEvent(rs);
          if (row != null) {
            history.accept(row);
          }
        });
    return history;
  }

  private Repayment retrieveLatestRepayment(final Long loanId) {
    final List<Repayment> repayments =
        jdbcTemplate.query(
            "SELECT t.id, t.transaction_date, t.amount"
                + " FROM m_loan_transaction t"
                + " WHERE t.loan_id = :loanId AND t.is_reversed = :reversed"
                + " AND t.transaction_type_enum IN (:types)"
                + " ORDER BY t.transaction_date DESC, t.id DESC",
            Map.of(
                "loanId",
                loanId,
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
    return repayments.isEmpty() ? null : repayments.getFirst();
  }
}
