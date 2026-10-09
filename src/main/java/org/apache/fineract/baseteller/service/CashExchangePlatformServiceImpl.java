package org.apache.fineract.baseteller.service;

import static org.apache.fineract.baseteller.validation.CashExchangeValidator.invalid;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.CashExchangeContextData;
import org.apache.fineract.baseteller.data.CashExchangeCurrencyData;
import org.apache.fineract.baseteller.data.CashExchangeData;
import org.apache.fineract.baseteller.data.CashExchangeDenominationData;
import org.apache.fineract.baseteller.data.CashExchangeInventoryData;
import org.apache.fineract.baseteller.data.CashExchangeInventoryLineData;
import org.apache.fineract.baseteller.data.CashExchangePreviewData;
import org.apache.fineract.baseteller.data.CashExchangeQuantityData;
import org.apache.fineract.baseteller.data.CashExchangeRequest;
import org.apache.fineract.baseteller.data.CashExchangeTellerData;
import org.apache.fineract.baseteller.exception.CashExchangeNotFoundException;
import org.apache.fineract.baseteller.validation.CashExchangeValidator;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CashExchangePlatformServiceImpl implements CashExchangePlatformService {
  private static final String RESOURCE = "BASE_TELLER_CASH_EXCHANGE";
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final PlatformSecurityContext security;
  private final CashExchangeInventory inventory;

  @Override
  @Transactional(readOnly = true)
  public CashExchangeContextData context() {
    AppUser user = security.authenticatedUser();
    user.validateHasReadPermission(RESOURCE);
    return new CashExchangeContextData(
        tellers(user, DateUtils.getBusinessLocalDate()), currencies());
  }

  @Override
  @Transactional(readOnly = true)
  public CashExchangeInventoryData denominations(Long cashierId, String currencyCode) {
    AppUser user = security.authenticatedUser();
    user.validateHasReadPermission(RESOURCE);
    LocalDate date = DateUtils.getBusinessLocalDate();
    authorized(cashierId, user, date);
    CashExchangeCurrencyData currency = currency(currencyCode);
    Map<String, Long> quantities = inventory.quantities(cashierId, currency.code(), date);
    return new CashExchangeInventoryData(
        cashierId,
        currency.code(),
        currency.decimalPlaces(),
        catalog(currency).stream()
            .map(
                d ->
                    new CashExchangeInventoryLineData(
                        d.denominationId(),
                        d.type(),
                        d.value(),
                        quantities.getOrDefault(d.denominationId(), 0L)))
            .toList());
  }

  @Override
  @Transactional(readOnly = true)
  public CashExchangePreviewData preview(CashExchangeRequest request) {
    AppUser user = security.authenticatedUser();
    user.validateHasCreatePermission(RESOURCE);
    return prepare(request, user, false).preview();
  }

  @Override
  @Transactional
  public CashExchangeData create(CashExchangeRequest request) {
    AppUser user = security.authenticatedUser();
    user.validateHasCreatePermission(RESOURCE);
    CashExchangeValidator.validate(request, true);
    LocalDate date = DateUtils.getBusinessLocalDate();
    CashExchangeTellerData teller = authorized(request.cashierId(), user, date);
    // Serialize every exchange key first, including competing requests aimed at different drawers.
    // A unique database constraint remains the final backstop. No catch/requery in an aborted
    // PostgreSQL transaction.
    jdbc.queryForObject("SELECT id FROM m_cash_exchange_mutex WHERE id=1 FOR UPDATE", Long.class);
    String fingerprint = fingerprint(request);
    List<Existing> existing =
        jdbc.query(
            "SELECT id,request_fingerprint FROM m_cash_exchange WHERE idempotency_key=?",
            (rs, row) -> new Existing(rs.getLong(1), rs.getString(2)),
            request.idempotencyKey());
    if (!existing.isEmpty()) {
      if (!fingerprint.equals(existing.get(0).fingerprint()))
        throw invalid(
            "idempotency.conflict", "idempotencyKey was already used for a different request.");
      return stored(existing.get(0).id(), user);
    }
    // Allocation takes office then cashier; retain that ordering to avoid lock inversion.
    jdbc.queryForObject(
        "SELECT id FROM m_office WHERE id=? FOR UPDATE", Long.class, teller.officeId());
    jdbc.queryForObject("SELECT id FROM m_cashiers WHERE id=? FOR UPDATE", Long.class, teller.id());
    jdbc.queryForList(
        "SELECT id FROM m_service_payment_denomination WHERE currency_code=? FOR SHARE",
        Long.class,
        request.currencyCode().toUpperCase(Locale.ROOT));
    Prepared prepared = prepare(request, user, true);
    String receipt =
        "CE-"
            + date
            + "-"
            + hash(request.idempotencyKey()).substring(0, 20).toUpperCase(Locale.ROOT);
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO m_cash_exchange (idempotency_key,request_fingerprint,receipt_number,status,business_date,office_id,teller_id,cashier_id,teller_name,currency_code,received_total,delivered_total,created_by,created_by_username)
            VALUES (?,?,?,'COMPLETED',?,?,?,?,?,?,?,?,?,?) RETURNING id
            """,
            Long.class,
            request.idempotencyKey(),
            fingerprint,
            receipt,
            date,
            teller.officeId(),
            teller.tellerId(),
            teller.id(),
            teller.name(),
            prepared.preview().currencyCode(),
            prepared.preview().receivedAmount(),
            prepared.preview().deliveredAmount(),
            user.getId(),
            user.getUsername());
    details(id, "RECEIVED", prepared.preview().receivedDenominations());
    details(id, "DELIVERED", prepared.preview().deliveredDenominations());
    return stored(id, user);
  }

  @Override
  @Transactional(readOnly = true)
  public CashExchangeData retrieve(Long id) {
    AppUser user = security.authenticatedUser();
    user.validateHasReadPermission(RESOURCE);
    return stored(id, user);
  }

  @Override
  @Transactional(readOnly = true)
  public CashExchangeData receipt(Long id) {
    AppUser user = security.authenticatedUser();
    user.validateHasPermissionTo("REPRINT_" + RESOURCE);
    return stored(id, user);
  }

  private Prepared prepare(CashExchangeRequest request, AppUser user, boolean create) {
    CashExchangeValidator.validate(request, create);
    LocalDate date = DateUtils.getBusinessLocalDate();
    authorized(request.cashierId(), user, date);
    CashExchangeCurrencyData currency = currency(request.currencyCode());
    Map<String, CashExchangeDenominationData> configured = new HashMap<>();
    catalog(currency).forEach(d -> configured.put(d.denominationId(), d));
    List<CashExchangeDenominationData> received =
        lines(request.receivedDenominations(), configured);
    List<CashExchangeDenominationData> delivered =
        lines(request.deliveredDenominations(), configured);
    BigDecimal receivedTotal = CashExchangeValidator.total(received),
        deliveredTotal = CashExchangeValidator.total(delivered);
    CashExchangeValidator.balanced(receivedTotal, deliveredTotal);
    Map<String, Long> available = inventory.quantities(request.cashierId(), currency.code(), date);
    for (var line : delivered)
      if (line.quantity() > available.getOrDefault(line.denominationId(), 0L))
        throw invalid(
            "inventory.insufficient",
            "Insufficient recorded inventory for denomination " + line.denominationId());
    for (var line : received) {
      long out =
          delivered.stream()
              .filter(d -> d.denominationId().equals(line.denominationId()))
              .mapToLong(CashExchangeDenominationData::quantity)
              .sum();
      try {
        Math.addExact(available.getOrDefault(line.denominationId(), 0L) - out, line.quantity());
      } catch (ArithmeticException e) {
        throw invalid(
            "inventory.overflow", "Exchange would exceed supported integer inventory range.");
      }
    }
    return new Prepared(
        new CashExchangePreviewData(
            request.cashierId(),
            currency.code(),
            receivedTotal,
            deliveredTotal,
            true,
            BigDecimal.ZERO,
            received,
            delivered));
  }

  private List<CashExchangeDenominationData> lines(
      List<CashExchangeQuantityData> requested,
      Map<String, CashExchangeDenominationData> configured) {
    List<CashExchangeDenominationData> result = new ArrayList<>();
    for (var line : requested) {
      var denomination = configured.get(line.denominationId());
      if (denomination == null)
        throw invalid(
            "denomination.unsupported",
            "Denomination is missing, disabled or belongs to another currency.");
      if (line.quantity() > 0)
        result.add(
            new CashExchangeDenominationData(
                denomination.denominationId(),
                denomination.type(),
                denomination.value(),
                line.quantity(),
                denomination.value().multiply(BigDecimal.valueOf(line.quantity()))));
    }
    result.sort(Comparator.comparing(CashExchangeDenominationData::denominationId));
    return List.copyOf(result);
  }

  private List<CashExchangeCurrencyData> currencies() {
    return jdbc.query(
        "SELECT code,name,decimal_places FROM m_organisation_currency ORDER BY code",
        (rs, row) -> new CashExchangeCurrencyData(rs.getString(1), rs.getString(2), rs.getInt(3)));
  }

  private CashExchangeCurrencyData currency(String code) {
    if (code == null) throw invalid("currency.required", "currencyCode is required.");
    return currencies().stream()
        .filter(c -> c.code().equals(code.toUpperCase(Locale.ROOT)))
        .findFirst()
        .orElseThrow(
            () -> invalid("currency.unsupported", "Currency is not enabled for the tenant."));
  }

  private List<CashExchangeDenominationData> catalog(CashExchangeCurrencyData currency) {
    List<CashExchangeDenominationData> result =
        jdbc.query(
            "SELECT identifier,denomination_type,value FROM m_service_payment_denomination WHERE"
                + " currency_code=? AND active=true ORDER BY value DESC",
            (rs, row) ->
                new CashExchangeDenominationData(
                    rs.getString(1), rs.getString(2), rs.getBigDecimal(3), 0L, BigDecimal.ZERO),
            currency.code());
    for (var d : result)
      if (d.value().signum() <= 0
          || d.value().stripTrailingZeros().scale() > currency.decimalPlaces()
          || d.type() == null
          || d.type().isBlank())
        throw invalid(
            "denomination.configuration.invalid",
            "Configured denomination is invalid for this currency.");
    return result;
  }

  private List<CashExchangeTellerData> tellers(AppUser user, LocalDate date) {
    if (user.getOffice() == null)
      throw invalid("office.required", "Authenticated office is required.");
    Map<String, Object> p =
        new HashMap<>(Map.of("date", date, "hierarchy", user.getOffice().getHierarchy() + "%"));
    // Existing inventory/history convention: assigned staff see their own drawers; global
    // settlement readers may supervise.
    boolean restrict =
        user.getStaffId() != null && !user.hasAnyPermission("READ_GLOBAL_SETTLEMENT");
    if (restrict) p.put("staff", user.getStaffId());
    return named.query(
        "SELECT c.id,c.teller_id,s.display_name,t.office_id,COALESCE((SELECT MAX(au.username) FROM"
            + " m_appuser au WHERE au.staff_id=c.staff_id),CAST(c.staff_id AS varchar)) code FROM"
            + " m_cashiers c JOIN m_tellers t ON t.id=c.teller_id JOIN m_staff s ON s.id=c.staff_id"
            + " JOIN m_office o ON o.id=t.office_id WHERE o.hierarchy LIKE :hierarchy AND"
            + " t.state=300 AND (t.valid_from IS NULL OR t.valid_from<=:date) AND (t.valid_to IS"
            + " NULL OR t.valid_to>=:date) AND (c.start_date IS NULL OR c.start_date<=:date) AND"
            + " (c.end_date IS NULL OR c.end_date>=:date)"
            + (restrict ? " AND c.staff_id=:staff" : "")
            + " ORDER BY s.display_name,c.id",
        p,
        (rs, row) ->
            new CashExchangeTellerData(
                rs.getLong(1),
                rs.getLong(2),
                rs.getString("code"),
                rs.getString(3),
                rs.getLong(4)));
  }

  private CashExchangeTellerData authorized(Long id, AppUser user, LocalDate date) {
    return tellers(user, date).stream()
        .filter(t -> t.id().equals(id))
        .findFirst()
        .orElseThrow(
            () ->
                invalid(
                    "cashier.forbidden",
                    "Cashier is missing, inactive or outside the authenticated user's assignment"
                        + " and office scope."));
  }

  private CashExchangeData stored(Long id, AppUser user) {
    if (id == null || id <= 0) throw notFound();
    Map<String, Object> p = Map.of("id", id, "hierarchy", user.getOffice().getHierarchy() + "%");
    List<CashExchangeData> rows =
        named.query(
            "SELECT e.* FROM m_cash_exchange e JOIN m_office o ON o.id=e.office_id WHERE e.id=:id"
                + " AND o.hierarchy LIKE :hierarchy",
            p,
            (rs, row) ->
                new CashExchangeData(
                    rs.getLong("id"),
                    rs.getString("receipt_number"),
                    rs.getString("status"),
                    rs.getObject("business_date", LocalDate.class),
                    rs.getLong("office_id"),
                    rs.getLong("teller_id"),
                    rs.getLong("cashier_id"),
                    rs.getString("teller_name"),
                    rs.getString("currency_code"),
                    rs.getBigDecimal("received_total"),
                    rs.getBigDecimal("delivered_total"),
                    rs.getLong("created_by"),
                    rs.getString("created_by_username"),
                    rs.getTimestamp("created_on_utc").toInstant().atOffset(ZoneOffset.UTC),
                    storedLines(id, "RECEIVED"),
                    storedLines(id, "DELIVERED")));
    if (rows.isEmpty()) throw notFound();
    // Historical receipts survive assignment expiry, but ordinary assigned cashiers cannot read
    // another drawer.
    if (user.getStaffId() != null && !user.hasAnyPermission("READ_GLOBAL_SETTLEMENT")) {
      Long owner =
          jdbc.queryForObject(
              "SELECT staff_id FROM m_cashiers WHERE id=?", Long.class, rows.get(0).cashierId());
      if (!user.getStaffId().equals(owner)) throw notFound();
    }
    return rows.get(0);
  }

  private void details(Long id, String direction, List<CashExchangeDenominationData> lines) {
    for (var d : lines)
      jdbc.update(
          "INSERT INTO m_cash_exchange_detail"
              + " (exchange_id,direction,denomination_identifier,denomination_type,denomination_value,quantity,line_total)"
              + " VALUES (?,?,?,?,?,?,?)",
          id,
          direction,
          d.denominationId(),
          d.type(),
          d.value(),
          d.quantity(),
          d.amount());
  }

  private List<CashExchangeDenominationData> storedLines(Long id, String direction) {
    return jdbc.query(
        "SELECT denomination_identifier,denomination_type,denomination_value,quantity,line_total"
            + " FROM m_cash_exchange_detail WHERE exchange_id=? AND direction=? ORDER BY"
            + " denomination_identifier",
        (rs, row) ->
            new CashExchangeDenominationData(
                rs.getString(1),
                rs.getString(2),
                rs.getBigDecimal(3),
                rs.getLong(4),
                rs.getBigDecimal(5)),
        id,
        direction);
  }

  private static String fingerprint(CashExchangeRequest r) {
    return hash(
        new Gson()
            .toJson(
                new CashExchangeRequest(
                    r.cashierId(),
                    r.currencyCode().toUpperCase(Locale.ROOT),
                    canonical(r.receivedDenominations()),
                    canonical(r.deliveredDenominations()),
                    r.idempotencyKey())));
  }

  private static List<CashExchangeQuantityData> canonical(List<CashExchangeQuantityData> lines) {
    return lines.stream()
        .filter(d -> d.quantity() > 0)
        .sorted(Comparator.comparing(CashExchangeQuantityData::denominationId))
        .toList();
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static CashExchangeNotFoundException notFound() {
    return new CashExchangeNotFoundException();
  }

  private record Prepared(CashExchangePreviewData preview) {}

  private record Existing(Long id, String fingerprint) {}
}
