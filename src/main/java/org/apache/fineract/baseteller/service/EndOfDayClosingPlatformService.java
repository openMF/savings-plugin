package org.apache.fineract.baseteller.service;

import static org.apache.fineract.baseteller.service.EndOfDayClosingCalculator.invalid;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.*;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.*;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.Currency;
import org.apache.fineract.baseteller.data.EndOfDayClosingRequest.*;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.teller.service.TellerManagementReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EndOfDayClosingPlatformService {
  private static final String RESOURCE = "BASE_TELLER_END_OF_DAY_CLOSING";
  private static final Gson GSON = new Gson();
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final PlatformSecurityContext security;
  private final CashInventoryReadPlatformServiceImpl inventory;
  private final CashManagementPlatformServiceImpl closing;
  private final TellerManagementReadPlatformService tellerService;
  private final EndOfDayClosingAuthorization authorization;

  @Transactional(readOnly = true)
  public Context context() {
    AppUser user = user("READ");
    LocalDate date = DateUtils.getBusinessLocalDate();
    List<Cashier> cashiers = cashiers(user, date);
    List<Long> completed =
        cashiers.isEmpty()
            ? List.of()
            : named.queryForList(
                "SELECT id FROM m_end_of_day_closing WHERE cashier_id IN (:ids) AND"
                    + " business_date=:date ORDER BY id",
                Map.of("ids", cashiers.stream().map(Cashier::cashierId).toList(), "date", date),
                Long.class);
    return new Context(date.toString(), cashiers, currencies(), completed);
  }

  @Transactional(readOnly = true)
  public List<Check> checks(Long cashierId, String currency) {
    AppUser user = user("READ");
    Cashier cashier = authorizedCashier(cashierId, user, DateUtils.getBusinessLocalDate());
    return eligibleChecks(cashier).stream()
        .filter(c -> currency == null || c.currencyCode().equals(currency))
        .toList();
  }

  @Transactional(readOnly = true)
  public EndOfDayClosingData preview(EndOfDayClosingRequest request) {
    AppUser user = user("CREATE");
    return prepare(request, user, false);
  }

  @Transactional
  public EndOfDayClosingData complete(
      EndOfDayClosingRequest request, String username, String credential) {
    // Keep the JPA transaction shared with native posting. Fineract deliberately disallows
    // Spring custom isolation on its EclipseLink manager; this PostgreSQL transaction-local
    // setting is the first SQL statement and never changes pooled/session-wide defaults.
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    AppUser user = user("CREATE");
    validate(request, true);
    LocalDate date = LocalDate.parse(request.businessDate());
    Cashier cashier = authorizedCashier(request.cashierId(), user, date);
    jdbc.queryForObject("SELECT id FROM m_cash_exchange_mutex WHERE id=1 FOR UPDATE", Long.class);
    // PostgreSQL custody writers do not all take a common drawer lock. Serialize the final
    // snapshot with *all* existing writers, including native commands and check transitions.
    // Coarse locks are deliberate until all those workflows share a per-drawer protocol.
    jdbc.execute(
        "LOCK TABLE m_cash_allocation, m_cashiers, m_tellers, m_cashier_transactions,"
            + " m_cashier_reconciliation, m_cashier_reconciliation_check,"
            + " m_cash_operation_transaction, m_cash_operation_check, m_base_teller_deposit,"
            + " m_base_teller_deposit_check_detail, m_base_teller_savings_opening,"
            + " m_base_teller_credit_payment, m_base_teller_credit_payment_check,"
            + " m_base_teller_returned_check, m_base_teller_returned_check_payment,"
            + " m_service_payment, m_savings_account_transaction, m_loan_transaction,"
            + " m_client_transaction, m_account_transfer_transaction, m_payment_detail,"
            + " m_payment_type, m_savings_account, m_loan, m_client, m_staff, m_appuser,"
            + " m_service_payment_denomination, m_organisation_currency, m_end_of_day_closing,"
            + " m_end_of_day_closing_check,m_transaction_reversal,m_transaction_reversal_check,m_transaction_reversal_denomination,m_cash_exchange,m_cash_exchange_detail"
            + " IN SHARE ROW EXCLUSIVE MODE");
    cashier = authorizedCashier(request.cashierId(), user, date);
    List<String> codes =
        request.currencies().stream().map(CurrencyDeclaration::currencyCode).toList();
    AppUser authorizer = authorization.authorize(username, credential, user, cashier, date, codes);
    String authorizedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
    String fingerprint = fingerprint(request);
    List<Existing> previous =
        jdbc.query(
            "SELECT id,request_fingerprint FROM m_end_of_day_closing WHERE idempotency_key=?",
            (rs, row) -> new Existing(rs.getLong(1), rs.getString(2)),
            request.idempotencyKey());
    if (!previous.isEmpty()) {
      if (!fingerprint.equals(previous.get(0).fingerprint()))
        throw invalid("idempotency.conflict", "Idempotency key was used for another declaration.");
      return stored(previous.get(0).id(), user);
    }
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT COUNT(*)>0 FROM m_cashier_reconciliation WHERE cashier_id=? AND"
                + " business_date=?",
            Boolean.class,
            cashier.cashierId(),
            date)))
      throw invalid("already.closed", "The cashier already has a closing for this business date.");
    EndOfDayClosingData prepared = prepare(request, user, true);
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO m_end_of_day_closing (idempotency_key,request_fingerprint,business_date,office_id,cashier_id,snapshot)
            VALUES (?,?,?,?,?,?) RETURNING id
            """,
            Long.class,
            request.idempotencyKey(),
            fingerprint,
            date,
            cashier.officeId(),
            cashier.cashierId(),
            "{}");
    List<Balance> balances = new ArrayList<>();
    for (Balance b : prepared.balancesByCurrency()) {
      String currencyKey = "EOD:" + id + ":" + b.currencyCode();
      var denominations =
          b.denominations().stream()
              .map(d -> new BaseTellerDenominationData(d.denominationId(), d.value(), d.quantity()))
              .toList();
      var deposits =
          b.checks().stream()
              .filter(c -> c.source().equals("DEPOSIT"))
              .map(c -> new CashierCheckData(c.id(), c.bank(), c.reference(), c.amount()))
              .toList();
      var nativeRequest =
          new CashierClosingRequest(
              currencyKey,
              cashier.cashierId(),
              date,
              b.currencyCode(),
              denominations,
              deposits.stream().map(CashierCheckData::id).toList());
      var cashPosition =
          inventory.inventoryForUser(
              "TELLER:" + cashier.cashierId(), "CASH", b.currencyCode(), false, user);
      BigDecimal opening =
          cashPosition.stream()
              .map(CashInventoryData::initialBalance)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal inflows =
          cashPosition.stream()
              .map(CashInventoryData::accumulatedInflows)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal outflows =
          cashPosition.stream()
              .map(CashInventoryData::accumulatedOutflows)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal cutoffs =
          cashPosition.stream()
              .map(CashInventoryData::cutOffs)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      var position =
          new CashierClosingContextData(
              date,
              cashier.officeId(),
              cashier.officeName(),
              cashier.tellerId(),
              cashier.tellerName(),
              cashier.cashierId(),
              cashier.cashierName(),
              b.currencyCode(),
              opening,
              inflows,
              outflows,
              cutoffs,
              b.expectedTotal(),
              deposits,
              CashManagementStatus.OPEN);
      // Use the same authoritative summary that native settlement validation uses.
      // Physical count and discrepancy are audit metadata, never the settlement amount.
      BigDecimal settledCash =
          EndOfDayClosingCalculator.amount(
              tellerService
                  .retrieveCashierTransactionsWithSummary(
                      cashier.cashierId(),
                      false,
                      null,
                      null,
                      b.currencyCode(),
                      org.apache.fineract.infrastructure.core.service.SearchParameters.builder()
                          .build())
                  .getNetCash(),
              b.decimalPlaces());
      // WEB-1232 owns both reconciliation persistence and the single native settlement.
      CashierClosingReceiptData receipt =
          closing.postClosing(
              nativeRequest,
              position,
              tellerService.findCashier(cashier.cashierId()),
              deposits,
              b.declaredCash(),
              b.selectedChecks(),
              b.declaredTotal(),
              user,
              authorizer,
              settledCash);
      jdbc.update(
          "UPDATE m_cashier_reconciliation SET voucher_total=?,end_of_day_closing_id=? WHERE id=?",
          b.vouchers(),
          id,
          receipt.id());
      for (var check : b.checks())
        jdbc.update(
            """
            INSERT INTO m_end_of_day_closing_check (closing_id,reconciliation_id,source_type,source_id,currency_code,amount)
            VALUES (?,?,?,?,?,?)
            """,
            id,
            receipt.id(),
            check.source(),
            check.id(),
            check.currencyCode(),
            check.amount());
      balances.add(
          new Balance(
              b.currencyCode(),
              b.decimalPlaces(),
              b.expectedCash(),
              b.expectedChecks(),
              b.expectedVouchers(),
              b.expectedTotal(),
              b.coinTotal(),
              b.billTotal(),
              b.declaredCash(),
              b.selectedChecks(),
              b.vouchers(),
              b.declaredTotal(),
              b.shortage(),
              b.overage(),
              b.reconciliationStatus(),
              b.denominations(),
              b.checks(),
              receipt.id(),
              settledCash));
    }
    var result =
        new EndOfDayClosingData(
            id,
            "EOD-" + date + "-" + id,
            "COMPLETED",
            date.toString(),
            cashier.officeId(),
            cashier.officeName(),
            cashier.tellerId(),
            cashier.tellerName(),
            cashier.cashierId(),
            cashier.staffId(),
            cashier.cashierName(),
            user.getId(),
            authorizer.getId(),
            authorizer.getUsername(),
            authorizedAt,
            OffsetDateTime.now(ZoneOffset.UTC).toString(),
            List.copyOf(balances));
    jdbc.update("UPDATE m_end_of_day_closing SET snapshot=? WHERE id=?", GSON.toJson(result), id);
    return result;
  }

  @Transactional(readOnly = true)
  public EndOfDayClosingData retrieve(Long id, boolean report) {
    return stored(id, user(report ? "REPRINT" : "READ"));
  }

  private EndOfDayClosingData prepare(
      EndOfDayClosingRequest request, AppUser user, boolean complete) {
    validate(request, complete);
    LocalDate date = LocalDate.parse(request.businessDate());
    Cashier cashier = authorizedCashier(request.cashierId(), user, date);
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT COUNT(*)>0 FROM m_cashier_reconciliation WHERE cashier_id=? AND"
                + " business_date=?",
            Boolean.class,
            cashier.cashierId(),
            date)))
      throw invalid("already.closed", "The cashier already has a closing for this business date.");
    List<Currency> currencies = currencies();
    if (currencies.isEmpty())
      throw invalid("currency.missing", "No tenant currencies are configured.");
    Set<String> declared = new HashSet<>();
    for (var c : request.currencies()) {
      if (c == null || c.currencyCode() == null || !declared.add(c.currencyCode()))
        throw invalid("currency.invalid", "Currency declarations must be unique and configured.");
    }
    if (!declared.equals(new HashSet<>(currencies.stream().map(Currency::currencyCode).toList())))
      throw invalid(
          "currency.incomplete", "Declare every configured tenant currency exactly once.");
    List<CashInventoryData> expected =
        inventory.inventoryForUser("TELLER:" + cashier.cashierId(), null, null, false, user);
    List<Check> checks = eligibleChecks(cashier);
    List<Balance> balances = new ArrayList<>();
    for (Currency currency : currencies) {
      BigDecimal cash =
          expected.stream()
              .filter(
                  v ->
                      v.currencyCode().equals(currency.currencyCode())
                          && v.inventoryType() == CashInventoryType.CASH)
              .map(CashInventoryData::balance)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal checkTotal =
          expected.stream()
              .filter(
                  v ->
                      v.currencyCode().equals(currency.currencyCode())
                          && v.inventoryType() == CashInventoryType.CHECK)
              .map(CashInventoryData::balance)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      List<Check> eligible =
          checks.stream().filter(c -> c.currencyCode().equals(currency.currencyCode())).toList();
      BigDecimal catalogTotal =
          eligible.stream().map(Check::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
      if (catalogTotal.compareTo(checkTotal) != 0)
        throw invalid(
            "custody.inconsistent",
            "Check custody does not reconcile with the authoritative inventory.");
      CurrencyDeclaration declaration =
          request.currencies().stream()
              .filter(c -> c.currencyCode().equals(currency.currencyCode()))
              .findFirst()
              .orElseThrow();
      balances.add(
          EndOfDayClosingCalculator.calculate(declaration, currency, cash, checkTotal, eligible));
    }
    return new EndOfDayClosingData(
        null,
        null,
        "PREVIEW",
        date.toString(),
        cashier.officeId(),
        cashier.officeName(),
        cashier.tellerId(),
        cashier.tellerName(),
        cashier.cashierId(),
        cashier.staffId(),
        cashier.cashierName(),
        user.getId(),
        null,
        null,
        null,
        null,
        List.copyOf(balances));
  }

  private List<Currency> currencies() {
    return jdbc.query(
        "SELECT code,decimal_places FROM m_organisation_currency ORDER BY code",
        (rs, row) -> {
          String code = rs.getString(1);
          int places = rs.getInt(2);
          if (places < 0 || places > 6)
            throw invalid("currency.invalid", "Currency precision is unsupported.");
          List<CashExchangeDenominationData> catalog =
              jdbc.query(
                  "SELECT identifier,denomination_type,value FROM m_service_payment_denomination"
                      + " WHERE currency_code=? AND active=true ORDER BY identifier",
                  (ds, n) ->
                      new CashExchangeDenominationData(
                          ds.getString(1),
                          ds.getString(2),
                          ds.getBigDecimal(3),
                          0L,
                          BigDecimal.ZERO),
                  code);
          for (var d : catalog) {
            EndOfDayClosingCalculator.amount(d.value(), places);
            if (d.value().signum() <= 0
                || !("COIN".equals(d.type()) || EndOfDayClosingCalculator.isBill(d.type())))
              throw invalid("catalog.invalid", "Denomination catalog is invalid.");
          }
          return new Currency(code, places, catalog);
        });
  }

  private List<Cashier> cashiers(AppUser user, LocalDate date) {
    if (user.getOffice() == null)
      throw invalid("office.required", "Authenticated office is required.");
    Map<String, Object> params =
        new HashMap<>(Map.of("hierarchy", user.getOffice().getHierarchy() + "%", "date", date));
    boolean supervisor = user.hasAnyPermission("READ_GLOBAL_SETTLEMENT");
    if (!supervisor && user.getStaffId() == null) return List.of();
    if (!supervisor) params.put("staff", user.getStaffId());
    return named.query(
        """
        SELECT c.id,c.staff_id,s.display_name,t.id teller_id,t.name teller_name,o.id office_id,o.name office_name,
        EXISTS(SELECT 1 FROM m_cashier_reconciliation r WHERE r.cashier_id=c.id AND r.business_date=:date) closed
        FROM m_cashiers c JOIN m_tellers t ON t.id=c.teller_id JOIN m_staff s ON s.id=c.staff_id
        JOIN m_office o ON o.id=t.office_id WHERE o.hierarchy LIKE :hierarchy AND t.state=300
        AND (t.valid_from IS NULL OR t.valid_from<=:date) AND (t.valid_to IS NULL OR t.valid_to>=:date)
        AND (c.start_date IS NULL OR c.start_date<=:date) AND (c.end_date IS NULL OR c.end_date>=:date)
        """
            + (supervisor ? "" : " AND c.staff_id=:staff")
            + " ORDER BY c.id",
        params,
        (rs, row) ->
            new Cashier(
                rs.getLong(1),
                rs.getLong(2),
                rs.getString(3),
                rs.getLong(4),
                rs.getString(5),
                rs.getLong(6),
                rs.getString(7),
                rs.getBoolean("closed")));
  }

  private Cashier authorizedCashier(Long id, AppUser user, LocalDate date) {
    return cashiers(user, date).stream()
        .filter(c -> c.cashierId().equals(id))
        .findFirst()
        .orElseThrow(
            () ->
                invalid(
                    "cashier.forbidden",
                    "Cashier is inactive or outside assignment and office scope."));
  }

  private List<Check> eligibleChecks(Cashier cashier) {
    Map<String, Object> p =
        Map.of(
            "cashier",
            cashier.cashierId(),
            "office",
            cashier.officeId(),
            "date",
            DateUtils.getBusinessLocalDate());
    return named.query(
        "SELECT q.* FROM ("
            + CHECKS
            + ") q WHERE q.cashier_id=:cashier AND q.office_id=:office AND q.accepted_date<=:date"
            + " AND NOT EXISTS (SELECT 1 FROM m_end_of_day_closing_check x WHERE"
            + " x.source_type=q.source_type AND x.source_id=q.id) ORDER BY"
            + " q.currency_code,q.source_type,q.id",
        p,
        (rs, row) ->
            new Check(
                rs.getString("source_type"),
                rs.getLong("id"),
                rs.getDate("accepted_date").toString(),
                rs.getString("currency_code"),
                rs.getString("check_type"),
                rs.getString("bank"),
                rs.getString("reference"),
                rs.getString("username"),
                rs.getLong("cashier_id"),
                rs.getBigDecimal("amount")));
  }

  private static final String CHECKS =
      """
      SELECT 'DEPOSIT' source_type,cd.id,CAST(d.created_on_utc AS date) accepted_date,UPPER(d.currency_code) currency_code,
      cd.check_type,cd.check_bank bank,cd.check_number reference,u.username,d.cashier_id,cd.amount,d.office_id
      FROM m_base_teller_deposit_check_detail cd JOIN m_base_teller_deposit d ON d.id=cd.deposit_id
      JOIN m_appuser u ON u.id=d.operator_id
      WHERE d.status IN ('PENDING_COLLECTION','COMPLETED')
      AND NOT EXISTS(SELECT 1 FROM m_transaction_reversal_check r WHERE r.source_type='SAVINGS_DEPOSIT' AND r.source_check_id=cd.id)
      AND NOT EXISTS (SELECT 1 FROM m_base_teller_returned_check rc WHERE rc.deposit_check_detail_id=cd.id AND rc.status IN ('RETURNED','SETTLED'))
      AND NOT EXISTS (SELECT 1 FROM m_cashier_reconciliation_check rc WHERE rc.deposit_check_detail_id=cd.id)
      AND NOT EXISTS (SELECT 1 FROM m_cash_operation_check oc WHERE oc.deposit_check_detail_id=cd.id)
      UNION ALL
      SELECT 'OPENING',o.id,CAST(o.created_on_utc AS date),UPPER(o.currency_code),o.check_type,o.check_bank,o.check_number,u.username,o.cashier_id,o.amount,o.office_id
      FROM m_base_teller_savings_opening o JOIN m_appuser u ON u.id=o.operator_id
      WHERE o.funding_type='CHECK' AND o.status='COMPLETED'
      UNION ALL
      SELECT 'CREDIT',c.id,CAST(c.accepted_on_utc AS date),UPPER(p.currency_code),c.check_type,c.bank_name_snapshot,c.check_number,u.username,p.cashier_id,p.amount,p.office_id
      FROM m_base_teller_credit_payment_check c JOIN m_base_teller_credit_payment p ON p.id=c.credit_payment_id
      JOIN m_appuser u ON u.id=c.accepted_by WHERE c.status IN ('PENDING_COLLECTION','CLEARED')
      AND p.status IN ('PENDING_COLLECTION','CLEARED')
      AND NOT EXISTS(SELECT 1 FROM m_transaction_reversal_check r WHERE r.source_type='CREDIT_PAYMENT' AND r.source_check_id=c.id)
      """;

  private EndOfDayClosingData stored(Long id, AppUser user) {
    List<String> rows =
        named.queryForList(
            """
            SELECT e.snapshot FROM m_end_of_day_closing e JOIN m_office o ON o.id=e.office_id
            WHERE e.id=:id AND o.hierarchy LIKE :hierarchy
            """,
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", id)
                .addValue("hierarchy", user.getOffice().getHierarchy() + "%")
                .addValue("staff", user.getStaffId()),
            String.class);
    if (rows.isEmpty())
      throw invalid("not.found", "Closing was not found in the authorized scope.");
    EndOfDayClosingData result = GSON.fromJson(rows.get(0), EndOfDayClosingData.class);
    if (!user.hasAnyPermission("READ_GLOBAL_SETTLEMENT")
        && !Objects.equals(user.getStaffId(), result.cashierStaffId()))
      throw invalid("not.found", "Closing was not found in the authorized scope.");
    return result;
  }

  private AppUser user(String action) {
    AppUser user = security.authenticatedUser();
    user.validateHasPermissionTo(action + "_" + RESOURCE);
    return user;
  }

  private void validate(EndOfDayClosingRequest request, boolean complete) {
    if (request == null
        || request.cashierId() == null
        || request.cashierId() <= 0
        || request.currencies() == null
        || request.currencies().isEmpty())
      throw invalid("request.invalid", "Cashier and currency declarations are required.");
    try {
      if (!DateUtils.getBusinessLocalDate().equals(LocalDate.parse(request.businessDate())))
        throw invalid("date.invalid", "Use the current Fineract business date.");
    } catch (java.time.format.DateTimeParseException | NullPointerException e) {
      throw invalid("date.invalid", "Use the current Fineract business date in yyyy-MM-dd format.");
    }
    for (var c : request.currencies()) {
      if (c == null
          || c.currencyCode() == null
          || c.denominations() == null
          || c.checks() == null
          || c.vouchers() == null)
        throw invalid("declaration.invalid", "Complete currency declarations are required.");
      Set<String> denominations = new HashSet<>();
      for (var d : c.denominations()) {
        if (d == null
            || d.denominationId() == null
            || d.quantity() == null
            || !denominations.add(d.denominationId()))
          throw invalid("denomination.invalid", "Each denomination identifier must be unique.");
        try {
          if (d.quantity().longValueExact() < 0) throw new ArithmeticException();
        } catch (ArithmeticException e) {
          throw invalid("quantity.invalid", "Quantity must be a non-negative supported integer.");
        }
      }
      Set<CheckSelection> checks = new HashSet<>();
      for (var ch : c.checks())
        if (ch == null || ch.source() == null || ch.id() == null || ch.id() <= 0 || !checks.add(ch))
          throw invalid("check.invalid", "Check selections must be positive and unique.");
    }
    if (complete
        && (request.idempotencyKey() == null
            || request.idempotencyKey().isBlank()
            || request.idempotencyKey().length() > 100))
      throw invalid(
          "idempotency.required", "Provide an idempotency key of at most 100 characters.");
  }

  private static String fingerprint(EndOfDayClosingRequest request) {
    List<CurrencyDeclaration> declarations =
        request.currencies().stream()
            .map(
                c ->
                    new CurrencyDeclaration(
                        c.currencyCode(),
                        c.denominations().stream()
                            .sorted(Comparator.comparing(Quantity::denominationId))
                            .toList(),
                        c.vouchers().stripTrailingZeros(),
                        c.checks().stream()
                            .sorted(
                                Comparator.comparing(CheckSelection::source)
                                    .thenComparing(CheckSelection::id))
                            .toList()))
            .sorted(Comparator.comparing(CurrencyDeclaration::currencyCode))
            .toList();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      GSON.toJson(
                              new EndOfDayClosingRequest(
                                  request.cashierId(),
                                  request.businessDate(),
                                  request.idempotencyKey(),
                                  declarations))
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private record Existing(Long id, String fingerprint) {}
}
