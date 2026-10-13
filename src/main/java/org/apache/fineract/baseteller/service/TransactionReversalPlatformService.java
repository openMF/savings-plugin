package org.apache.fineract.baseteller.service;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.*;
import org.apache.fineract.baseteller.data.TransactionReversalData.*;
import org.apache.fineract.baseteller.service.TransactionReversalHandler.Source;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionReversalPlatformService {
  public static final String READ = "READ_BASE_TELLER_TRANSACTION_REVERSAL";
  public static final String CREATE = "CREATE_BASE_TELLER_TRANSACTION_REVERSAL";
  public static final String AUTHORIZE = "AUTHORIZE_BASE_TELLER_TRANSACTION_REVERSAL";
  private static final Gson GSON = new Gson();
  private static final SecureRandom RANDOM = new SecureRandom();
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final PlatformSecurityContext security;
  private final List<TransactionReversalHandler> handlers;
  private final CashExchangeInventory inventory;
  private final org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService
      configuration;

  @Transactional(readOnly = true)
  public Context context() {
    AppUser actor = user(READ);
    var currencies =
        jdbc.query(
            "SELECT UPPER(code),name,decimal_places FROM m_organisation_currency ORDER BY code",
            (rs, row) ->
                new TransactionHistoryCurrencyData(rs.getString(1), rs.getString(2), rs.getInt(3)));
    var banks =
        jdbc.query(
            "SELECT id,code,name FROM m_base_teller_bank WHERE active ORDER BY name,id",
            (rs, row) ->
                new CreditPaymentBankData(rs.getLong(1), rs.getString(2), rs.getString(3)));
    return new Context(
        DateUtils.getBusinessLocalDate(),
        cashiers(actor),
        currencies,
        banks,
        catalog(null),
        actor.hasAnyPermission(CREATE),
        actor.hasAnyPermission(AUTHORIZE));
  }

  @Transactional(readOnly = true)
  public Search search(LocalDate date, Integer offset, Integer limit, String sort, String order) {
    AppUser actor = user(READ);
    if (date == null) throw invalid("date.required", "transactionDate is required.");
    int start = offset == null ? 0 : offset, size = limit == null ? 25 : limit;
    if (start < 0 || size < 1 || size > 500)
      throw invalid(
          "pagination.invalid", "offset must be nonnegative and limit must be between 1 and 500.");
    Map<String, String> columns =
        Map.of(
            "transactionDate",
            "h.transaction_date",
            "operation",
            "h.operation",
            "amount",
            "h.total_amount",
            "status",
            "h.status");
    String column = columns.get(sort == null ? "transactionDate" : sort);
    String direction = order == null ? "DESC" : order.toUpperCase(Locale.ROOT);
    if (column == null || !Set.of("ASC", "DESC").contains(direction))
      throw invalid("sort.invalid", "Unsupported sort or order.");
    var drawers = cashiers(actor);
    if (drawers.isEmpty())
      throw invalid("cashier.required", "An active cashier assignment is required.");
    Map<String, Object> params =
        new HashMap<>(
            Map.of(
                "date",
                date,
                "ids",
                drawers.stream().map(Cashier::id).toList(),
                "hierarchy",
                actor.getOffice().getHierarchy() + "%",
                "offset",
                start,
                "limit",
                size));
    String scope =
        " FROM history h JOIN m_office o ON o.id=h.office_id WHERE h.business_date=:date AND"
            + " o.hierarchy LIKE :hierarchy AND (h.source_cashier_id IN (:ids) OR"
            + " h.destination_cashier_id IN (:ids))";
    String cte = TransactionHistoryReadPlatformServiceImpl.historyCte();
    long count = named.queryForObject(cte + " SELECT COUNT(*)" + scope, params, Long.class);
    Map<String, String> usernames = new HashMap<>();
    Map<String, String> operationTypes = new HashMap<>();
    Map<String, BigDecimal> amounts = new HashMap<>();
    Map<String, LocalDate> dates = new HashMap<>();
    var items =
        named.query(
            cte
                + " SELECT h.*,oc.decimal_places,(SELECT u.username FROM m_appuser u WHERE"
                + " u.id=h.performed_by) performed_username"
                + scope.replace(
                    " WHERE ",
                    " LEFT JOIN m_organisation_currency oc ON UPPER(oc.code)=h.currency_code WHERE"
                        + " ")
                + " ORDER BY "
                + column
                + " "
                + direction
                + ",h.source_type "
                + direction
                + ",h.source_id "
                + direction
                + " LIMIT :limit OFFSET :offset",
            params,
            (rs, row) -> {
              String historyId = rs.getString("source_type") + ":" + rs.getLong("source_id");
              operationTypes.put(historyId, rs.getString("transaction_type"));
              amounts.put(historyId, rs.getBigDecimal("total_amount"));
              dates.put(historyId, rs.getObject("business_date", LocalDate.class));
              if (rs.getString("performed_username") != null)
                usernames.put(historyId, rs.getString("performed_username"));
              return new TransactionHistoryItemData(
                  rs.getString("source_type") + ":" + rs.getLong("source_id"),
                  rs.getTimestamp("transaction_date").toInstant().atOffset(ZoneOffset.UTC),
                  rs.getString("operation"),
                  rs.getBigDecimal("inflow"),
                  rs.getBigDecimal("outflow"),
                  rs.getString("currency_code"),
                  rs.getObject("decimal_places", Integer.class),
                  rs.getString("concept"),
                  rs.getString("status"),
                  rs.getString("reference"),
                  rs.getObject("cashier_id", Long.class),
                  rs.getObject("client_id", Long.class));
            });
    return new Search(
        items.stream()
            .map(
                item -> {
                  Source source = source(item.historyId(), actor, false);
                  return new Candidate(
                      item,
                      usernames.get(item.historyId()),
                      sourceType(item.historyId()),
                      operationTypes.get(item.historyId()),
                      amounts.get(item.historyId()),
                      dates.get(item.historyId()),
                      eligibility(source, item.historyId(), actor));
                })
            .toList(),
        count,
        start,
        size);
  }

  @Transactional(readOnly = true)
  public Detail detail(String historyId) {
    AppUser actor = user(READ);
    // Canonical discovery establishes visibility even for sources with no reversal handler.
    canonical(historyId, actor, false);
    Source source = source(historyId, actor, false);
    return detail(historyId, source, actor);
  }

  @Transactional(readOnly = true)
  public Preview preview(TransactionReversalRequest request, String authorizationNumber) {
    AppUser actor = user(CREATE);
    validateRequest(request);
    canonical(request.historyId(), actor, false);
    Source source = source(request.historyId(), actor, false);
    requireEligible(source, request.historyId(), actor);
    authorization(authorizationNumber, request.historyId(), source, actor, false);
    return prepare(request, source, actor);
  }

  @Transactional
  public Result reverse(TransactionReversalRequest request, String authorizationNumber) {
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    AppUser actor = user(CREATE);
    validateRequest(request);
    canonical(request.historyId(), actor, false);
    Source initial = source(request.historyId(), actor, false);
    if (initial == null)
      throw invalid("source.unsupported", "This source has no complete reversal mechanism.");
    lockCustody(initial, sourceType(request.historyId()));
    canonical(request.historyId(), actor, false);
    String fingerprint = fingerprint(request);
    var existing =
        jdbc.queryForList(
            "SELECT id,request_fingerprint,original_history_id,reversed_by FROM"
                + " m_transaction_reversal WHERE idempotency_key=?",
            request.idempotencyKey());
    if (!existing.isEmpty()) {
      var prior = existing.getFirst();
      if (!fingerprint.equals(prior.get("request_fingerprint"))
          || !request.historyId().equals(prior.get("original_history_id"))
          || !actor.getId().equals(((Number) prior.get("reversed_by")).longValue()))
        throw invalid(
            "idempotency.conflict", "Idempotency key belongs to another reversal request.");
      // A replay verifies the same authorization identity, but does not consume it twice.
      Result result = stored(((Number) prior.get("id")).longValue(), actor);
      var approvals =
          jdbc.queryForList(
              "SELECT id FROM m_transaction_reversal_authorization WHERE token_hash=?",
              Long.class,
              hashSecret(authorizationNumber));
      if (approvals.size() != 1 || !result.authorizationId().equals(approvals.getFirst()))
        throw invalid("authorization.invalid", "Authorization number is invalid.");
      return result;
    }
    Source source = source(request.historyId(), actor, false);
    requireEligible(source, request.historyId(), actor);
    Authorization approval =
        authorization(authorizationNumber, request.historyId(), source, actor, true);
    Preview prepared = prepare(request, source, actor);
    String reference =
        handler(sourceType(request.historyId()))
            .reverse(source, "WEB1259:" + request.idempotencyKey());
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO m_transaction_reversal(idempotency_key,request_fingerprint,original_history_id,source_type,source_id,
              business_date,office_id,teller_id,cashier_id,currency_code,amount,cash_effect,check_effect,
              authorization_id,authorized_by,authorized_at,reversed_by,source_reversal_reference,status)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'COMPLETED') RETURNING id
            """,
            Long.class,
            request.idempotencyKey(),
            fingerprint,
            request.historyId(),
            sourceType(request.historyId()),
            source.id(),
            source.businessDate(),
            source.officeId(),
            prepared.original().cashier().tellerId(),
            source.cashierId(),
            source.currency(),
            source.amount(),
            prepared.cashEffect(),
            prepared.checkEffect(),
            approval.id(),
            approval.authorizedBy(),
            approval.authorizedAt(),
            actor.getId(),
            reference);
    persistDenominations(id, "OUT", prepared.cashDenominations());
    persistDenominations(id, "IN", prepared.returnedCashDenominations());
    for (var check : source.checks())
      jdbc.update(
          "INSERT INTO"
              + " m_transaction_reversal_check(reversal_id,source_type,source_check_id,bank_id,check_number,amount,currency_code)"
              + " VALUES(?,?,?,?,?,?,?)",
          id,
          sourceType(request.historyId()),
          check.id(),
          check.bankId(),
          check.checkNumber(),
          check.amount(),
          check.currencyCode());
    if (jdbc.update(
            "UPDATE m_transaction_reversal_authorization SET consumed_at=CURRENT_TIMESTAMP WHERE"
                + " id=? AND consumed_at IS NULL",
            approval.id())
        != 1) throw invalid("authorization.invalid", "Authorization number is invalid.");
    return stored(id, actor);
  }

  @Transactional(readOnly = true)
  public Result retrieve(Long id) {
    return stored(id, user(READ));
  }

  /** Only an authenticated supervisor can issue a number; it is never a cashier-supplied label. */
  @Transactional
  public IssuedAuthorization issue(String historyId, Long cashierUserId) {
    AppUser supervisor = user(AUTHORIZE);
    canonical(historyId, supervisor, true);
    Source source = source(historyId, supervisor, true);
    if (source == null
        || source.ineligibleReason() != null
        || source.cashierId() == null
        || !DateUtils.getBusinessLocalDate().equals(source.businessDate())
        || !Set.of("COMPLETED", "CLEARED").contains(source.status())
        || reversedId(historyId) != null)
      throw invalid("source.ineligible", "The transaction is not eligible for authorization.");
    if (cashierUserId == null || supervisor.getId().equals(cashierUserId))
      throw invalid("authorization.self", "A different supervisor must authorize reversal.");
    var rows =
        jdbc.queryForList(
            """
            SELECT u.id FROM m_appuser u JOIN m_cashiers c ON c.staff_id=u.staff_id JOIN m_tellers t ON t.id=c.teller_id JOIN m_office o ON o.id=t.office_id
            WHERE u.id=? AND c.id=? AND t.office_id=? AND u.staff_id<>COALESCE(?, -1) AND u.enabled AND u.nonlocked AND u.nonexpired AND u.nonexpired_credentials AND NOT u.is_deleted
              AND o.hierarchy LIKE ? AND t.state=300 AND (c.start_date IS NULL OR c.start_date<=?) AND (c.end_date IS NULL OR c.end_date>=?)
              AND EXISTS(SELECT 1 FROM m_appuser_role ur JOIN m_role active_role ON active_role.id=ur.role_id AND NOT active_role.is_disabled JOIN m_role_permission rp ON rp.role_id=ur.role_id JOIN m_permission p ON p.id=rp.permission_id
                WHERE ur.appuser_id=u.id AND p.code IN (?, 'ALL_FUNCTIONS'))
            """,
            Long.class,
            cashierUserId,
            source.cashierId(),
            source.officeId(),
            supervisor.getStaffId(),
            supervisor.getOffice().getHierarchy() + "%",
            source.businessDate(),
            source.businessDate(),
            CREATE);
    if (rows.isEmpty() || closed(source))
      throw invalid(
          "authorization.scope", "The requested cashier is not eligible for this authorization.");
    byte[] random = new byte[32];
    RANDOM.nextBytes(random);
    String number = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO m_transaction_reversal_authorization(token_hash,history_id,cashier_id,office_id,actor_id,business_date,authorized_by,expires_at)
            VALUES(?,?,?,?,?,?,?,clock_timestamp() + INTERVAL '15 minutes') RETURNING id
            """,
            Long.class,
            hash(number),
            historyId,
            source.cashierId(),
            source.officeId(),
            cashierUserId,
            source.businessDate(),
            supervisor.getId());
    return new IssuedAuthorization(id, number, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));
  }

  private Preview prepare(TransactionReversalRequest request, Source source, AppUser actor) {
    var denominations = catalog(source.currency());
    BigDecimal removal =
        "OUT".equals(source.originalDirection()) ? BigDecimal.ZERO : source.cashAmount();
    var outgoing = validateQuantities(request.cashDenominations(), removal, denominations);
    BigDecimal returns =
        Set.of("OUT", "SWAP").contains(source.originalDirection())
            ? source.cashAmount()
            : BigDecimal.ZERO;
    var incoming = validateQuantities(request.returnedCashDenominations(), returns, denominations);
    handler(sourceType(request.historyId())).validatePhysicalComponents(source, outgoing, incoming);
    validateChecks(request.checks(), source);
    if (!outgoing.isEmpty()) {
      Map<String, Long> available =
          inventory.quantities(source.cashierId(), source.currency(), source.businessDate());
      for (var line : outgoing)
        if (available.getOrDefault(line.denominationId(), 0L) < line.quantity())
          throw invalid("cash.insufficient", "Insufficient denomination inventory for reversal.");
    }
    BigDecimal cashEffect = physicalCashEffect(source.originalDirection(), source.cashAmount());
    return new Preview(
        detail(request.historyId(), source, actor),
        outgoing,
        incoming,
        "SWAP".equals(source.originalDirection())
            ? "SWAP"
            : "OUT".equals(source.originalDirection()) ? "IN" : "OUT",
        cashEffect,
        source.checkAmount().negate(),
        handler(sourceType(request.historyId())).accountingStrategy());
  }

  static List<BaseTellerDenominationData> validateQuantities(
      List<TransactionReversalRequest.Quantity> lines,
      BigDecimal amount,
      List<Denomination> catalog) {
    if (amount.signum() == 0) {
      if (lines != null && !lines.isEmpty())
        throw invalid("cash.unexpected", "This component has no cash movement.");
      return List.of();
    }
    if (lines == null || lines.isEmpty())
      throw invalid("cash.required", "Cash denomination breakdown is required.");
    Map<String, Denomination> values = new HashMap<>();
    catalog.forEach(d -> values.put(d.id(), d));
    Set<String> seen = new HashSet<>();
    List<BaseTellerDenominationData> result = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (var line : lines) {
      if (line == null
          || line.denominationId() == null
          || !seen.add(line.denominationId())
          || line.quantity() == null
          || line.quantity() <= 0
          || !values.containsKey(line.denominationId()))
        throw invalid("cash.invalid", "Unknown, duplicate or invalid denomination quantity.");
      Denomination d = values.get(line.denominationId());
      result.add(new BaseTellerDenominationData(d.id(), d.value(), line.quantity()));
      total = total.add(d.value().multiply(BigDecimal.valueOf(line.quantity())));
    }
    if (total.compareTo(amount) != 0)
      throw invalid(
          "cash.total", "Denomination total must exactly match the original cash component.");
    return result.stream()
        .sorted(Comparator.comparing(BaseTellerDenominationData::denominationId))
        .toList();
  }

  static void validateChecks(List<TransactionReversalRequest.Check> declared, Source source) {
    var checks = declared == null ? List.<TransactionReversalRequest.Check>of() : declared;
    if (checks.size() != source.checks().size())
      throw invalid("checks.total", "Every original check must be declared exactly once.");
    Set<Long> seen = new HashSet<>();
    Set<String> documents = new HashSet<>();
    BigDecimal total = BigDecimal.ZERO;
    for (var c : checks) {
      if (c == null || c.id() == null || !seen.add(c.id()))
        throw invalid("checks.duplicate", "Duplicate or invalid check selection.");
      var original =
          source.checks().stream()
              .filter(x -> x.id().equals(c.id()))
              .findFirst()
              .orElseThrow(
                  () ->
                      invalid(
                          "checks.unrelated",
                          "Check does not belong to the original transaction."));
      if (!original.eligible()
          || c.bankId() == null
          || c.checkNumber() == null
          || c.checkNumber().isBlank()
          || c.checkNumber().length() > 100
          || !documents.add(c.bankId() + ":" + c.checkNumber().strip())
          || !Objects.equals(original.bankId(), c.bankId())
          || !Objects.equals(original.checkNumber(), c.checkNumber())
          || c.amount() == null
          || c.amount().signum() <= 0
          || c.amount().compareTo(original.amount()) != 0
          || !source.currency().equals(original.currencyCode()))
        throw invalid("checks.invalid", "Check details must match the eligible original check.");
      total = total.add(c.amount());
    }
    if (total.compareTo(source.checkAmount()) != 0)
      throw invalid("checks.total", "Checks must exactly match the original check component.");
  }

  private Detail detail(String historyId, Source source, AppUser actor) {
    if (source == null) {
      return named.queryForObject(
          TransactionHistoryReadPlatformServiceImpl.historyCte()
              + " SELECT h.*,u.username FROM history h LEFT JOIN m_cashiers c ON c.id=h.cashier_id"
              + " LEFT JOIN m_appuser u ON u.id=h.performed_by WHERE h.source_type=:type AND"
              + " h.source_id=:id",
          Map.of("type", sourceType(historyId), "id", sourceId(historyId)),
          (rs, row) ->
              new Detail(
                  historyId,
                  sourceType(historyId),
                  sourceId(historyId),
                  rs.getObject("business_date", LocalDate.class),
                  cashiers(actor).stream()
                      .filter(cashier -> cashier.id().equals(getLong(rs, "cashier_id")))
                      .findFirst()
                      .orElse(null),
                  rs.getString("username"),
                  rs.getString("operation"),
                  rs.getString("currency_code"),
                  rs.getBigDecimal("total_amount"),
                  rs.getBigDecimal("cash_received"),
                  rs.getBigDecimal("checks_received"),
                  rs.getBigDecimal("outflow").signum() > 0 ? "OUT" : "IN",
                  new Eligibility(false, "This source has no complete reversal mechanism."),
                  List.of(),
                  List.of(),
                  List.of(),
                  reversedId(historyId)));
    }
    Cashier cashier =
        cashiers(actor).stream()
            .filter(c -> c.id().equals(source.cashierId()))
            .findFirst()
            .orElse(null);
    return new Detail(
        historyId,
        sourceType(historyId),
        source.id(),
        source.businessDate(),
        cashier,
        source.user(),
        source.operation(),
        source.currency(),
        source.amount(),
        source.cashAmount(),
        source.checkAmount(),
        source.originalDirection(),
        eligibility(source, historyId, actor),
        source.checks(),
        catalog(source.currency()),
        source.returnedDenominations(),
        reversedId(historyId));
  }

  private Eligibility eligibility(Source source, String historyId, AppUser actor) {
    String reason =
        source == null
            ? "This source has no complete reversal mechanism."
            : reversedId(historyId) != null
                ? "This transaction is already reversed."
                : !Set.of("COMPLETED", "CLEARED").contains(source.status())
                    ? "The original operation is not completed."
                    : source.ineligibleReason() != null
                        ? source.ineligibleReason()
                        : !DateUtils.getBusinessLocalDate().equals(source.businessDate())
                            ? "Only the current business date can be reversed."
                            : cashiers(actor).stream()
                                    .noneMatch(
                                        c ->
                                            c.id().equals(source.cashierId())
                                                && c.officeId().equals(source.officeId()))
                                ? "The original cashier session is not active for this user."
                                : closed(source)
                                    ? "This cashier session has completed closing."
                                    : accountingClosed(source, historyId)
                                        ? "The accounting period is closed."
                                        : null;
    if (reason == null) {
      String permission = nativePermission(historyId, source);
      if (permission != null
          && configuration.isMakerCheckerEnabledForTask(permission)
          && !actor.isCheckerSuperUser())
        reason = "Native maker/checker approval is required; synchronous reversal is unavailable.";
      else if (!actor.hasAnyPermission(CREATE)
          || permission != null && !actor.hasAnyPermission(permission))
        reason = "The required reversal permission is missing.";
    }
    if (reason == null && source.cashAmount().signum() > 0) {
      try {
        inventory.quantities(source.cashierId(), source.currency(), source.businessDate());
      } catch (GeneralPlatformDomainRuleException e) {
        reason = "Recorded denomination composition is incomplete or unavailable.";
      }
    }
    return new Eligibility(reason == null, reason);
  }

  private void requireEligible(Source source, String historyId, AppUser actor) {
    Eligibility eligibility = eligibility(source, historyId, actor);
    if (!eligibility.reversible()) throw invalid("source.ineligible", eligibility.reason());
    String permission = nativePermission(historyId, source);
    if (permission != null) actor.validateHasPermissionTo(permission);
  }

  private String nativePermission(String historyId, Source source) {
    var builder = new org.apache.fineract.commands.service.CommandWrapperBuilder();
    return switch (sourceType(historyId)) {
      case "SAVINGS_DEPOSIT" ->
          builder
              .undoSavingsAccountTransaction(source.accountId(), source.transactionId())
              .build()
              .getTaskPermissionName();
      case "CREDIT_PAYMENT" ->
          builder
              .adjustTransaction(source.accountId(), source.transactionId())
              .build()
              .getTaskPermissionName();
      case "SERVICE_PAYMENT" ->
          builder.reverseJournalEntry("preview").build().getTaskPermissionName();
      default -> null;
    };
  }

  private boolean accountingClosed(Source source, String historyId) {
    return !"CASH_EXCHANGE".equals(sourceType(historyId))
        && Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM acc_gl_closure WHERE office_id=? AND closing_date>=?)",
                Boolean.class,
                source.officeId(),
                source.businessDate()));
  }

  private boolean closed(Source source) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM m_cashier_reconciliation WHERE cashier_id=? AND"
                + " business_date=?) OR EXISTS(SELECT 1 FROM m_end_of_day_closing WHERE"
                + " cashier_id=? AND business_date=?)",
            Boolean.class,
            source.cashierId(),
            source.businessDate(),
            source.cashierId(),
            source.businessDate()));
  }

  private List<Cashier> cashiers(AppUser actor) {
    if (actor.getStaffId() == null || actor.getOffice() == null) return List.of();
    return jdbc.query(
        """
        SELECT c.id,c.teller_id,t.office_id,c.staff_id,s.display_name FROM m_cashiers c JOIN m_tellers t ON t.id=c.teller_id JOIN m_office o ON o.id=t.office_id JOIN m_staff s ON s.id=c.staff_id
        WHERE c.staff_id=? AND s.is_active AND o.hierarchy LIKE ? AND t.state=300 AND (c.start_date IS NULL OR c.start_date<=?) AND (c.end_date IS NULL OR c.end_date>=?)
        AND (t.valid_from IS NULL OR t.valid_from<=?) AND (t.valid_to IS NULL OR t.valid_to>=?) ORDER BY c.id
        """,
        (rs, row) ->
            new Cashier(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5)),
        actor.getStaffId(),
        actor.getOffice().getHierarchy() + "%",
        DateUtils.getBusinessLocalDate(),
        DateUtils.getBusinessLocalDate(),
        DateUtils.getBusinessLocalDate(),
        DateUtils.getBusinessLocalDate());
  }

  private List<Denomination> catalog(String currency) {
    return jdbc.query(
        "SELECT identifier,value,denomination_type,currency_code FROM"
            + " m_service_payment_denomination WHERE active AND (CAST(? AS varchar) IS NULL OR"
            + " currency_code=?) ORDER BY currency_code,value DESC,identifier",
        (rs, row) ->
            new Denomination(
                rs.getString(1), rs.getBigDecimal(2), rs.getString(3), rs.getString(4)),
        currency,
        currency);
  }

  private TransactionReversalHandler handler(String type) {
    return handlers.stream()
        .filter(h -> h.sourceType().equals(type))
        .findFirst()
        .orElseThrow(
            () -> invalid("source.unsupported", "This source has no complete reversal mechanism."));
  }

  private Source source(String historyId, AppUser actor, boolean supervisor) {
    var handler =
        handlers.stream().filter(h -> h.sourceType().equals(sourceType(historyId))).findFirst();
    return handler.isEmpty() ? null : handler.get().load(sourceId(historyId));
  }

  private void canonical(String historyId, AppUser actor, boolean supervisor) {
    Map<String, Object> params =
        new HashMap<>(
            Map.of(
                "type",
                sourceType(historyId),
                "id",
                sourceId(historyId),
                "hierarchy",
                actor.getOffice().getHierarchy() + "%"));
    String scope = "";
    if (!supervisor) {
      var cashiers = cashiers(actor);
      if (cashiers.isEmpty())
        throw invalid("cashier.required", "An active cashier assignment is required.");
      params.put("cashiers", cashiers.stream().map(Cashier::id).toList());
      scope =
          " AND (h.source_cashier_id IN (:cashiers) OR h.destination_cashier_id IN (:cashiers))";
    }
    if (named.queryForObject(
            TransactionHistoryReadPlatformServiceImpl.historyCte()
                + " SELECT COUNT(*) FROM history h JOIN m_office o ON o.id=h.office_id WHERE"
                + " h.source_type=:type AND h.source_id=:id AND o.hierarchy LIKE :hierarchy"
                + scope,
            params,
            Long.class)
        != 1) throw invalid("not.found", "Transaction was not found in the authorized scope.");
  }

  private Long reversedId(String historyId) {
    var rows =
        jdbc.queryForList(
            "SELECT id FROM m_transaction_reversal WHERE original_history_id=?",
            Long.class,
            historyId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Authorization authorization(
      String secret, String historyId, Source source, AppUser actor, boolean lock) {
    var rows =
        jdbc.query(
            """
            SELECT a.id,a.authorized_by,a.authorized_at FROM m_transaction_reversal_authorization a
            JOIN m_appuser u ON u.id=a.authorized_by JOIN m_office o ON o.id=u.office_id JOIN m_office target ON target.id=a.office_id
            WHERE a.token_hash=? AND a.history_id=? AND a.cashier_id=? AND a.office_id=? AND a.actor_id=? AND a.business_date=?
              AND a.expires_at>clock_timestamp() AND a.consumed_at IS NULL AND u.id<>? AND COALESCE(u.staff_id,-1)<>?
              AND u.enabled AND u.nonlocked AND u.nonexpired AND u.nonexpired_credentials AND NOT u.is_deleted AND target.hierarchy LIKE CONCAT(o.hierarchy,'%')
              AND EXISTS(SELECT 1 FROM m_appuser_role ur JOIN m_role active_role ON active_role.id=ur.role_id AND NOT active_role.is_disabled JOIN m_role_permission rp ON rp.role_id=ur.role_id JOIN m_permission p ON p.id=rp.permission_id
                WHERE ur.appuser_id=u.id AND p.code IN (?, 'ALL_FUNCTIONS'))
            """
                + (lock ? " FOR UPDATE OF a" : ""),
            (rs, row) ->
                new Authorization(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getTimestamp(3).toInstant().atOffset(ZoneOffset.UTC)),
            hashSecret(secret),
            historyId,
            source.cashierId(),
            source.officeId(),
            actor.getId(),
            source.businessDate(),
            actor.getId(),
            actor.getStaffId(),
            AUTHORIZE);
    if (rows.size() != 1)
      throw invalid(
          "authorization.invalid", "Authorization number is invalid or no longer eligible.");
    return rows.getFirst();
  }

  private Result stored(Long id, AppUser actor) {
    var rows =
        jdbc.query(
            """
            SELECT r.* FROM m_transaction_reversal r JOIN m_office o ON o.id=r.office_id JOIN m_cashiers c ON c.id=r.cashier_id
            WHERE r.id=? AND o.hierarchy LIKE ? AND r.reversed_by=?
            """,
            (rs, row) ->
                new Result(
                    rs.getLong("id"),
                    "TRANSACTION_REVERSAL:" + rs.getLong("id"),
                    rs.getString("original_history_id"),
                    rs.getLong("cashier_id"),
                    rs.getString("currency_code"),
                    rs.getBigDecimal("amount"),
                    rs.getBigDecimal("cash_effect"),
                    rs.getBigDecimal("check_effect"),
                    rs.getLong("authorization_id"),
                    rs.getLong("authorized_by"),
                    rs.getTimestamp("authorized_at").toInstant().atOffset(ZoneOffset.UTC),
                    rs.getLong("reversed_by"),
                    rs.getTimestamp("reversed_at").toInstant().atOffset(ZoneOffset.UTC),
                    rs.getString("source_reversal_reference"),
                    rs.getString("status"),
                    storedDenominations(id, "OUT"),
                    storedDenominations(id, "IN"),
                    jdbc.query(
                        "SELECT source_check_id,bank_id,check_number,amount,currency_code FROM"
                            + " m_transaction_reversal_check WHERE reversal_id=? ORDER BY"
                            + " source_check_id",
                        (check, rowNumber) ->
                            new ReversedCheck(
                                check.getLong(1),
                                check.getLong(2),
                                check.getString(3),
                                check.getBigDecimal(4),
                                check.getString(5)),
                        id)),
            id,
            actor.getOffice().getHierarchy() + "%",
            actor.getId());
    if (rows.isEmpty())
      throw invalid("not.found", "Reversal was not found in the authorized scope.");
    return rows.getFirst();
  }

  private List<BaseTellerDenominationData> storedDenominations(Long id, String direction) {
    return jdbc.query(
        "SELECT denomination_identifier,denomination_value,quantity FROM"
            + " m_transaction_reversal_denomination WHERE reversal_id=? AND direction=? ORDER BY"
            + " denomination_identifier",
        (rs, row) ->
            new BaseTellerDenominationData(rs.getString(1), rs.getBigDecimal(2), rs.getLong(3)),
        id,
        direction);
  }

  private void persistDenominations(
      Long id, String direction, List<BaseTellerDenominationData> lines) {
    for (var line : lines)
      jdbc.update(
          "INSERT INTO"
              + " m_transaction_reversal_denomination(reversal_id,direction,denomination_identifier,denomination_value,quantity)"
              + " VALUES(?,?,?,?,?)",
          id,
          direction,
          line.denominationId(),
          line.value(),
          line.quantity());
  }

  private void lockCustody(Source source, String sourceType) {
    // Same PostgreSQL custody boundary as WEB-1258, including writers that lack drawer locks.
    jdbc.queryForObject("SELECT id FROM m_cash_exchange_mutex WHERE id=1 FOR UPDATE", Long.class);
    // Fence native readers too: a core reversal must not prepare a stale unreversed entity
    // while this orchestration is posting its native command and physical correction.
    switch (sourceType) {
      case "SAVINGS_DEPOSIT" ->
          jdbc.execute(
              "LOCK TABLE"
                  + " m_savings_account,m_savings_account_transaction,acc_gl_journal_entry,acc_gl_closure"
                  + " IN ACCESS EXCLUSIVE MODE");
      case "CREDIT_PAYMENT" ->
          jdbc.execute(
              "LOCK TABLE m_loan,m_loan_transaction,acc_gl_journal_entry,acc_gl_closure IN ACCESS"
                  + " EXCLUSIVE MODE");
      case "SERVICE_PAYMENT" ->
          jdbc.execute("LOCK TABLE acc_gl_journal_entry,acc_gl_closure IN ACCESS EXCLUSIVE MODE");
      default -> {}
    }
    jdbc.queryForObject(
        "SELECT id FROM m_office WHERE id=? FOR UPDATE", Long.class, source.officeId());
    jdbc.queryForObject(
        "SELECT id FROM m_cashiers WHERE id=? FOR UPDATE", Long.class, source.cashierId());
    jdbc.execute(
        "LOCK TABLE"
            + " m_cash_allocation,m_cashiers,m_tellers,m_cashier_transactions,m_cashier_reconciliation,m_cashier_reconciliation_check,m_cash_operation_transaction,m_cash_operation_check,m_base_teller_deposit,m_base_teller_deposit_check_detail,m_base_teller_savings_opening,m_base_teller_credit_payment,m_base_teller_credit_payment_check,m_base_teller_returned_check,m_base_teller_returned_check_payment,m_service_payment,m_savings_account_transaction,m_loan_transaction,m_client_transaction,m_account_transfer_transaction,m_payment_detail,m_payment_type,m_savings_account,m_loan,m_client,m_staff,m_appuser,m_service_payment_denomination,m_organisation_currency,m_end_of_day_closing,m_end_of_day_closing_check,m_cash_exchange,m_cash_exchange_detail,m_transaction_reversal,m_transaction_reversal_authorization,m_base_teller_bank,m_appuser_role,m_role,m_role_permission,m_permission"
            + " IN SHARE ROW EXCLUSIVE MODE");
  }

  private AppUser user(String permission) {
    AppUser actor = security.authenticatedUser();
    actor.validateHasPermissionTo(permission);
    if (actor.getOffice() == null)
      throw invalid("office.required", "An office assignment is required.");
    return actor;
  }

  static void validateRequest(TransactionReversalRequest request) {
    if (request == null) throw invalid("request.required", "Reversal request is required.");
    sourceId(request.historyId());
    for (var list :
        List.of(
            request.cashDenominations() == null
                ? List.<TransactionReversalRequest.Quantity>of()
                : request.cashDenominations(),
            request.returnedCashDenominations() == null
                ? List.<TransactionReversalRequest.Quantity>of()
                : request.returnedCashDenominations()))
      if (list.stream()
          .anyMatch(q -> q == null || q.denominationId() == null || q.quantity() == null))
        throw invalid("cash.invalid", "Invalid denomination entry.");
    if (request.checks() != null
        && request.checks().stream().anyMatch(c -> c == null || c.id() == null))
      throw invalid("checks.invalid", "Invalid check entry.");
    if (request.idempotencyKey() == null
        || request.idempotencyKey().isBlank()
        || request.idempotencyKey().length() > 80)
      throw invalid(
          "idempotency.required",
          "A nonblank idempotency key of at most 80 characters is required.");
  }

  static String sourceType(String historyId) {
    if (historyId == null || !historyId.matches("[A-Z_]+:[1-9][0-9]*"))
      throw invalid("id.invalid", "A canonical history ID is required.");
    return historyId.substring(0, historyId.indexOf(':'));
  }

  static Long sourceId(String historyId) {
    sourceType(historyId);
    try {
      return Long.valueOf(historyId.substring(historyId.indexOf(':') + 1));
    } catch (NumberFormatException e) {
      throw invalid("id.invalid", "History ID is outside the supported range.");
    }
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hashSecret(String secret) {
    if (secret == null || secret.isBlank() || secret.length() > 200)
      throw invalid("authorization.required", "Authorization number is required.");
    return hash(secret);
  }

  static String fingerprint(TransactionReversalRequest request) {
    var cash =
        request.cashDenominations() == null
            ? List.<TransactionReversalRequest.Quantity>of()
            : request.cashDenominations();
    var returns =
        request.returnedCashDenominations() == null
            ? List.<TransactionReversalRequest.Quantity>of()
            : request.returnedCashDenominations();
    var checks =
        request.checks() == null ? List.<TransactionReversalRequest.Check>of() : request.checks();
    // Authorization is deliberately absent; the persisted audit identity must match on replay.
    return hash(
        GSON.toJson(
            new TransactionReversalRequest(
                request.historyId(),
                request.idempotencyKey(),
                cash.stream()
                    .sorted(
                        Comparator.comparing(TransactionReversalRequest.Quantity::denominationId))
                    .toList(),
                returns.stream()
                    .sorted(
                        Comparator.comparing(TransactionReversalRequest.Quantity::denominationId))
                    .toList(),
                checks.stream()
                    .sorted(Comparator.comparing(TransactionReversalRequest.Check::id))
                    .toList())));
  }

  static BigDecimal physicalCashEffect(String originalDirection, BigDecimal amount) {
    return switch (originalDirection) {
      case "IN" -> amount.negate();
      case "OUT" -> amount;
      case "SWAP" -> BigDecimal.ZERO;
      default ->
          throw invalid("direction.invalid", "Original physical cash direction is unavailable.");
    };
  }

  public static GeneralPlatformDomainRuleException invalid(String code, String message) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.base.teller.transaction.reversal." + code, message);
  }

  private static Long getLong(java.sql.ResultSet rs, String column) {
    try {
      return rs.getObject(column, Long.class);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private record Authorization(Long id, Long authorizedBy, OffsetDateTime authorizedAt) {}

  // No toString, record, or Lombok Data: the code is returned once and must not enter logs.
  public static final class IssuedAuthorization {
    private final Long id;
    private final String authorizationNumber;
    private final OffsetDateTime expiresAt;

    public IssuedAuthorization(Long id, String number, OffsetDateTime expiresAt) {
      this.id = id;
      this.authorizationNumber = number;
      this.expiresAt = expiresAt;
    }

    public Long getId() {
      return id;
    }

    public String getAuthorizationNumber() {
      return authorizationNumber;
    }

    public OffsetDateTime getExpiresAt() {
      return expiresAt;
    }
  }
}
