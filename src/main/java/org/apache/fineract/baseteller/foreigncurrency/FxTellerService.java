package org.apache.fineract.baseteller.foreigncurrency;

import static org.apache.fineract.baseteller.foreigncurrency.FxValidation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.Cashier;
import org.apache.fineract.baseteller.service.CashExchangeInventory;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.domain.ClientIdentifierStatus;
import org.apache.fineract.portfolio.client.domain.ClientStatus;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only evidence for a proposed cash trade. There is deliberately no financial write path. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, transactionManager = "jdbcTransactionManager")
public class FxTellerService {
  private final PlatformSecurityContext security;
  private final JdbcTemplate jdbc;
  private final FxRateService rates;
  private final CashExchangeInventory inventory;
  private final ClientReadPlatformService clients;

  @PreAuthorize("hasAuthority('ALL_FUNCTIONS') or hasAuthority('READ_BASE_TELLER_FX')")
  public FxData.TellerContext context() {
    AppUser actor = user("READ_" + TELLER);
    LocalDate date = DateUtils.getBusinessLocalDate();
    var drawer = cashier(actor, date);
    var permissions = new ArrayList<>(List.of("READ_" + TELLER));
    for (var op : FxData.Operation.values())
      if (actor.hasAnyPermission(permission(op))) permissions.add(permission(op));
    return new FxData.TellerContext(
        actor.getId(),
        date.toString(),
        drawer,
        rates.currencies(),
        null,
        rates.currentDrafts(drawer.officeId(), date, actor),
        List.copyOf(permissions),
        capabilities());
  }

  @PreAuthorize("hasAuthority('ALL_FUNCTIONS') or hasAuthority('READ_BASE_TELLER_FX')")
  public Page<FxData.Customer> customers(
      String number, String name, Integer offset, Integer limit) {
    AppUser actor = user("READ_" + TELLER);
    actor.validateHasReadPermission("CLIENT");
    cashier(actor, DateUtils.getBusinessLocalDate());
    int start = offset == null ? 0 : offset, size = limit == null ? 25 : limit;
    if (start < 0
        || size < 1
        || size > 100
        || (number != null && number.length() > 100)
        || (name != null && name.length() > 100)
        || ((number == null || number.isBlank()) && (name == null || name.isBlank())))
      throw invalid(
          "search.invalid", "Supply a customer number or name, offset >= 0 and limit 1-100.");
    if (number != null && !number.isBlank()) {
      var rows =
          jdbc.query(
              "SELECT c.id,c.account_no,c.display_name,c.office_id,c.status_enum FROM m_client c"
                  + " JOIN m_office o ON o.id=c.office_id WHERE c.account_no=? AND o.hierarchy LIKE"
                  + " ?",
              (rs, i) ->
                  new FxData.Customer(
                      rs.getLong(1),
                      rs.getString(2),
                      rs.getString(3),
                      rs.getLong(4),
                      rs.getInt(5) == ClientStatus.ACTIVE.getValue()),
              number.trim(),
              FxRateService.hierarchy(actor));
      return new Page<>(start == 0 ? rows : List.of(), rows.size());
    }
    // Native Fineract search applies the authenticated office scope and search conventions.
    var found =
        clients.retrieveAll(
            SearchParameters.builder()
                .name(name)
                .offset(start)
                .limit(size)
                .orderBy("id")
                .sortOrder("ASC")
                .build());
    var rows = found.getPageItems().stream().map(c -> customer(c.getId(), actor)).toList();
    return new Page<>(rows, found.getTotalFilteredRecords());
  }

  @PreAuthorize("hasAuthority('ALL_FUNCTIONS') or hasAuthority('READ_BASE_TELLER_FX')")
  public List<FxData.Denomination> denominations(String code) {
    AppUser actor = user("READ_" + TELLER);
    var date = DateUtils.getBusinessLocalDate();
    return available(cashier(actor, date), rates.currency(code), date);
  }

