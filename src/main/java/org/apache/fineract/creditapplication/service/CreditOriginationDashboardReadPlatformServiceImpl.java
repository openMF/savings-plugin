/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import static org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.AGING_STALLED;
import static org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.AGING_UNKNOWN;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.creditapplication.data.CreditOriginationAgingData;
import org.apache.fineract.creditapplication.data.CreditOriginationBoardData;
import org.apache.fineract.creditapplication.data.CreditOriginationDashboardData;
import org.apache.fineract.creditapplication.data.CreditOriginationDashboardData.StageDistribution;
import org.apache.fineract.creditapplication.data.CreditOriginationDashboardData.StalledApplication;
import org.apache.fineract.creditapplication.data.CreditOriginationDashboardData.Summary;
import org.apache.fineract.creditapplication.data.CreditOriginationDashboardRequest;
import org.apache.fineract.creditapplication.data.CreditOriginationStageCode;
import org.apache.fineract.creditapplication.data.CreditOriginationStageData;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.EventHistory;
import org.apache.fineract.creditapplication.service.CreditOriginationBoardAssembler.EventRow;
import org.apache.fineract.creditapplication.service.CreditOriginationStageDerivation.ComplianceRecord;
import org.apache.fineract.creditapplication.service.CreditOriginationStageDerivation.LoanLifecycle;
import org.apache.fineract.creditapplication.service.CreditOriginationStageDerivation.ProspectRegistration;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Global origination dashboard. A single office-scoped query streams one row per stage event
 * (ordered by client, application, newest event first); each application is folded through the same
 * derivation and assembler as the individual board, so the two can never disagree. Only counters
 * and a heap bounded by offset + limit are retained in memory.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreditOriginationDashboardReadPlatformServiceImpl
    implements CreditOriginationDashboardReadPlatformService {

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;
  static final int MAX_OFFSET = 10_000;

  private static final String LOAN_RESOURCE = "LOAN";
  private static final List<Integer> ACTIVE_LOAN_STATUSES =
      Stream.of(
              LoanStatus.SUBMITTED_AND_PENDING_APPROVAL,
              LoanStatus.APPROVED,
              LoanStatus.ACTIVE,
              LoanStatus.TRANSFER_IN_PROGRESS,
              LoanStatus.TRANSFER_ON_HOLD)
          .map(LoanStatus::getValue)
          .toList();

  /** Most days first, then lowest application id, for a deterministic stalled ordering. */
  private static final Comparator<StalledApplication> STALLED_ORDER =
      Comparator.comparingLong(StalledApplication::daysInStage)
          .reversed()
          .thenComparing(StalledApplication::creditApplicationId);

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final PlatformSecurityContext context;

  @Override
  public CreditOriginationDashboardData retrieve(final CreditOriginationDashboardRequest request) {
    final AppUser user = context.authenticatedUser();
    user.validateHasReadPermission(LOAN_RESOURCE);
    final Criteria criteria = validate(request);
    final LocalDate businessDate = DateUtils.getBusinessLocalDate();

    final Map<String, Object> parameters = new HashMap<>();
    parameters.put("officeHierarchy", user.getOffice().getHierarchy() + "%");
    parameters.put("activeLoanStatuses", ACTIVE_LOAN_STATUSES);
    final StringBuilder filters = new StringBuilder();
    if (criteria.officeId() != null) {
      filters.append(" AND c.office_id = :officeId");
      parameters.put("officeId", criteria.officeId());
    }
    if (criteria.productId() != null) {
      filters.append(" AND l.product_id = :productId");
      parameters.put("productId", criteria.productId());
    }

    final Aggregator aggregator =
        new Aggregator(businessDate, criteria.offset() + criteria.limit());
    jdbcTemplate.query(sql(filters.toString()), parameters, aggregator::accept);
    aggregator.finish();
    return aggregator.result(criteria);
  }

  private static String sql(final String filters) {
    return "WITH app AS ("
        + " SELECT "
        + CreditOriginationStageDerivation.LOAN_COLUMNS
        + ", c.display_name AS client_name"
        + CreditOriginationStageDerivation.LOAN_JOINS
        + " WHERE l.loan_status_id IN (:activeLoanStatuses) AND "
        + CreditOriginationStageDerivation.OFFICE_SCOPE
        + filters
        + "), prospect AS ("
        + " SELECT p.id AS prospect_id, p.client_id, p.registration_status AS prospect_status,"
        + " p.created_on_utc AS prospect_created_on,"
        + " p.last_modified_on_utc AS prospect_modified_on,"
        + " ROW_NUMBER() OVER (PARTITION BY p.client_id"
        + " ORDER BY p.created_on_utc DESC, p.id DESC) AS rn"
        + " FROM m_prospect_registration p"
        + " WHERE p.client_id IN (SELECT a.client_id FROM app a)"
        + "), kyc AS ("
        + " SELECT v.id AS kyc_id, v.client_id, v.kyc_status,"
        + " d.decision_status AS kyc_decision_status, v.kyc_timestamp,"
        + " v.last_modified_on_utc AS kyc_modified_on,"
        + " ROW_NUMBER() OVER (PARTITION BY v.client_id"
        + " ORDER BY v.created_on_utc DESC, v.id DESC) AS rn"
        + " FROM m_client_kyc_verification v"
        + " LEFT JOIN m_client_kyc_decision d ON d.kyc_verification_id = v.id"
        + " WHERE v.client_id IN (SELECT a.client_id FROM app a)"
        + ")"
        + " SELECT app.*, pr.prospect_id, pr.prospect_status, pr.prospect_created_on,"
        + " pr.prospect_modified_on, k.kyc_id, k.kyc_status, k.kyc_decision_status,"
        + " k.kyc_timestamp, k.kyc_modified_on, "
        + CreditOriginationStageDerivation.EVENT_COLUMNS
        + " FROM app"
        + " LEFT JOIN prospect pr ON pr.client_id = app.client_id AND pr.rn = 1"
        + " LEFT JOIN kyc k ON k.client_id = app.client_id AND k.rn = 1"
        + " LEFT JOIN m_credit_origination_stage_event e"
        + " ON e.loan_id = app.loan_id AND e.client_id = app.client_id"
        + " LEFT JOIN m_appuser actor ON actor.id = e.actor_id"
        + " ORDER BY app.client_id, app.loan_id, "
        + CreditOriginationStageDerivation.EVENT_ORDER;
  }

  private static Criteria validate(final CreditOriginationDashboardRequest request) {
    final List<ApiParameterError> errors = new ArrayList<>();
    final Long officeId = request == null ? null : request.getOfficeId();
    final Long productId = request == null ? null : request.getProductId();
    final int offset = request == null || request.getOffset() == null ? 0 : request.getOffset();
    final int limit =
        request == null || request.getLimit() == null ? DEFAULT_LIMIT : request.getLimit();
    if (officeId != null && officeId <= 0) {
      addError(errors, "officeId", officeId, "Identifier must be greater than zero.");
    }
    if (productId != null && productId <= 0) {
      addError(errors, "productId", productId, "Identifier must be greater than zero.");
    }
    if (offset < 0 || offset > MAX_OFFSET) {
      addError(errors, "offset", offset, "Offset must be between 0 and " + MAX_OFFSET + ".");
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      addError(errors, "limit", limit, "Limit must be between 1 and " + MAX_LIMIT + ".");
    }
    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }
    return new Criteria(officeId, productId, offset, limit);
  }

  private static void addError(
      final List<ApiParameterError> errors,
      final String parameter,
      final Object value,
      final String message) {
    errors.add(
        ApiParameterError.parameterError(
            "validation.msg.creditOriginationDashboard." + parameter + ".invalid",
            message,
            parameter,
            value));
  }

  private record Criteria(Long officeId, Long productId, int offset, int limit) {}

  /** Folds the ordered row stream into dashboard counters without retaining applications. */
  private static final class Aggregator {

    private static final int STAGES = CreditOriginationStageCode.values().length;

    private final LocalDate businessDate;
    private final int retained;
    private final PriorityQueue<StalledApplication> stalled;

    private final long[] stageApplications = new long[STAGES];
    private final long[] stageProspects = new long[STAGES];
    private final long[] stageStalledApplications = new long[STAGES];
    private final long[] stageStalledProspects = new long[STAGES];
    private final long[] stageUnknownAging = new long[STAGES];
    private long activeApplications;
    private long activeProspects;
    private long stalledApplications;
    private long stalledProspects;
    private long unknownAgingApplications;
    private long scoringApplications;
    private long scoringProspects;

    private LoanLifecycle loan;
    private ProspectRegistration prospect;
    private ComplianceRecord compliance;
    private String clientName;
    private EventHistory events;

    private Long clientId;
    private final Set<CreditOriginationStageCode> clientStages =
        EnumSet.noneOf(CreditOriginationStageCode.class);
    private final Set<CreditOriginationStageCode> clientStalledStages =
        EnumSet.noneOf(CreditOriginationStageCode.class);
    private boolean clientActive;
    private boolean clientScoring;

    Aggregator(final LocalDate businessDate, final int retained) {
      this.businessDate = businessDate;
      this.retained = retained;
      // Head is the lowest-ranked retained entry, evicted first once the heap is full.
      this.stalled = new PriorityQueue<>(STALLED_ORDER.reversed());
    }

    void accept(final ResultSet rs) throws SQLException {
      final long loanId = rs.getLong("loan_id");
      if (loan == null || loan.loanId() != loanId) {
        flushApplication();
        loan = CreditOriginationStageDerivation.readLoan(rs);
        prospect = CreditOriginationStageDerivation.readProspect(rs);
        compliance = CreditOriginationStageDerivation.readCompliance(rs);
        clientName = rs.getString("client_name");
        events = new EventHistory();
        if (!Objects.equals(clientId, loan.clientId())) {
          flushClient();
          clientId = loan.clientId();
        }
      }
      final EventRow row = CreditOriginationStageDerivation.readEvent(rs);
      if (row != null) {
        events.accept(row);
      }
    }

    void finish() {
      flushApplication();
      flushClient();
    }

    CreditOriginationDashboardData result(final Criteria criteria) {
      final List<StageDistribution> stages = new ArrayList<>(STAGES);
      for (CreditOriginationStageCode code : CreditOriginationStageCode.values()) {
        final int index = code.ordinal();
        stages.add(
            new StageDistribution(
                code.name(),
                code.getDisplayName(),
                index + 1,
                stageProspects[index],
                stageApplications[index],
                stageStalledProspects[index],
                stageStalledApplications[index],
                stageUnknownAging[index],
                code.isEventsOnly() ? "EVENTS_ONLY" : "DERIVED"));
      }
      final List<StalledApplication> ranked = new ArrayList<>(stalled);
      ranked.sort(STALLED_ORDER);
      final List<StalledApplication> page =
          criteria.offset() >= ranked.size()
              ? List.of()
              : List.copyOf(ranked.subList(criteria.offset(), ranked.size()));
      return new CreditOriginationDashboardData(
          businessDate,
          CreditOriginationBoardAssembler.STALLED_THRESHOLD_DAYS,
          CreditOriginationBoardAssembler.AGING_UNIT,
          criteria.officeId(),
          criteria.productId(),
          new Summary(
              activeProspects,
              activeApplications,
              stalledProspects,
              stalledApplications,
              unknownAgingApplications,
              scoringProspects,
              scoringApplications,
              null,
              "UNDEFINED"),
          List.copyOf(stages),
          new Page<>(page, Math.toIntExact(stalledApplications)));
    }

    private void flushApplication() {
      if (loan == null) {
        return;
      }
      final CreditOriginationBoardData board =
          CreditOriginationBoardAssembler.assemble(
              CreditOriginationStageDerivation.context(loan, prospect),
              CreditOriginationStageDerivation.derive(loan, prospect, compliance, events, null),
              events,
              businessDate);
      classify(board);
      loan = null;
    }

    private void classify(final CreditOriginationBoardData board) {
      if (board.currentStage() == null) {
        return;
      }
      final CreditOriginationStageCode current =
          CreditOriginationStageCode.valueOf(board.currentStage());
      final CreditOriginationStageData stage = board.stages().get(current.ordinal());
      if (CreditOriginationBoardAssembler.INACTIVE_STATUSES.contains(stage.status())) {
        return;
      }
      final int index = current.ordinal();
      final CreditOriginationAgingData aging = board.currentStageAging();
      activeApplications++;
      stageApplications[index]++;
      clientActive = true;
      clientStages.add(current);
      if (current == CreditOriginationStageCode.PARAMETRIC_SCORE) {
        scoringApplications++;
        clientScoring = true;
      }
      if (AGING_UNKNOWN.equals(aging.status())) {
        unknownAgingApplications++;
        stageUnknownAging[index]++;
      } else if (AGING_STALLED.equals(aging.status())) {
        stalledApplications++;
        stageStalledApplications[index]++;
        clientStalledStages.add(current);
        retain(
            new StalledApplication(
                board.prospectId(),
                board.creditApplicationId(),
                board.clientId(),
                clientName,
                current.name(),
                stage.status(),
                aging.enteredOn(),
                aging.daysInStage(),
                board.responsibleStaffId(),
                board.responsibleStaffName(),
                board.officeId(),
                board.productId()));
      }
    }

    private void retain(final StalledApplication candidate) {
      stalled.offer(candidate);
      if (stalled.size() > retained) {
        stalled.poll();
      }
    }

    private void flushClient() {
      if (clientActive) {
        activeProspects++;
      }
      if (!clientStalledStages.isEmpty()) {
        stalledProspects++;
      }
      if (clientScoring) {
        scoringProspects++;
      }
      clientStages.forEach(code -> stageProspects[code.ordinal()]++);
      clientStalledStages.forEach(code -> stageStalledProspects[code.ordinal()]++);
      clientStages.clear();
      clientStalledStages.clear();
      clientActive = false;
      clientScoring = false;
    }
  }
}
