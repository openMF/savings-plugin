package org.apache.fineract.baseteller.service;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.CashDrawerPickupContextData;
import org.apache.fineract.baseteller.data.CashDrawerPickupData;
import org.apache.fineract.baseteller.data.CashDrawerPickupRequest;
import org.apache.fineract.baseteller.data.CashExchangeDenominationData;
import org.apache.fineract.baseteller.data.CashExchangeInventoryLineData;
import org.apache.fineract.baseteller.data.EndOfDayClosingData;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.commands.service.SynchronousCommandProcessingService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.FineractRequestContextHolder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashDrawerPickupPlatformService {
  private static final String RESOURCE = "BASE_TELLER_CASH_DRAWER_PICKUP";
  private static final Gson GSON = new Gson();
  private final JdbcTemplate jdbc;
  private final PlatformSecurityContext security;
  private final CashExchangeInventory inventory;
  private final PortfolioCommandSourceWritePlatformService commands;
  private final AuthenticationProvider authentication;
  private final FineractRequestContextHolder requestContext;

  public CashDrawerPickupPlatformService(JdbcTemplate jdbc, PlatformSecurityContext security,
      CashExchangeInventory inventory, PortfolioCommandSourceWritePlatformService commands,
      @Qualifier("customAuthenticationProvider") AuthenticationProvider authentication,
      FineractRequestContextHolder requestContext) {
    this.jdbc = jdbc;
    this.security = security;
    this.inventory = inventory;
    this.commands = commands;
    this.authentication = authentication;
    this.requestContext = requestContext;
  }

  @Transactional(readOnly = true)
  public CashDrawerPickupContextData context() {
    AppUser actor = user("READ");
    LocalDate date = DateUtils.getBusinessLocalDate();
    List<EndOfDayClosingData.Cashier> sources = sourceCashiers(actor, date);
    List<EndOfDayClosingData.Cashier> destinations = new ArrayList<>();
    for (var source : sources) destinations.addAll(destinations(source, date));
    destinations = destinations.stream().distinct().toList();
    List<EndOfDayClosingData.Currency> currencies = jdbc.query(
        "SELECT code,decimal_places FROM m_organisation_currency ORDER BY code",
        (rs, i) -> new EndOfDayClosingData.Currency(rs.getString(1), rs.getInt(2), catalog(rs.getString(1))));
    List<String> permissions = new ArrayList<>();
    if (actor.hasAnyPermission("CREATE_" + RESOURCE)) permissions.add("CREATE");
    if (actor.hasAnyPermission("REPRINT_" + RESOURCE)) permissions.add("REPRINT");
    permissions.add("READ");
    List<CashDrawerPickupContextData.SourceInventory> sourceInventory = new ArrayList<>();
    for (var source : sources) {
      for (var currency : currencies) {
        Map<String, Long> available = inventory.quantities(source.cashierId(), currency.currencyCode(), date);
        List<CashExchangeInventoryLineData> lines = currency.denominations().stream()
            .map(d -> new CashExchangeInventoryLineData(d.denominationId(), d.type(), d.value(),
                available.getOrDefault(d.denominationId(), 0L)))
            .toList();
        sourceInventory.add(new CashDrawerPickupContextData.SourceInventory(source.cashierId(), currency.currencyCode(), lines));
      }
    }
    return new CashDrawerPickupContextData(date.toString(), sources, destinations, currencies, sourceInventory, permissions);
  }

  @Transactional(readOnly = true)
  public List<CashDrawerPickupData.Check> checks(Long cashierId) {
    AppUser actor = user("READ");
    var source = source(cashierId, actor, DateUtils.getBusinessLocalDate());
    return eligibleChecks(source);
  }

  @Transactional(readOnly = true)
  public CashDrawerPickupData preview(CashDrawerPickupRequest request) {
    AppUser actor = user("CREATE");
    Prepared prepared = prepare(request, actor);
    return data(null, null, prepared, null, null, null);
  }

  @Transactional
  public CashDrawerPickupData create(CashDrawerPickupRequest request, String username, String credential) {
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    AppUser actor = user("CREATE");
    validate(request, true);
    jdbc.queryForObject("SELECT id FROM m_cash_exchange_mutex WHERE id=1 FOR UPDATE", Long.class);
    // The existing cash and closing writers do not share a row lock. These locks cover the
    // source of every denomination and check snapshot before the final read and native commands.
    jdbc.execute("LOCK TABLE m_cash_allocation,m_cash_allocation_denomination,m_cashiers,m_tellers,"
        + "m_cashier_transactions,m_cashier_reconciliation,m_cashier_reconciliation_check,"
        + "m_cash_operation_transaction,m_cash_operation_check,m_base_teller_deposit,"
        + "m_base_teller_deposit_check_detail,m_base_teller_savings_opening,"
        + "m_base_teller_credit_payment,m_base_teller_credit_payment_check,"
        + "m_base_teller_returned_check,m_service_payment,m_service_payment_denomination,"
        + "m_savings_account_transaction,m_cash_exchange,m_cash_exchange_detail,"
        + "m_transaction_reversal,m_transaction_reversal_check,m_cash_drawer_pickup,"
        + "m_cash_drawer_pickup_currency,m_cash_drawer_pickup_check,m_cash_drawer_pickup_cash"
        + " IN SHARE ROW EXCLUSIVE MODE");
    LocalDate date = DateUtils.getBusinessLocalDate();
    var source = source(request.cashierId(), actor, date);
    var destination = destination(request.destinationCashierId(), source, date);
    AppUser authorizer = authorize(username, credential, actor, source, destination);
    String fingerprint = hash(GSON.toJson(request));
    List<Existing> old = jdbc.query("SELECT id,request_fingerprint FROM m_cash_drawer_pickup WHERE idempotency_key=?",
        (rs, i) -> new Existing(rs.getLong(1), rs.getString(2)), request.idempotencyKey());
    if (!old.isEmpty()) {
      if (!old.get(0).fingerprint().equals(fingerprint)) throw invalid("idempotency.conflict", "Idempotency key belongs to a different pickup.");
      return stored(old.get(0).id(), actor);
    }
    Prepared prepared = prepare(request, actor);
    String reference = "PU-" + date + "-" + hash(request.idempotencyKey()).substring(0, 20).toUpperCase(Locale.ROOT);
    String authorizedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
    Long id = jdbc.queryForObject("""
        INSERT INTO m_cash_drawer_pickup(idempotency_key,request_fingerprint,reference,status,business_date,office_id,source_cashier_id,destination_cashier_id,authorized_by,authorized_at,created_by,snapshot)
        VALUES (?,?,?,'PROCESSING',?,?,?,?,?,?,?,'{}') RETURNING id
        """, Long.class, request.idempotencyKey(), fingerprint, reference, date, source.officeId(),
        source.cashierId(), destination.cashierId(), authorizer.getId(), OffsetDateTime.parse(authorizedAt), actor.getId());
    for (var currency : prepared.currencies()) {
      Long currencyId = jdbc.queryForObject("""
          INSERT INTO m_cash_drawer_pickup_currency(pickup_id,currency_code,business_date,status,source_cashier_id,destination_cashier_id,cash_total,check_total)
          VALUES (?,?,?,'COMPLETED',?,?,?,?) RETURNING id
          """, Long.class, id, currency.currencyCode(), date, source.cashierId(), destination.cashierId(), currency.cash(), currency.checks());
      for (var line : currency.cashDenominations()) jdbc.update("""
          INSERT INTO m_cash_drawer_pickup_cash(pickup_currency_id,denomination_identifier,denomination_type,denomination_value,quantity,line_total)
          VALUES (?,?,?,?,?,?)
          """, currencyId, line.denominationId(), line.type(), line.value(), line.quantity(), line.amount());
      for (var check : currency.selectedChecks()) jdbc.update("""
          INSERT INTO m_cash_drawer_pickup_check(pickup_currency_id,source_type,source_id,amount,source_cashier_id,destination_cashier_id)
          VALUES (?,?,?,?,?,?)
          """, currencyId, check.source(), check.id(), check.amount(), source.cashierId(), destination.cashierId());
      if (currency.cash().signum() > 0) {
        Long out = movement(new CommandWrapperBuilder().settleCashFromCashier(source.tellerId(), source.cashierId()),
            currency.cash(), currency.currencyCode(), date, reference, request.idempotencyKey() + ":" + currency.currencyCode() + ":out");
        Long in = movement(new CommandWrapperBuilder().allocateCashToCashier(destination.tellerId(), destination.cashierId()),
            currency.cash(), currency.currencyCode(), date, reference, request.idempotencyKey() + ":" + currency.currencyCode() + ":in");
        jdbc.update("UPDATE m_cash_drawer_pickup_currency SET source_cashier_transaction_id=?,destination_cashier_transaction_id=? WHERE id=?", out, in, currencyId);
      }
    }
    CashDrawerPickupData result = data(id, reference, prepared, authorizer.getId(), authorizer.getUsername(), authorizedAt);
    jdbc.update("UPDATE m_cash_drawer_pickup SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP,snapshot=? WHERE id=?", GSON.toJson(result), id);
    return result;
  }

  @Transactional(readOnly = true)
  public CashDrawerPickupData retrieve(Long id) { return stored(id, user("READ")); }

  @Transactional(readOnly = true)
  public CashDrawerPickupData receipt(Long id) { return stored(id, user("REPRINT")); }

  private Prepared prepare(CashDrawerPickupRequest request, AppUser actor) {
    validate(request, false);
    LocalDate date = DateUtils.getBusinessLocalDate();
    var source = source(request.cashierId(), actor, date);
    var destination = destination(request.destinationCashierId(), source, date);
    List<CashDrawerPickupData.Check> eligible = eligibleChecks(source);
    Set<String> selected = new HashSet<>();
    List<CashDrawerPickupData.Currency> currencies = new ArrayList<>();
    for (var requested : request.currencies()) {
      String code = requested.currencyCode().toUpperCase(Locale.ROOT);
      Integer precision = jdbc.query("SELECT decimal_places FROM m_organisation_currency WHERE code=?",
          (rs, i) -> rs.getInt(1), code).stream().findFirst().orElse(null);
      if (precision == null) throw invalid("currency.invalid", "Currency is not enabled for this tenant.");
      boolean funded = destinations(source, date).stream().anyMatch(d -> d.cashierId().equals(destination.cashierId())
          && Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM m_cash_allocation WHERE operation_type='HEAD_CASHIER_ALLOCATION' AND status='COMPLETED' AND destination_cashier_id=? AND business_date=? AND currency_code=?", Boolean.class, destination.cashierId(), date, code)));
      if (!funded) throw invalid("destination.invalid", "Destination is not a funded head cashier for this currency.");
      Map<String, CashExchangeDenominationData> configured = new HashMap<>();
      catalog(code).forEach(d -> {
        if (d.value() == null || d.value().signum() <= 0 || d.value().stripTrailingZeros().scale() > precision
            || !("COIN".equals(d.type()) || EndOfDayClosingCalculator.isBill(d.type())))
          throw invalid("catalog.invalid", "Configured denomination is invalid for this currency.");
        configured.put(d.denominationId(), d);
      });
      Map<String, Long> available = requested.cashDenominations().isEmpty()
          ? Map.of() : inventory.quantities(source.cashierId(), code, date);
      Map<String, Long> destinationAvailable = requested.cashDenominations().isEmpty()
          ? Map.of() : inventory.quantities(destination.cashierId(), code, date);
      Set<String> seen = new HashSet<>();
      List<CashExchangeDenominationData> lines = new ArrayList<>();
      for (var item : requested.cashDenominations()) {
        if (item == null || item.denominationId() == null || !seen.add(item.denominationId())) throw invalid("denomination.duplicate", "Denominations must have unique identifiers.");
        var denomination = configured.get(item.denominationId());
        if (denomination == null) throw invalid("denomination.invalid", "Denomination is unknown, disabled or belongs to another currency.");
        long quantity;
        try { quantity = item.quantity().longValueExact(); }
        catch (ArithmeticException | NullPointerException e) { throw invalid("quantity.invalid", "Quantity must be a nonnegative integer."); }
        if (quantity < 0 || quantity > available.getOrDefault(item.denominationId(), 0L)) throw invalid("inventory.insufficient", "Insufficient source denomination inventory.");
        try { Math.addExact(destinationAvailable.getOrDefault(item.denominationId(), 0L), quantity); }
        catch (ArithmeticException e) { throw invalid("inventory.overflow", "Destination denomination inventory would overflow."); }
        if (quantity > 0) lines.add(new CashExchangeDenominationData(item.denominationId(), denomination.type(), denomination.value(), quantity,
            denomination.value().multiply(BigDecimal.valueOf(quantity))));
      }
      List<CashDrawerPickupData.Check> checks = new ArrayList<>();
      for (var item : requested.checks()) {
        if (item == null || item.source() == null || item.id() == null || item.id() <= 0 || !selected.add(item.source() + ":" + item.id())) throw invalid("check.duplicate", "Check selections must be unique and valid.");
        checks.add(eligible.stream().filter(c -> c.source().equals(item.source()) && c.id().equals(item.id()) && c.currencyCode().equals(code))
            .findFirst().orElseThrow(() -> invalid("check.ineligible", "Selected check is not in source custody.")));
      }
      lines.sort(Comparator.comparing(CashExchangeDenominationData::denominationId));
      checks.sort(Comparator.comparing(CashDrawerPickupData.Check::source).thenComparing(CashDrawerPickupData.Check::id));
      BigDecimal cash = lines.stream().map(CashExchangeDenominationData::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal checkTotal = checks.stream().map(CashDrawerPickupData.Check::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
      if (cash.stripTrailingZeros().scale() > precision || checkTotal.stripTrailingZeros().scale() > precision) throw invalid("amount.precision", "Pickup total exceeds currency precision.");
      currencies.add(new CashDrawerPickupData.Currency(code, cash, checkTotal, BigDecimal.ZERO, cash.add(checkTotal), List.copyOf(lines), List.copyOf(checks)));
    }
    if (currencies.stream().allMatch(c -> c.totalToCollect().signum() == 0)) throw invalid("selection.empty", "Select cash or checks for pickup.");
    return new Prepared(source, destination, date, List.copyOf(currencies));
  }

  private List<CashExchangeDenominationData> catalog(String code) {
    return jdbc.query("SELECT identifier,denomination_type,value FROM m_service_payment_denomination WHERE currency_code=? AND active=true ORDER BY value DESC",
        (rs, i) -> new CashExchangeDenominationData(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), 0L, BigDecimal.ZERO), code);
  }

  private List<EndOfDayClosingData.Cashier> sourceCashiers(AppUser actor, LocalDate date) {
    if (actor.getOffice() == null || actor.getStaffId() == null) return List.of();
    return jdbc.query("""
        SELECT c.id,c.staff_id,s.display_name,t.id,t.name,o.id,o.name FROM m_cashiers c
        JOIN m_tellers t ON t.id=c.teller_id JOIN m_staff s ON s.id=c.staff_id JOIN m_office o ON o.id=t.office_id
        WHERE c.staff_id=? AND o.hierarchy LIKE ? AND t.state=300
        AND (t.valid_from IS NULL OR t.valid_from<=?) AND (t.valid_to IS NULL OR t.valid_to>=?)
        AND (c.start_date IS NULL OR c.start_date<=?) AND (c.end_date IS NULL OR c.end_date>=?)
        AND NOT EXISTS(SELECT 1 FROM m_cashier_reconciliation r WHERE r.cashier_id=c.id AND r.business_date=?)
        ORDER BY c.id
        """, (rs, i) -> new EndOfDayClosingData.Cashier(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getLong(6), rs.getString(7)),
        actor.getStaffId(), actor.getOffice().getHierarchy() + "%", date, date, date, date, date);
  }

  private EndOfDayClosingData.Cashier source(Long id, AppUser actor, LocalDate date) {
    return sourceCashiers(actor, date).stream().filter(c -> Objects.equals(c.cashierId(), id)).findFirst()
        .orElseThrow(() -> invalid("source.forbidden", "Source cashier is not an active assigned drawer."));
  }

  private List<EndOfDayClosingData.Cashier> destinations(EndOfDayClosingData.Cashier source, LocalDate date) {
    return jdbc.query("""
        SELECT DISTINCT c.id,c.staff_id,s.display_name,t.id,t.name,o.id,o.name FROM m_cash_allocation a
        JOIN m_cashiers c ON c.id=a.destination_cashier_id JOIN m_tellers t ON t.id=c.teller_id
        JOIN m_staff s ON s.id=c.staff_id JOIN m_office o ON o.id=t.office_id
        WHERE a.operation_type='HEAD_CASHIER_ALLOCATION' AND a.status='COMPLETED'
        AND a.business_date=? AND a.office_id=? AND c.id<>? AND t.state=300
        AND (t.valid_from IS NULL OR t.valid_from<=?) AND (t.valid_to IS NULL OR t.valid_to>=?)
        AND (c.start_date IS NULL OR c.start_date<=?) AND (c.end_date IS NULL OR c.end_date>=?)
        ORDER BY c.id
        """, (rs, i) -> new EndOfDayClosingData.Cashier(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getLong(6), rs.getString(7)),
        date, source.officeId(), source.cashierId(), date, date, date, date);
  }

  private EndOfDayClosingData.Cashier destination(Long id, EndOfDayClosingData.Cashier source, LocalDate date) {
    return destinations(source, date).stream().filter(c -> Objects.equals(c.cashierId(), id)).findFirst()
        .orElseThrow(() -> invalid("destination.invalid", "Destination must be an active funded head cashier in the source office."));
  }

  private List<CashDrawerPickupData.Check> eligibleChecks(EndOfDayClosingData.Cashier source) {
    return jdbc.query("""
        SELECT q.* FROM (
        SELECT 'DEPOSIT' source_type,cd.id,CAST(d.created_on_utc AS date) accepted_date,UPPER(d.currency_code) currency_code,
        cd.check_bank bank,cd.check_number reference,cl.display_name client,d.cashier_id,cd.amount,d.office_id,'PENDING_COLLECTION' status
        FROM m_base_teller_deposit_check_detail cd JOIN m_base_teller_deposit d ON d.id=cd.deposit_id
        LEFT JOIN m_client cl ON cl.id=d.client_id
        WHERE d.status IN ('PENDING_COLLECTION','COMPLETED') AND cd.clearing_status='SUBJECT_TO_COLLECTION'
        AND NOT EXISTS(SELECT 1 FROM m_base_teller_returned_check r WHERE r.deposit_check_detail_id=cd.id AND r.status IN ('RETURNED','SETTLED'))
        AND NOT EXISTS(SELECT 1 FROM m_cashier_reconciliation_check r WHERE r.deposit_check_detail_id=cd.id)
        AND NOT EXISTS(SELECT 1 FROM m_cash_operation_check r WHERE r.deposit_check_detail_id=cd.id)
        AND NOT EXISTS(SELECT 1 FROM m_transaction_reversal_check r WHERE r.source_type='SAVINGS_DEPOSIT' AND r.source_check_id=cd.id)
        UNION ALL
        SELECT 'OPENING',o.id,CAST(o.created_on_utc AS date),UPPER(o.currency_code),o.check_bank,o.check_number,cl.display_name,o.cashier_id,o.amount,o.office_id,'PENDING_COLLECTION'
        FROM m_base_teller_savings_opening o LEFT JOIN m_client cl ON cl.id=o.client_id
        WHERE o.funding_type='CHECK' AND o.status='COMPLETED'
        UNION ALL
        SELECT 'CREDIT',c.id,CAST(c.accepted_on_utc AS date),UPPER(p.currency_code),c.bank_name_snapshot,c.check_number,cl.display_name,p.cashier_id,p.amount,p.office_id,c.status
        FROM m_base_teller_credit_payment_check c JOIN m_base_teller_credit_payment p ON p.id=c.credit_payment_id
        LEFT JOIN m_client cl ON cl.id=p.client_id
        WHERE c.status='PENDING_COLLECTION' AND p.status='PENDING_COLLECTION'
        AND NOT EXISTS(SELECT 1 FROM m_transaction_reversal_check r WHERE r.source_type='CREDIT_PAYMENT' AND r.source_check_id=c.id)
        ) q WHERE q.cashier_id=? AND q.office_id=? AND q.accepted_date<=?
        AND NOT EXISTS(SELECT 1 FROM m_cash_drawer_pickup_check pc WHERE pc.source_type=q.source_type AND pc.source_id=q.id)
        AND NOT EXISTS(SELECT 1 FROM m_end_of_day_closing_check ec WHERE ec.source_type=q.source_type AND ec.source_id=q.id)
        ORDER BY q.currency_code,q.source_type,q.id
        """, (rs, i) -> new CashDrawerPickupData.Check(rs.getString("source_type"), rs.getLong("id"), rs.getString("accepted_date"),
            rs.getString("bank"), rs.getString("reference"), rs.getString("client"), rs.getString("currency_code"), rs.getBigDecimal("amount"), rs.getString("status")),
        source.cashierId(), source.officeId(), DateUtils.getBusinessLocalDate());
  }

  private AppUser authorize(String username, String credential, AppUser actor,
      EndOfDayClosingData.Cashier source, EndOfDayClosingData.Cashier destination) {
    if (username == null || username.isBlank() || credential == null || credential.isBlank()) throw invalid("authorization.invalid", "Head Teller authorization failed.");
    var token = UsernamePasswordAuthenticationToken.unauthenticated(username, credential);
    Authentication verified;
    try { verified = authentication.authenticate(token); }
    catch (AuthenticationException e) { throw invalid("authorization.invalid", "Head Teller authorization failed."); }
    finally { token.eraseCredentials(); }
    if (verified == null || !verified.isAuthenticated() || !(verified.getPrincipal() instanceof AppUser authorizer)) throw invalid("authorization.invalid", "Head Teller authorization failed.");
    if (verified instanceof org.springframework.security.core.CredentialsContainer container) container.eraseCredentials();
    if (!authorizer.hasAnyPermission("AUTHORIZE_" + RESOURCE)
        || Objects.equals(authorizer.getId(), actor.getId())
        || !Objects.equals(authorizer.getStaffId(), destination.staffId())
        || Objects.equals(authorizer.getStaffId(), source.staffId())
        || authorizer.getOffice() == null
        || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM m_office WHERE id=? AND hierarchy LIKE ?", Boolean.class,
            source.officeId(), authorizer.getOffice().getHierarchy() + "%"))) throw invalid("authorization.invalid", "Head Teller authorization failed.");
    return authorizer;
  }

  private Long movement(CommandWrapperBuilder builder, BigDecimal amount, String currency, LocalDate date, String reference, String key) {
    Map<String, Object> body = Map.of("currencyCode", currency, "txnAmount", amount, "txnNote", "Cash drawer pickup " + reference,
        "locale", "en", "dateFormat", "yyyy-MM-dd", "txnDate", date.toString());
    // Fineract stores the last native command ID in the HTTP request. A second internal
    // command would otherwise be treated as a retry of the first one.
    String attribute = SynchronousCommandProcessingService.COMMAND_SOURCE_ID;
    Object enclosingCommandId = requestContext.getAttribute(attribute, null);
    CommandProcessingResult result;
    try {
      requestContext.setAttribute(attribute, null);
      result = commands.logCommandSource(builder.withJson(GSON.toJson(body)).build(key));
    } finally {
      requestContext.setAttribute(attribute, enclosingCommandId);
    }
    if (result == null || result.getSubResourceId() == null) throw invalid("cash.movement.failed", "Fineract did not return a cashier transaction reference.");
    return result.getSubResourceId();
  }

  private CashDrawerPickupData data(Long id, String reference, Prepared prepared, Long authorizerId, String authorizerName, String authorizedAt) {
    return new CashDrawerPickupData(id, reference, id == null ? "PREVIEW" : "COMPLETED", prepared.date().toString(),
        prepared.source().officeId(), prepared.source().officeName(), prepared.source().cashierId(), prepared.source().cashierName(),
        prepared.destination().cashierId(), prepared.destination().cashierName(), authorizerId, authorizerName, authorizedAt,
        id == null ? null : OffsetDateTime.now(ZoneOffset.UTC).toString(), prepared.currencies());
  }

  private CashDrawerPickupData stored(Long id, AppUser actor) {
    if (id == null || id <= 0) throw invalid("not.found", "Pickup was not found.");
    List<String> rows = jdbc.query("""
        SELECT p.snapshot FROM m_cash_drawer_pickup p JOIN m_office o ON o.id=p.office_id
        JOIN m_cashiers c ON c.id=p.source_cashier_id
        WHERE p.id=? AND p.status='COMPLETED' AND o.hierarchy LIKE ?
        AND (p.created_by=? OR p.authorized_by=? OR ?)
        """, (rs, i) -> rs.getString(1), id, actor.getOffice().getHierarchy() + "%", actor.getId(), actor.getId(), actor.hasAnyPermission("READ_GLOBAL_SETTLEMENT"));
    if (rows.isEmpty()) throw invalid("not.found", "Pickup was not found in the authorized scope.");
    return GSON.fromJson(rows.get(0), CashDrawerPickupData.class);
  }

  private void validate(CashDrawerPickupRequest request, boolean create) {
    if (request == null || request.cashierId() == null || request.destinationCashierId() == null
        || Objects.equals(request.cashierId(), request.destinationCashierId()) || request.currencies() == null || request.currencies().isEmpty())
      throw invalid("request.invalid", "A source, destination and currencies are required.");
    if (create && (request.idempotencyKey() == null || request.idempotencyKey().isBlank() || request.idempotencyKey().length() > 100))
      throw invalid("idempotency.invalid", "A valid idempotency key is required.");
    Set<String> codes = new HashSet<>();
    for (var c : request.currencies()) {
      if (c == null || c.currencyCode() == null || c.currencyCode().isBlank() || !codes.add(c.currencyCode().toUpperCase(Locale.ROOT))
          || c.cashDenominations() == null || c.checks() == null) throw invalid("currency.invalid", "Currency rows must be unique and include cash and check arrays.");
    }
  }

  private AppUser user(String action) {
    AppUser actor = security.authenticatedUser();
    actor.validateHasPermissionTo(action + "_" + RESOURCE);
    return actor;
  }

  private static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  private static GeneralPlatformDomainRuleException invalid(String code, String message) {
    return new GeneralPlatformDomainRuleException("error.msg.base.teller.pickup." + code, message);
  }

  private record Prepared(EndOfDayClosingData.Cashier source, EndOfDayClosingData.Cashier destination,
      LocalDate date, List<CashDrawerPickupData.Currency> currencies) {}
  private record Existing(Long id, String fingerprint) {}
}