  @PreAuthorize(
      "hasAuthority('ALL_FUNCTIONS') or hasAuthority('CREATE_BASE_TELLER_FX_' + #p0.name())")
  public FxData.TradePreview preview(FxData.Operation operation, FxData.TradeRequest request) {
    AppUser actor = user(permission(operation));
    actor.validateHasReadPermission("CLIENT");
    FxValidation.validate(request);
    LocalDate date = DateUtils.getBusinessLocalDate();
    var drawer = cashier(actor, date);
    var usd = rates.currency("USD");
    var quote = rates.currency(request.quoteCurrency());
    if (request.usdAmount().stripTrailingZeros().scale() > usd.decimalPlaces())
      throw invalid("amount.precision", "USD amount exceeds the configured currency precision.");
    var customer = customer(request.clientId(), actor);
    if (!customer.active())
      throw invalid("customer.inactive", "The selected customer is not active.");
    if (operation == FxData.Operation.PURCHASE && request.identifierId() == null)
      throw invalid(
          "identification.required",
          "Purchase previews require an existing registered customer identifier.");
    if (request.identifierId() != null) identifier(request.identifierId(), customer.id(), date);
    var candidate = rates.draft(request.rateDraftId(), actor);
    var latest = rates.latest(drawer.officeId(), date, quote.code());
    if (candidate.officeId().longValue() != drawer.officeId().longValue()
        || !candidate.effectiveDate().equals(date.toString())
        || !candidate.quoteCurrency().equals(quote.code()))
      throw invalid(
          "rate.context",
          "Candidate draft must match the authenticated branch, business date and currency pair.");
    if (latest == null
        || !latest.id().equals(candidate.id())
        || candidate.version() != request.rateVersion())
      throw invalid(
          "rate.stale", "Candidate branch draft has changed. Refresh context and preview again.");
    boolean sale = operation == FxData.Operation.SALE;
    var usdLeg =
        leg(
            request.usdDenominations(),
            available(drawer, usd, date),
            usd,
            sale ? FxData.Direction.OUTFLOW : FxData.Direction.INFLOW);
    var quoteLeg =
        leg(
            request.quoteDenominations(),
            available(drawer, quote, date),
            quote,
            sale ? FxData.Direction.INFLOW : FxData.Direction.OUTFLOW);
    if (usdLeg.denominationTotal().compareTo(request.usdAmount()) != 0)
      throw invalid(
          "denominations.usd.total", "USD denominations must equal the requested USD amount.");
    return new FxData.TradePreview(
        operation,
        "INCOMPLETE_PREVIEW",
        false,
        false,
        customer,
        request.identifierId(),
        request.reference(),
        drawer,
        date.toString(),
        DateUtils.getOffsetDateTimeOfTenant().withOffsetSameInstant(ZoneOffset.UTC).toString(),
        request.usdAmount(),
        quote.code(),
        null,
        null,
        null,
        candidate,
        usdLeg,
        quoteLeg,
        capabilities());
  }

  /**
   * Always fails before reading or changing cash, creating history or invoking a native command.
   */
  @PreAuthorize(
      "hasAuthority('ALL_FUNCTIONS') or hasAuthority('CREATE_BASE_TELLER_FX_' + #p0.name())")
  public void confirm(FxData.Operation operation) {
    user(permission(operation));
    blocked("USD " + operation);
  }

  static FxData.CashLeg leg(
      List<FxData.Quantity> requested,
      List<FxData.Denomination> catalog,
      FxData.Currency currency,
      FxData.Direction direction) {
    var configured = new HashMap<String, FxData.Denomination>();
    catalog.forEach(d -> configured.put(d.id(), d));
    var lines = new ArrayList<FxData.CashLine>();
    BigDecimal total = BigDecimal.ZERO;
    for (var input : requested) {
      var d = configured.get(input.denominationId());
      long q = quantity(input.quantity());
      if (d == null || !d.currencyCode().equals(currency.code()))
        throw invalid(
            "denomination.unsupported",
            "Denomination is disabled, unknown or belongs to another currency.");
      if (direction == FxData.Direction.OUTFLOW && q > d.availableQuantity())
        throw invalid(
            "inventory.insufficient", "Insufficient recorded outgoing denomination inventory.");
      if (direction == FxData.Direction.INFLOW) {
        try {
          Math.addExact(d.availableQuantity(), q);
        } catch (ArithmeticException e) {
          throw invalid(
              "inventory.overflow", "Incoming quantity exceeds the supported inventory range.");
        }
      }
      BigDecimal amount = d.value().multiply(BigDecimal.valueOf(q));
      total = total.add(amount);
      decimal(total, false);
      lines.add(new FxData.CashLine(d.id(), currency.code(), d.type(), d.value(), q, amount));
    }
    if (total.signum() <= 0)
      throw invalid(
          "denominations.total", "Each currency must have a positive cash denomination total.");
    return new FxData.CashLeg(currency.code(), direction, total, List.copyOf(lines));
  }

