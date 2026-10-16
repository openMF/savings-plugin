package org.apache.fineract.baseteller.cashtransfer;

import static org.apache.fineract.baseteller.cashtransfer.CashTransferValidation.*;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.cashtransfer.CashTransferData.*;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.Cashier;
import org.apache.fineract.baseteller.service.CashCustodyPosting;
import org.apache.fineract.baseteller.service.CashExchangeInventory;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.teller.service.TellerWritePlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Physical same-office custody transfer. Native cashier services own every monetary effect. */
@Service
@RequiredArgsConstructor
public class CashTransferPlatformService {
  public static final String RESOURCE = "BASE_TELLER_CASH_TRANSFER";
  public static final String OPERATION = "CASH_TRANSFER_BETWEEN_TELLERS";
  private static final Gson GSON = new Gson();
  private final JdbcTemplate jdbc;
  private final PlatformSecurityContext security;
  private final CashExchangeInventory inventory;
  private final TellerWritePlatformService tellers;
  private final FromJsonHelper jsonHelper;

  @Transactional(readOnly = true)
  public Context context() {
    AppUser actor = user("READ");
    LocalDate date = DateUtils.getBusinessLocalDate();
    Cashier source = source(actor, date);
    List<AvailableCurrency> currencies = new ArrayList<>();
    for (var currency : configuredCurrencies()) {
      Map<String, Long> available = inventory.quantities(source.cashierId(), currency.code(), date);
      currencies.add(
          new AvailableCurrency(
              currency.code(),
              currency.precision(),
              catalog(currency).stream()
                  .map(
                      d ->
                          new AvailableDenomination(
                              d.denominationId(),
                              currency.code(),
                              d.denominationValue(),
                              d.denominationType(),
                              available.getOrDefault(d.denominationId(), 0L)))
                  .toList()));
    }
    List<String> permissions = new ArrayList<>(List.of("READ_" + RESOURCE));
    for (String action : List.of("CREATE", "REPRINT"))
      if (actor.hasAnyPermission(action + "_" + RESOURCE)) permissions.add(action + "_" + RESOURCE);
    return new Context(
        date.toString(),
        source,
        destinations(source, date),
        List.copyOf(currencies),
        List.copyOf(permissions));
  }

  @Transactional(readOnly = true)
  public CashTransferData preview(CashTransferRequest request) {
    AppUser actor = user("CREATE");
    return prepare(request, actor);
  }

