/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentProductValidation.invalid;
import static org.apache.fineract.investment.InvestmentSimulationData.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Authorized gross projection orchestration; the only writes are isolated audit inserts. */
@Service
public class InvestmentSimulationService {
  public static final String READ = "READ_INVESTMENT_SIMULATION";
  public static final String CREATE = "CREATE_INVESTMENT_SIMULATION";
  public static final String AUDIT = "READ_INVESTMENT_SIMULATION_AUDIT";
  private final PlatformSecurityContext security;
  private final InvestmentSimulationAccess access;
  private final InvestmentRateService rates;
  private final InvestmentRateTime time;
  private final InvestmentSimulationValidation validation;
  private final InvestmentSimulationCalculator calculator;
  private final InvestmentSimulationAuditService audit;
  private final PlatformTransactionManager transactions;

  public InvestmentSimulationService(
      PlatformSecurityContext security,
      InvestmentSimulationAccess access,
      InvestmentRateService rates,
      InvestmentRateTime time,
      InvestmentSimulationValidation validation,
      InvestmentSimulationCalculator calculator,
      InvestmentSimulationAuditService audit,
      @Qualifier("jdbcTransactionManager") PlatformTransactionManager transactions) {
    this.security = security;
    this.access = access;
    this.rates = rates;
    this.time = time;
    this.validation = validation;
    this.calculator = calculator;
    this.audit = audit;
    this.transactions = transactions;
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_INVESTMENT_SIMULATION')")
  public Template template() {
    authorize(READ);
    return new Template(
        CREATE,
        READ,
        AUDIT,
        MAX_REINVESTMENTS,
        MAX_HORIZON_DAYS,
        MAX_SCHEDULE_PERIODS,
        List.of(360, 365),
        List.of(4, 7),
        List.of(4, 7),
        List.of(
            "NATIVE_CALCULATION_TYPE",
            "NATIVE_DAY_BASIS",
            "NATIVE_COMPOUNDING_AND_POSTING",
            "INITIALIZED_TENANT_ROUNDING",
            "TENANT_TIMEZONE",
            "NATIVE_FINANCIAL_YEAR_AND_POSTING_DATE"),
        List.of(
            "NON_BINDING_GROSS_ESTIMATE",
            "PRODUCT_POLICY_DRAFT_ONLY",
            "NO_FINANCIAL_EXECUTION",
            "NO_CUSTOMER_ASSOCIATION",
            "FUNDS_VERIFICATION_UNAVAILABLE",
            "DAILY_COMPOUNDING_BLOCKED_FLOATING_POINT",
            "QUARTERLY_AND_BIANNUAL_CONVENTIONS_UNVERIFIED",
            "RENEWALS_REQUIRE_EXPLICIT_FUTURE_PUBLISHED_PRICING",
            "HISTORY_SCOPED_TO_AUTHENTICATED_ACTOR"),
        capabilities());
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','CREATE_INVESTMENT_SIMULATION')")
  @Transactional(
      transactionManager = "jdbcTransactionManager",
      propagation = Propagation.NOT_SUPPORTED)
  public Result validate(String json) {
    long actor = authorize(CREATE);
    return snapshot(validation.parse(json), actor, null);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','CREATE_INVESTMENT_SIMULATION')")
  @Transactional(
      transactionManager = "jdbcTransactionManager",
      propagation = Propagation.NOT_SUPPORTED)
  public Result create(String json) {
    long actor = authorize(CREATE);
    var request = validation.parse(json);
    String tenant = tenant();
    var previous = audit.byKey(actor, tenant, request);
    if (previous != null) return previous;
    var result = snapshot(request, actor, UUID.randomUUID().toString());
    try {
      // A separate write transaction is required: financially read-only is not audit-write-free.
      return audit.store(result);
    } catch (DuplicateKeyException e) {
      var winner = audit.byKey(actor, tenant, request);
      if (winner == null) throw e;
      return winner;
    }
  }

  private Result snapshot(Request request, long actor, String id) {
    var read = new TransactionTemplate(transactions);
    read.setReadOnly(true);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    // Release the configuration connection before any audit transaction starts. Otherwise
    // concurrent requests could consume the entire pool while waiting for second connections.
    return read.execute(status -> project(request, actor, id));
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_INVESTMENT_SIMULATION')")
  public Result get(String id) {
    long actor = authorize(READ);
    if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      throw invalid("simulationId", "A canonical simulation UUID is required.");
    return audit.get(id, actor, tenant());
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_INVESTMENT_SIMULATION_AUDIT')")
  public List<HistoryEntry> history(Long product, int offset, int limit) {
    long actor = authorize(AUDIT);
    if (offset < 0 || offset > 10000 || limit < 1 || limit > 100)
      throw invalid("pagination", "Offset must be 0–10000 and limit 1–100.");
    if (product != null && product < 1)
      throw invalid("productId", "A positive product ID is required.");
    return audit.history(actor, product, offset, limit);
  }

  private Result project(Request request, long actor, String id) {
    var now = time.now();
    var product = access.product(request.productId());
    var policy = access.policy(request.productId());
    validation.eligible(request, policy.policy(), product);
    var settings = access.settings(request.productId());
    var convention = calculator.convention(settings, product, request.interestPayoutMode());
    var blockers = new ArrayList<>(calculator.blockers(settings, request.interestPayoutMode()));
    var warnings =
        new ArrayList<>(
            List.of(
                "Gross projection only. Actual taxes, fees, account configuration and settlement rules may affect the final amount.",
                "NON_BINDING_ESTIMATE_NOT_A_CONTRACTUAL_RATE_OR_SETTLEMENT_COMMITMENT",
                "PRODUCT_POLICY_DRAFT_ONLY",
                "NATIVE_RATE_CHART_INTEGRATION_UNVERIFIED",
                "PUBLISHED_RATE_SCHEDULE_SNAPSHOT_MAY_CHANGE",
                "DATES_UNADJUSTED_FOR_WEEKENDS_AND_HOLIDAYS"));
    var funding =
        new FundingAssessment(
            request.fundingContext().mode(),
            request.fundingContext().mode() == FundingMode.QUOTE_ONLY
                ? "NOT_CHECKED_QUOTE_ONLY"
                : "VERIFICATION_UNAVAILABLE_QUOTE_PRESERVED",
            false,
            false);
    if (request.fundingContext().mode() == FundingMode.VERIFY_AVAILABLE_FUNDS)
      warnings.add("AVAILABLE_FUNDS_VERIFICATION_UNAVAILABLE");
    String zone = time.timezone();
    if (zone == null) blockers.add("TENANT_TIMEZONE_MISSING_OR_INVALID");
    var start = LocalDate.parse(request.startDate());
    // The quote instant prices today's start; future/historical starts use unambiguous tenant
    // midnight, so an intraday publication never applies retroactively to that date's midnight.
    Instant pricing = null;
    if (zone != null) {
      var zoneId = ZoneId.of(zone);
      if (zoneId.getRules().getValidOffsets(start.atStartOfDay()).size() != 1)
        blockers.add("START_DATE_MIDNIGHT_AMBIGUOUS_OR_NONEXISTENT");
      else
        pricing =
            start.equals(now.atZone(zoneId).toLocalDate())
                ? now
                : start.atStartOfDay(zoneId).toInstant();
    }
    var cycles = new ArrayList<Cycle>();
    BigDecimal principal = request.investmentAmount();
    for (int cycle = 0; cycle <= request.requestedReinvestmentCount(); cycle++) {
      var cycleBlockers = new ArrayList<>(blockers);
      InvestmentRateData.Configuration published =
          pricing == null ? null : rates.applicable(request.productId(), pricing);
      InvestmentRateData.Selection selection = null;
      if (published == null) cycleBlockers.add("NO_PUBLISHED_RATE_AT_REQUESTED_INSTANT");
      else {
        if (published.policyRevision() != policy.revision())
          cycleBlockers.add("PUBLISHED_RATE_POLICY_REVISION_DIFFERS_FROM_CURRENT_POLICY");
        try {
          selection =
              InvestmentRateResolver.select(
                  request.productId(), published, principal, request.termDays(), pricing);
          if (selection.status() == InvestmentRateData.SelectionStatus.NO_APPLICABLE_RATE)
            cycleBlockers.add("NO_APPLICABLE_PUBLISHED_RATE");
        } catch (PlatformApiDataValidationException e) {
          cycleBlockers.add("PUBLISHED_RATE_SNAPSHOT_INCOMPATIBLE_WITH_REQUEST");
        }
        // BE-02's effective versions have no expiration. Conservatively require an explicitly
        // scheduled future publication for renewals; do not turn today's rate into a forecast.
        if (cycle > 0
            && pricing.isAfter(now)
            && !Instant.parse(published.effectiveInstant()).isAfter(now))
          cycleBlockers.add("FUTURE_RENEWAL_RATE_NOT_EXPLICITLY_PUBLISHED");
      }
      if (cycle > 0) {
        try {
          InvestmentSimulationValidation.amount(principal, policy.policy(), product);
        } catch (PlatformApiDataValidationException e) {
          cycleBlockers.add("RENEWAL_PRINCIPAL_OUTSIDE_PRODUCT_LIMITS_OR_PRECISION");
        }
      }
      var maturity = start.plusDays(request.termDays());
      if (!cycleBlockers.isEmpty()) {
        Status status =
            published == null && pricing != null
                ? Status.NO_APPLICABLE_RATE
                : Status.CALCULATION_BLOCKED;
        cycles.add(
            new Cycle(
                cycle + 1,
                start.toString(),
                maturity.toString(),
                principal,
                published,
                selection,
                status,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.copyOf(cycleBlockers)));
        blockers = cycleBlockers;
        break;
      }
      try {
        var calculated =
            calculator.calculate(
                cycle + 1,
                start,
                request.termDays(),
                principal,
                product,
                settings,
                request.interestPayoutMode(),
                cycle < request.requestedReinvestmentCount(),
                published,
                selection);
        cycles.add(calculated);
        principal = calculated.nextPrincipal();
      } catch (InvestmentSimulationCalculator.RoundingChanged e) {
        blockers.add("TENANT_ROUNDING_CHANGED_DURING_PROJECTION_RETRY");
        cycles.add(
            new Cycle(
                cycle + 1,
                start.toString(),
                maturity.toString(),
                principal,
                published,
                selection,
                Status.CALCULATION_BLOCKED,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.copyOf(blockers)));
        break;
      }
      start = maturity;
      if (cycle < request.requestedReinvestmentCount()) {
        var zoneId = ZoneId.of(zone);
        if (zoneId.getRules().getValidOffsets(start.atStartOfDay()).size() != 1) {
          blockers.add("RENEWAL_MIDNIGHT_AMBIGUOUS_OR_NONEXISTENT");
          pricing = null;
        } else pricing = start.atStartOfDay(zoneId).toInstant();
      }
    }
    long completed =
        cycles.stream().filter(c -> c.status() == Status.COMPLETE_GROSS_PROJECTION).count();
    Status status =
        blockers.isEmpty() && completed == request.requestedReinvestmentCount() + 1
            ? Status.COMPLETE_GROSS_PROJECTION
            : completed > 0
                ? Status.PARTIAL_PROJECTION
                : cycles.isEmpty() ? Status.CALCULATION_BLOCKED : cycles.getFirst().status();
    boolean complete = status == Status.COMPLETE_GROSS_PROJECTION;
    BigDecimal gross =
        complete
            ? cycles.stream().map(Cycle::grossInterest).reduce(BigDecimal.ZERO, BigDecimal::add)
            : null;
    return new Result(
        id,
        status,
        request,
        policy.policy().investmentType(),
        product.currencyCode(),
        policy.revision(),
        policy.policy(),
        product,
        convention,
        List.copyOf(cycles),
        gross,
        complete ? cycles.getLast().projectedMaturityValue() : null,
        complete ? cycles.getLast().maturityDate() : null,
        funding,
        List.copyOf(warnings),
        List.copyOf(blockers),
        capabilities(),
        actor,
        tenant(),
        Objects.toString(ThreadLocalContextUtil.getBusinessDate(), null),
        now.toString());
  }

  private long authorize(String permission) {
    var user = security.authenticatedUser();
    user.validateHasPermissionTo(permission);
    tenant();
    if (user.getId() == null || user.getId() < 1)
      throw new AccessDeniedException("Authenticated actor required.");
    return user.getId();
  }

  private String tenant() {
    var tenant = ThreadLocalContextUtil.getTenant();
    if (tenant == null
        || tenant.getTenantIdentifier() == null
        || tenant.getTenantIdentifier().isBlank())
      throw new AccessDeniedException("Authenticated tenant required.");
    return tenant.getTenantIdentifier();
  }

  public static final class Conflict extends RuntimeException {
    Conflict() {
      super("REQUEST_KEY_REUSED_WITH_DIFFERENT_INPUT");
    }
  }

  public static final class NotFound extends AbstractPlatformResourceNotFoundException {
    NotFound() {
      super("error.msg.investment.simulation.not.found", "Investment simulation not found.");
    }
  }
}