  private List<FxData.Denomination> available(Cashier drawer, FxData.Currency c, LocalDate date) {
    if (c.decimalPlaces() < 0 || c.decimalPlaces() > 12)
      throw invalid("currency.precision", "Configured currency precision is unsupported.");
    var quantities = inventory.quantities(drawer.cashierId(), c.code(), date);
    return jdbc.query(
        "SELECT identifier,denomination_type,value FROM m_service_payment_denomination"
            + " WHERE currency_code=? AND active=true ORDER BY value DESC,identifier",
        (rs, i) -> {
          BigDecimal value = rs.getBigDecimal(3);
          if (value == null
              || value.signum() <= 0
              || value.stripTrailingZeros().scale() > c.decimalPlaces()
              || rs.getString(2) == null
              || rs.getString(2).isBlank())
            throw invalid(
                "denomination.configuration",
                "Denomination configuration does not match its currency.");
          return new FxData.Denomination(
              rs.getString(1),
              c.code(),
              rs.getString(2),
              value,
              quantities.getOrDefault(rs.getString(1), 0L));
        },
        c.code());
  }

  private FxData.Customer customer(Long id, AppUser actor) {
    var rows =
        jdbc.query(
            "SELECT c.id,c.account_no,c.display_name,c.office_id,c.status_enum FROM m_client c JOIN"
                + " m_office o ON o.id=c.office_id WHERE c.id=? AND o.hierarchy LIKE ?",
            (rs, i) ->
                new FxData.Customer(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getLong(4),
                    rs.getInt(5) == ClientStatus.ACTIVE.getValue()),
            id,
            FxRateService.hierarchy(actor));
    if (rows.size() != 1)
      throw invalid("customer.not.found", "Customer not found in the authenticated office scope.");
    return rows.getFirst();
  }

  private void identifier(Long id, Long client, LocalDate date) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_client_identifier i JOIN m_code_value cv ON"
                + " cv.id=i.document_type_id JOIN m_code c ON c.id=cv.code_id WHERE i.id=? AND"
                + " i.client_id=? AND i.status=? AND i.active=? AND cv.is_active=true AND"
                + " c.code_name='Customer Identifier' AND (i.issuance_date IS NULL OR"
                + " i.issuance_date<=?) AND (i.expiry_date IS NULL OR i.expiry_date>=?)",
            Long.class,
            id,
            client,
            ClientIdentifierStatus.ACTIVE.getValue(),
            ClientIdentifierStatus.ACTIVE.getValue(),
            date,
            date);
    if (count == null || count != 1)
      throw invalid(
          "identification.invalid",
          "Identifier must belong to this customer and be active and unexpired.");
  }

  private Cashier cashier(AppUser actor, LocalDate date) {
    if (actor.getStaffId() == null)
      throw invalid("cashier.required", "Authenticated staff assignment is required.");
    var rows =
        jdbc.query(
            """
            SELECT c.id,c.staff_id,s.display_name,t.id,t.name,o.id,o.name FROM m_cashiers c
            JOIN m_tellers t ON t.id=c.teller_id JOIN m_staff s ON s.id=c.staff_id JOIN m_office o ON o.id=t.office_id
            WHERE c.staff_id=? AND o.hierarchy LIKE ? AND s.is_active=true AND t.state=300
            AND (t.valid_from IS NULL OR t.valid_from<=?) AND (t.valid_to IS NULL OR t.valid_to>=?)
            AND (c.start_date IS NULL OR c.start_date<=?) AND (c.end_date IS NULL OR c.end_date>=?)
            AND (c.full_day=true OR (c.full_day=false AND CAST(? AS time) BETWEEN CAST(c.start_time AS time) AND CAST(c.end_time AS time)))
            AND NOT EXISTS(SELECT 1 FROM m_cashier_reconciliation r WHERE r.cashier_id=c.id AND r.business_date=?)
            AND EXISTS(SELECT 1 FROM m_cash_allocation a WHERE a.destination_cashier_id=c.id AND a.business_date=? AND a.status='COMPLETED' AND a.amount>0)
            ORDER BY c.id
            """,
            (rs, i) ->
                new Cashier(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getString(3),
                    rs.getLong(4),
                    rs.getString(5),
                    rs.getLong(6),
                    rs.getString(7)),
            actor.getStaffId(),
            FxRateService.hierarchy(actor),
            date,
            date,
            date,
            date,
            DateUtils.getOffsetDateTimeOfTenant().toLocalTime(),
            date,
            date);
    if (rows.size() != 1)
      throw invalid(
          "cashier.assignment",
          "Exactly one active, open, funded drawer is required for the authenticated staff"
              + " member.");
    return rows.getFirst();
  }

  private AppUser user(String permission) {
    var actor = security.authenticatedUser();
    actor.validateHasPermissionTo(permission);
    FxRateService.hierarchy(actor);
    return actor;
  }

  static String permission(FxData.Operation operation) {
    if (operation == null) throw invalid("operation.required", "An operation is required.");
    return "CREATE_" + TELLER + "_" + operation.name();
  }
}