  @Transactional
  public CashTransferData create(CashTransferRequest request) {
    // EclipseLink's shared JPA/JDBC transaction manager does not support Spring custom isolation.
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    AppUser actor = user("CREATE");
    validate(request, true);
    // Shared with exchange, pickup and closing. Check replay before resolving today's assignment:
    // a completed transfer remains replayable after the drawer closes or the business date changes.
    jdbc.queryForObject("SELECT id FROM m_cash_exchange_mutex WHERE id=1 FOR UPDATE", Long.class);
    String fingerprint = fingerprint(request, actor.getId());
    var previous =
        jdbc.query(
            "SELECT id,request_fingerprint,created_by FROM m_cash_teller_transfer WHERE"
                + " idempotency_key=?",
            (rs, i) -> new Existing(rs.getLong(1), rs.getString(2), rs.getLong(3)),
            request.idempotencyKey());
    if (!previous.isEmpty()) {
      var old = previous.get(0);
      if (!Objects.equals(old.actor(), actor.getId()) || !old.fingerprint().equals(fingerprint))
        throw invalid(
            "idempotency.conflict", "Idempotency key already belongs to a different transfer.");
      return stored(old.id(), actor);
    }
    lockInventory();
    CashTransferData prepared = prepare(request, actor);
    // Lock both cashiers in ascending order, matching the shared office-before-cashier protocol.
    jdbc.queryForObject(
        "SELECT id FROM m_office WHERE id=? FOR UPDATE", Long.class, prepared.source().officeId());
    jdbc.queryForList(
        "SELECT id FROM m_cashiers WHERE id IN (?,?) ORDER BY id FOR UPDATE",
        Long.class,
        prepared.source().cashierId(),
        prepared.destination().cashierId());
    String reference =
        "CT-"
            + prepared.businessDate()
            + "-"
            + hash(request.idempotencyKey()).substring(0, 24).toUpperCase(Locale.ROOT);
    String createdAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO m_cash_teller_transfer(idempotency_key,request_fingerprint,reference,status,business_date,
              office_id,source_cashier_id,destination_cashier_id,created_by,created_at,snapshot)
            VALUES (?,?,?,'PROCESSING',?,?,?,?,?,?,'{}') RETURNING id
            """,
            Long.class,
            request.idempotencyKey(),
            fingerprint,
            reference,
            LocalDate.parse(prepared.businessDate()),
            prepared.source().officeId(),
            prepared.source().cashierId(),
            prepared.destination().cashierId(),
            actor.getId(),
            OffsetDateTime.parse(createdAt));
    List<Currency> posted = new ArrayList<>();
    for (var currency : prepared.currencies()) {
      Long currencyId =
          jdbc.queryForObject(
              """
              INSERT INTO m_cash_teller_transfer_currency(transfer_id,currency_code,business_date,status,
                source_cashier_id,destination_cashier_id,cash_total)
              VALUES (?,?,?,'COMPLETED',?,?,?) RETURNING id
              """,
              Long.class,
              id,
              currency.currencyCode(),
              LocalDate.parse(prepared.businessDate()),
              prepared.source().cashierId(),
              prepared.destination().cashierId(),
              currency.total());
      for (var d : currency.denominations())
        jdbc.update(
            """
            INSERT INTO m_cash_teller_transfer_denomination(transfer_currency_id,denomination_identifier,
              denomination_type,denomination_value,quantity,line_total) VALUES (?,?,?,?,?,?)
            """,
            currencyId,
            d.denominationId(),
            d.denominationType(),
            d.denominationValue(),
            d.quantity(),
            d.amount());
      Long out =
          movement(
              prepared.source().cashierId(), true, currency, prepared.businessDate(), reference);
      Long in =
          movement(
              prepared.destination().cashierId(),
              false,
              currency,
              prepared.businessDate(),
              reference);
      // Confirm both authoritative native movements before completing custody.
      verifyMovement(out, prepared.source().cashierId(), 102, currency, prepared.businessDate());
      verifyMovement(
          in, prepared.destination().cashierId(), 101, currency, prepared.businessDate());
      jdbc.update(
          "UPDATE m_cash_teller_transfer_currency SET"
              + " source_cashier_transaction_id=?,destination_cashier_transaction_id=? WHERE id=?",
          out,
          in,
          currencyId);
      posted.add(
          new Currency(
              currency.currencyCode(),
              currency.decimalPlaces(),
              currency.total(),
              currency.denominations(),
              out,
              in));
    }
    // Reconcile the recorded composition against the real native monetary summary for BOTH drawers.
    for (var currency : posted) {
      inventory.quantities(
          prepared.source().cashierId(),
          currency.currencyCode(),
          LocalDate.parse(prepared.businessDate()));
      inventory.quantities(
          prepared.destination().cashierId(),
          currency.currencyCode(),
          LocalDate.parse(prepared.businessDate()));
    }
    String completedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
    CashTransferData result =
        new CashTransferData(
            id,
            reference,
            OPERATION,
            "COMPLETED",
            prepared.businessDate(),
            prepared.source(),
            prepared.destination(),
            actor.getId(),
            actor.getUsername(),
            createdAt,
            completedAt,
            true,
            prepared.inflowsByCurrency(),
            prepared.outflowsByCurrency(),
            List.copyOf(posted));
    jdbc.update(
        "UPDATE m_cash_teller_transfer SET status='COMPLETED',completed_at=?,snapshot=? WHERE id=?",
        OffsetDateTime.parse(completedAt),
        GSON.toJson(result),
        id);
    return result;
  }

  @Transactional(readOnly = true)
  public CashTransferData retrieve(Long id) {
    return stored(id, user("READ"));
  }

  @Transactional(readOnly = true)
  public CashTransferData receipt(Long id) {
    return stored(id, user("REPRINT"));
  }

  private CashTransferData prepare(CashTransferRequest request, AppUser actor) {
    validate(request, false);
    LocalDate date = DateUtils.getBusinessLocalDate();
    Cashier source = source(actor, date);
    Cashier destination =
        destinations(source, date).stream()
            .filter(c -> c.cashierId().equals(request.destinationCashierId()))
            .findFirst()
            .orElseThrow(
                () ->
                    invalid(
                        "destination.invalid",
                        "Destination must be another active open cashier in the same office."));
    Map<String, ConfiguredCurrency> configured =
        configuredCurrencies().stream()
            .collect(Collectors.toMap(ConfiguredCurrency::code, Function.identity()));
    List<Currency> currencies = new ArrayList<>();
    for (var selection : request.currencies()) {
      var currency = configured.get(selection.currencyCode().toUpperCase(Locale.ROOT));
      if (currency == null)
        throw invalid("currency.unsupported", "Currency is not configured for the tenant.");
      var catalog =
          catalog(currency).stream()
              .collect(Collectors.toMap(Denomination::denominationId, Function.identity()));
      Map<String, Long> available = inventory.quantities(source.cashierId(), currency.code(), date);
      Map<String, Long> receiving =
          inventory.quantities(destination.cashierId(), currency.code(), date);
      List<Denomination> lines = new ArrayList<>();
      for (var d : selection.denominations()) {
        var metadata = catalog.get(d.denominationId());
        if (metadata == null)
          throw invalid(
              "denomination.invalid",
              "Denomination is disabled, unknown or belongs to a different currency.");
        long quantity = quantity(d.quantity());
        if (quantity > available.getOrDefault(d.denominationId(), 0L))
          throw invalid(
              "inventory.insufficient",
              "Source does not hold the requested denomination quantity.");
        try {
          Math.addExact(receiving.getOrDefault(d.denominationId(), 0L), quantity);
        } catch (ArithmeticException e) {
          throw invalid(
              "inventory.overflow", "Destination quantity would exceed the supported range.");
        }
        if (quantity > 0)
          lines.add(
              new Denomination(
                  d.denominationId(),
                  metadata.denominationType(),
                  metadata.denominationValue(),
                  quantity,
                  metadata.denominationValue().multiply(BigDecimal.valueOf(quantity))));
      }
      BigDecimal total =
          lines.stream().map(Denomination::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
      if (total.signum() <= 0
          || total.compareTo(new BigDecimal("10000000000000")) >= 0
          || total.stripTrailingZeros().scale() > currency.precision())
        throw invalid(
            "amount.invalid",
            "Each currency must have a positive total within currency precision and native amount"
                + " limits.");
      lines.sort(Comparator.comparing(Denomination::denominationId));
      currencies.add(
          new Currency(
              currency.code(), currency.precision(), total, List.copyOf(lines), null, null));
    }
    currencies.sort(Comparator.comparing(Currency::currencyCode));
    var totals =
        currencies.stream().map(c -> new CurrencyTotal(c.currencyCode(), c.total())).toList();
    return new CashTransferData(
        null,
        null,
        OPERATION,
        "PREVIEW",
        date.toString(),
        source,
        destination,
        actor.getId(),
        actor.getUsername(),
        null,
        null,
        true,
        totals,
        totals,
        List.copyOf(currencies));
  }

  private List<ConfiguredCurrency> configuredCurrencies() {
    return jdbc.query(
        "SELECT code,decimal_places FROM m_organisation_currency ORDER BY code",
        (rs, i) -> {
          int precision = rs.getInt(2);
          if (precision < 0 || precision > 6)
            throw invalid(
                "currency.precision", "Currency precision exceeds native cash storage limits.");
          return new ConfiguredCurrency(rs.getString(1), precision);
        });
  }

  private List<Denomination> catalog(ConfiguredCurrency currency) {
    return jdbc.query(
        "SELECT identifier,denomination_type,value FROM m_service_payment_denomination WHERE"
            + " currency_code=? AND active=true ORDER BY value DESC,identifier",
        (rs, i) -> {
          String type = rs.getString(2);
          BigDecimal value = rs.getBigDecimal(3);
          if (value == null
              || value.signum() <= 0
              || value.stripTrailingZeros().scale() > currency.precision()
              || !List.of("BANKNOTE", "BILL", "NOTE", "COIN").contains(type))
            throw invalid(
                "denomination.configuration", "Cash denomination configuration is invalid.");
          return new Denomination(
              rs.getString(1),
              "COIN".equals(type) ? "COIN" : "BANKNOTE",
              value,
              0,
              BigDecimal.ZERO);
        },
        currency.code());
  }

  private Cashier source(AppUser actor, LocalDate date) {
    if (actor.getStaffId() == null)
      throw invalid("source.forbidden", "Authenticated user has no assigned staff cashier.");
    var sources =
        eligible(date, actor.getOffice().getHierarchy() + "%").stream()
            .filter(c -> c.staffId().equals(actor.getStaffId()))
            .toList();
    if (sources.size() != 1)
      throw invalid(
          "source.assignment",
          "Exactly one active open cashier assignment is required for the authenticated staff"
              + " member.");
    return sources.get(0);
  }

  private List<Cashier> destinations(Cashier source, LocalDate date) {
    return eligible(date, officeHierarchy(source.officeId()) + "%").stream()
        .filter(
            c ->
                c.officeId().equals(source.officeId())
                    && !c.cashierId().equals(source.cashierId())
                    && !c.staffId().equals(source.staffId()))
        .toList();
  }

  private String officeHierarchy(Long officeId) {
    return jdbc.queryForObject("SELECT hierarchy FROM m_office WHERE id=?", String.class, officeId);
  }

  private List<Cashier> eligible(LocalDate date, String hierarchy) {
    return jdbc.query(
        """
        SELECT c.id,c.staff_id,s.display_name,t.id,t.name,o.id,o.name FROM m_cashiers c
        JOIN m_tellers t ON t.id=c.teller_id JOIN m_staff s ON s.id=c.staff_id JOIN m_office o ON o.id=t.office_id
        WHERE o.hierarchy LIKE ? AND t.state=300 AND s.is_active=true
        AND (t.valid_from IS NULL OR t.valid_from<=?) AND (t.valid_to IS NULL OR t.valid_to>=?)
        AND (c.start_date IS NULL OR c.start_date<=?) AND (c.end_date IS NULL OR c.end_date>=?)
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
        hierarchy,
        date,
        date,
        date,
        date,
        date,
        date);
  }

  private CashTransferData stored(Long id, AppUser actor) {
    if (id == null || id <= 0) throw invalid("not.found", "Transfer not found.");
    var rows =
        jdbc.query(
            """
            SELECT p.snapshot FROM m_cash_teller_transfer p JOIN m_office o ON o.id=p.office_id
            JOIN m_cashiers s ON s.id=p.source_cashier_id JOIN m_cashiers d ON d.id=p.destination_cashier_id
            WHERE p.id=? AND p.status='COMPLETED' AND o.hierarchy LIKE ?
            AND (p.created_by=? OR s.staff_id=? OR d.staff_id=? OR ?)
            """,
            (rs, i) -> rs.getString(1),
            id,
            actor.getOffice().getHierarchy() + "%",
            actor.getId(),
            actor.getStaffId(),
            actor.getStaffId(),
            actor.hasAnyPermission("READ_GLOBAL_SETTLEMENT"));
    if (rows.isEmpty())
      throw invalid("not.found", "Transfer not found in the authenticated user's scope.");
    return GSON.fromJson(rows.get(0), CashTransferData.class);
  }

  private AppUser user(String action) {
    AppUser actor = security.authenticatedUser();
    actor.validateHasPermissionTo(action + "_" + RESOURCE);
    if (actor.getOffice() == null || actor.getOffice().getHierarchy() == null)
      throw invalid("office.forbidden", "An authorized office is required.");
    return actor;
  }

  private Long movement(
      Long cashier, boolean outgoing, Currency currency, String date, String reference) {
    return CashCustodyPosting.nativeMovement(
        tellers,
        jsonHelper,
        cashier,
        outgoing,
        currency.total(),
        currency.currencyCode(),
        LocalDate.parse(date),
        "Cash transfer between tellers " + reference);
  }

  private void verifyMovement(Long id, Long cashier, int type, Currency currency, String date) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT COUNT(*)=1 FROM m_cashier_transactions WHERE id=? AND cashier_id=? AND"
                + " txn_type=? AND currency_code=? AND txn_amount=? AND txn_date=?",
            Boolean.class,
            id,
            cashier,
            type,
            currency.currencyCode(),
            currency.total(),
            LocalDate.parse(date))))
      throw invalid(
          "movement.incomplete", "Both native cashier movements must be completed synchronously.");
  }

  private void lockInventory() {
    // Existing native and plugin writers do not uniformly share per-drawer locks. PostgreSQL table
    // locks prevent changes to every authoritative composition source until both native legs
    // commit.
    // This is intentionally coarse; replace only once all cash writers share a finer lock protocol.
    jdbc.execute(
        "LOCK TABLE m_cash_allocation,m_cash_allocation_denomination,m_cashiers,m_tellers,"
            + "m_cashier_transactions,m_cashier_reconciliation,m_cashier_reconciliation_check,"
            + "m_cash_operation_transaction,m_cash_operation_denomination,m_base_teller_deposit,"
            + "m_base_teller_deposit_cash_detail,m_base_teller_savings_opening,m_base_teller_savings_opening_cash_detail,"
            + "m_base_teller_credit_payment,m_base_teller_credit_payment_cash_detail,m_base_teller_returned_check_payment,"
            + "m_base_teller_returned_check_payment_cash_detail,m_service_payment,m_service_payment_cash_detail,"
            + "m_savings_account_transaction,m_cash_exchange,m_cash_exchange_detail,"
            + "m_transaction_reversal,m_transaction_reversal_denomination,m_cash_drawer_pickup,"
            + "m_cash_drawer_pickup_currency,m_cash_drawer_pickup_cash,m_cash_teller_transfer,"
            + "m_cash_teller_transfer_currency,m_cash_teller_transfer_denomination,m_service_payment_denomination,m_organisation_currency,m_staff"
            + " IN SHARE ROW EXCLUSIVE MODE");
  }

  private record ConfiguredCurrency(String code, int precision) {}

  private record Existing(Long id, String fingerprint, Long actor) {}
}
