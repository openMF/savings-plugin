package org.apache.fineract.baseteller.service;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.CashDifferenceType;
import org.apache.fineract.baseteller.data.CashHoldingData;
import org.apache.fineract.baseteller.data.CashManagementStatus;
import org.apache.fineract.baseteller.data.CashOperationData;
import org.apache.fineract.baseteller.data.CashOperationRequest;
import org.apache.fineract.baseteller.data.CashOperationType;
import org.apache.fineract.baseteller.data.CashierCheckData;
import org.apache.fineract.baseteller.data.CashierClosingContextData;
import org.apache.fineract.baseteller.data.CashierClosingReceiptData;
import org.apache.fineract.baseteller.data.CashierClosingRequest;
import org.apache.fineract.baseteller.data.GlobalCashCountData;
import org.apache.fineract.baseteller.validation.CashManagementValidator;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.teller.data.CashierData;
import org.apache.fineract.organisation.teller.data.TellerData;
import org.apache.fineract.organisation.teller.service.TellerManagementReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CashManagementPlatformServiceImpl
    implements CashManagementReadPlatformService, CashManagementWritePlatformService {

  private static final int ALLOCATION = 101;
  private static final int SETTLEMENT = 102;
  private static final int CASH_IN = 103;
  private static final int CASH_OUT = 104;
  private static final int MAX_LIMIT = 100;
  private static final Gson GSON = new Gson();

  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;
  private final PlatformSecurityContext context;
  private final TellerManagementReadPlatformService tellerService;
  private final CashManagementValidator validator;
  private final PortfolioCommandSourceWritePlatformService commandService;

  @Override
  @Transactional(readOnly = true)
  public CashierClosingContextData closingContext(
      final Long cashierId, final String currencyCode, final LocalDate businessDate) {
    final AppUser user = context.authenticatedUser();
    user.validateHasPermissionTo("READ_CASHIER_CLOSING");
    return context(cashierId, currencyCode, dateOrToday(businessDate), user, false);
  }

  @Override
  @Transactional
  public CashierClosingReceiptData close(final CashierClosingRequest request) {
    final AppUser authorizer = context.authenticatedUser();
    authorizer.validateHasPermissionTo("CREATE_CASHIER_CLOSING");
    authorizer.validateHasPermissionTo("AUTHORIZE_CASHIER_CLOSING");
    final int decimalPlaces = currencyDecimalPlaces(request == null ? null : request.currencyCode());
    validator.validateClosing(request, decimalPlaces);
    validateBusinessDate(request.businessDate());
    final String fingerprint = fingerprint(request);
    final Existing existing = existingReconciliation(request.idempotencyKey());
    if (existing != null) {
      validateFingerprint(existing, fingerprint);
      return closing(existing.id(), authorizer);
    }

    final CashierData cashier = cashier(request.cashierId(), request.businessDate(), authorizer);
    jdbcTemplate.queryForObject(
        "SELECT id FROM m_cashiers WHERE id = ? FOR UPDATE", Long.class, request.cashierId());
    if (authorizer.getStaffId() != null && authorizer.getStaffId().equals(cashier.getStaffId())) {
      throw invalid(
          "closing.self.authorization.forbidden",
          "A cashier closing must be authorized by a different user.");
    }
    final CashierClosingContextData position =
        context(request.cashierId(), request.currencyCode(), request.businessDate(), authorizer, true);
    if (position.status() == CashManagementStatus.COMPLETED) {
      throw invalid("closing.already.completed", "Cashier closing is already completed.");
    }
    final BigDecimal cashTotal = validator.validateDenominations(request.denominations(), decimalPlaces);
    final List<CashierCheckData> checks =
        selectedChecks(request.checkIds(), request.cashierId(), request.currencyCode(), authorizer, true);
    final BigDecimal checkTotal = sumChecks(checks);
    final BigDecimal actual = cashTotal.add(checkTotal);
    final BigDecimal difference = actual.subtract(position.expectedAmount());
    final CashDifferenceType differenceType = differenceType(difference);
    final String receipt = "CCR-" + request.businessDate() + "-" + request.cashierId() + "-" + shortHash(request.idempotencyKey());

    try {
      jdbcTemplate.update(
          "INSERT INTO m_cashier_reconciliation (idempotency_key, request_fingerprint, receipt_number,"
              + " business_date, office_id, teller_id, cashier_id, currency_code, opening_balance,"
              + " cash_inflows, cash_outflows, previous_settlements, expected_amount, cash_total,"
              + " check_total, actual_amount, difference_amount, difference_type, status, created_by,"
              + " authorized_by, completed_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          request.idempotencyKey(), fingerprint, receipt, request.businessDate(), position.officeId(),
          position.tellerId(), request.cashierId(), request.currencyCode().toUpperCase(Locale.ROOT),
          position.openingBalance(), position.cashInflows(), position.cashOutflows(),
          position.previousSettlements(), position.expectedAmount(), cashTotal, checkTotal, actual,
          difference, differenceType.name(), CashManagementStatus.COMPLETED.name(), authorizer.getId(),
          authorizer.getId(), authorizer.getId());
    } catch (DuplicateKeyException duplicate) {
      final Existing concurrent = existingReconciliation(request.idempotencyKey());
      if (concurrent != null) {
        validateFingerprint(concurrent, fingerprint);
        return closing(concurrent.id(), authorizer);
      }
      throw invalid("closing.already.completed", "Cashier closing is already completed.");
    }
    final Long id = reconciliationId(request.idempotencyKey());
    insertDenominations("m_cashier_reconciliation_denomination", "reconciliation_id", id, request.denominations());
    insertChecks("m_cashier_reconciliation_check", "reconciliation_id", id, checks);
    final SettlementResult settlement =
        settleCash(
            cashier,
            cashTotal,
            request.currencyCode(),
            request.businessDate(),
            "Cashier closing " + receipt,
            request.idempotencyKey() + ":settlement");
    jdbcTemplate.update(
        "UPDATE m_cashier_reconciliation SET cashier_transaction_id=?, accounting_transaction_id=? WHERE id=?",
        settlement.resourceId(),
        settlement.transactionId(),
        id);
    return closing(id, authorizer);
  }

  @Override
  @Transactional(readOnly = true)
  public CashierClosingReceiptData closing(final Long id) {
    final AppUser user = context.authenticatedUser();
    user.validateHasPermissionTo("READ_CASHIER_CLOSING");
    return closing(id, user);
  }

  private CashierClosingReceiptData closing(final Long id, final AppUser user) {
    final List<CashierClosingReceiptData> result = namedJdbcTemplate.query(
        "SELECT r.*, o.name office_name, t.name teller_name, s.display_name cashier_name,"
            + " au.username authorized_username FROM m_cashier_reconciliation r"
            + " JOIN m_office o ON o.id=r.office_id JOIN m_teller t ON t.id=r.teller_id"
            + " JOIN m_cashiers c ON c.id=r.cashier_id JOIN m_staff s ON s.id=c.staff_id"
            + " JOIN m_appuser au ON au.id=r.authorized_by"
            + " WHERE r.id=:id AND o.hierarchy LIKE :hierarchy",
        Map.of("id", id, "hierarchy", user.getOffice().getHierarchy() + "%"),
        (rs, row) -> new CashierClosingReceiptData(
            rs.getLong("id"), rs.getString("receipt_number"), rs.getObject("business_date", LocalDate.class),
            rs.getLong("office_id"), rs.getString("office_name"), rs.getLong("teller_id"),
            rs.getString("teller_name"), rs.getLong("cashier_id"), rs.getString("cashier_name"),
            rs.getString("currency_code"), rs.getBigDecimal("cash_total"), rs.getBigDecimal("check_total"),
            rs.getBigDecimal("expected_amount"), rs.getBigDecimal("actual_amount"),
            rs.getBigDecimal("difference_amount"), CashDifferenceType.valueOf(rs.getString("difference_type")),
            rs.getLong("authorized_by"), rs.getString("authorized_username"), offset(rs.getTimestamp("authorized_on_utc")),
            CashManagementStatus.valueOf(rs.getString("status")), denominations(rs.getLong("id")),
            reconciliationChecks(rs.getLong("id"))));
    if (result.isEmpty()) {
      throw new PlatformDataIntegrityException("error.msg.base.teller.closing.not.found", "Cashier closing not found.");
    }
    return result.get(0);
  }

  @Override
  @Transactional(readOnly = true)
  public GlobalCashCountData globalSettlementContext(
      final LocalDate businessDate, final String currencyCode) {
    final AppUser user = context.authenticatedUser();
    user.validateHasPermissionTo("READ_GLOBAL_SETTLEMENT");
    final Map<String, Object> params = new HashMap<>();
    params.put("date", dateOrToday(businessDate));
    params.put("hierarchy", user.getOffice().getHierarchy() + "%");
    String sql = "SELECT r.id FROM m_cashier_reconciliation r JOIN m_office o ON o.id=r.office_id"
        + " WHERE r.business_date=:date AND o.hierarchy LIKE :hierarchy";
    if (StringUtils.isNotBlank(currencyCode)) {
      sql += " AND LOWER(r.currency_code)=:currency";
      params.put("currency", currencyCode.toLowerCase(Locale.ROOT));
    }
    sql += " ORDER BY r.cashier_id,r.currency_code";
    final List<CashierClosingReceiptData> closings = namedJdbcTemplate.queryForList(sql, params, Long.class).stream()
        .map(id -> closing(id, user))
        .toList();
    return new GlobalCashCountData(
        dateOrToday(businessDate),
        currencyCode == null ? null : currencyCode.toUpperCase(Locale.ROOT),
        sum(closings, CashierClosingReceiptData::expectedAmount),
        sum(closings, CashierClosingReceiptData::cashTotal),
        sum(closings, CashierClosingReceiptData::checkTotal),
        sum(closings, CashierClosingReceiptData::actualAmount),
        sum(closings, CashierClosingReceiptData::difference),
        closings);
  }

  @Override
  @Transactional
  public CashOperationData createOperation(final CashOperationRequest request) {
    final AppUser user = context.authenticatedUser();
    user.validateHasPermissionTo("CREATE_CASH_DEPOSIT");
    final int decimalPlaces = currencyDecimalPlaces(request == null ? null : request.currencyCode());
    validator.validateOperation(request, decimalPlaces);
    validateBusinessDate(request.businessDate());
    final String fingerprint = fingerprint(request);
    final Existing existing = existingOperation(request.idempotencyKey());
    if (existing != null) {
      validateFingerprint(existing, fingerprint);
      return operation(existing.id(), user);
    }
    final CashierData cashier = cashier(request.cashierId(), request.businessDate(), user);
    jdbcTemplate.queryForObject("SELECT id FROM m_cashiers WHERE id=? FOR UPDATE", Long.class, request.cashierId());
    final CashierClosingContextData position = context(request.cashierId(), request.currencyCode(), request.businessDate(), user, true);
    final BigDecimal cashTotal = validator.validateDenominations(request.denominations(), decimalPlaces);
    final List<CashierCheckData> checks = selectedChecks(request.checkIds(), request.cashierId(), request.currencyCode(), user, true);
    final BigDecimal checkTotal = sumChecks(checks);
    final BigDecimal amount = cashTotal.add(checkTotal);
    if (amount.signum() <= 0) {
      throw invalid("operation.amount.invalid", "Cash operation amount must be greater than zero.");
    }
    final BigDecimal availableCash =
        position.expectedAmount().subtract(sumChecks(position.eligibleChecks()));
    if (cashTotal.compareTo(availableCash) > 0) {
      throw invalid("operation.cash.insufficient", "Cash operation exceeds the authoritative cashier balance.");
    }
    final String receipt = "COP-" + request.businessDate() + "-" + request.cashierId() + "-" + shortHash(request.idempotencyKey());
    jdbcTemplate.update(
        "INSERT INTO m_cash_operation_transaction (idempotency_key,request_fingerprint,receipt_number,"
            + "transaction_type,business_date,currency_code,amount,cash_total,check_total,office_id,teller_id,"
            + "cashier_id,actor_id,description,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        request.idempotencyKey(), fingerprint, receipt, request.transactionType().name(), request.businessDate(),
        request.currencyCode().toUpperCase(Locale.ROOT), amount, cashTotal, checkTotal, cashier.getOfficeId(),
        cashier.getTellerId(), cashier.getId(), user.getId(), StringUtils.abbreviate(request.description(), 500),
        CashManagementStatus.COMPLETED.name());
    final Long id = operationId(request.idempotencyKey());
    insertDenominations("m_cash_operation_denomination", "operation_id", id, request.denominations());
    insertChecks("m_cash_operation_check", "operation_id", id, checks);
    final SettlementResult settlement =
        settleCash(
            cashier,
            cashTotal,
            request.currencyCode(),
            request.businessDate(),
            StringUtils.defaultIfBlank(request.description(), request.transactionType().name()),
            request.idempotencyKey() + ":settlement");
    jdbcTemplate.update(
        "UPDATE m_cash_operation_transaction SET cashier_transaction_id=? WHERE id=?",
        settlement.resourceId(),
        id);
    return operation(id, user);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<CashOperationData> transactionHistory(
      final LocalDate fromDate, final LocalDate toDate, final Long cashierId, final String currencyCode,
      final CashManagementStatus status, final CashOperationType transactionType, final String query,
      final Integer offset, final Integer limit) {
    final AppUser user = context.authenticatedUser();
    user.validateHasPermissionTo("READ_CASH_OPERATION_HISTORY");
    final Map<String, Object> params = new HashMap<>();
    params.put("hierarchy", user.getOffice().getHierarchy() + "%");
    final StringBuilder where = new StringBuilder(" WHERE o.hierarchy LIKE :hierarchy");
    appendFilters(where, params, fromDate, toDate, cashierId, currencyCode, status, transactionType, query);
    final int resolvedLimit = Math.min(limit == null || limit <= 0 ? 25 : limit, MAX_LIMIT);
    final int resolvedOffset = offset == null || offset < 0 ? 0 : offset;
    params.put("limit", resolvedLimit);
    params.put("offset", resolvedOffset);
    final Integer count = namedJdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM m_cash_operation_transaction x JOIN m_office o ON o.id=x.office_id" + where,
        params, Integer.class);
    final List<Long> ids = namedJdbcTemplate.queryForList(
        "SELECT x.id FROM m_cash_operation_transaction x JOIN m_office o ON o.id=x.office_id" + where
            + " ORDER BY x.business_date DESC,x.id DESC LIMIT :limit OFFSET :offset", params, Long.class);
    return new Page<>(ids.stream().map(id -> operation(id, user)).toList(), count == null ? 0 : count);
  }

  @Override
  @Transactional(readOnly = true)
  public List<CashHoldingData> cashHoldings(
      final LocalDate businessDate, final Long cashierId, final String currencyCode) {
    final AppUser user = context.authenticatedUser();
    user.validateHasPermissionTo("READ_CASH_HOLDINGS");
    final LocalDate date = dateOrToday(businessDate);
    final Map<String, Object> params = new HashMap<>();
    params.put("date", date);
    params.put("hierarchy", user.getOffice().getHierarchy() + "%");
    String sql = "SELECT DISTINCT c.id cashier_id,ct.currency_code FROM m_cashiers c JOIN m_teller t ON t.id=c.teller_id"
        + " JOIN m_office o ON o.id=t.office_id JOIN m_cashier_transactions ct ON ct.cashier_id=c.id"
        + " WHERE ct.txn_date=:date AND o.hierarchy LIKE :hierarchy";
    if (cashierId != null) { sql += " AND c.id=:cashierId"; params.put("cashierId", cashierId); }
    if (StringUtils.isNotBlank(currencyCode)) { sql += " AND LOWER(ct.currency_code)=:currency"; params.put("currency", currencyCode.toLowerCase(Locale.ROOT)); }
    sql += " ORDER BY c.id,ct.currency_code";
    return namedJdbcTemplate.query(sql, params, (rs, row) ->
        holding(rs.getLong("cashier_id"), rs.getString("currency_code"), date, user));
  }

  private CashHoldingData holding(final Long cashierId, final String currency, final LocalDate date, final AppUser user) {
    final CashierClosingContextData value = context(cashierId, currency, date, user, true);
    return new CashHoldingData(date, value.officeId(), value.tellerId(), cashierId, value.cashierName(), currency,
        value.openingBalance(), value.cashInflows(), value.cashOutflows(), value.previousSettlements(),
        value.expectedAmount().subtract(sumChecks(value.eligibleChecks())), value.status());
  }

  private CashierClosingContextData context(
      final Long cashierId, final String currencyCode, final LocalDate date, final AppUser user, final boolean internal) {
    if (cashierId == null || StringUtils.isBlank(currencyCode)) {
      throw invalid("closing.context.required", "cashierId and currencyCode are required.");
    }
    currencyDecimalPlaces(currencyCode);
    final CashierData cashier = cashier(cashierId, date, user);
    final TellerData teller = tellerService.findTeller(cashier.getTellerId());
    final Map<String, Object> params = Map.of("cashierId", cashierId, "date", date, "currency", currencyCode.toLowerCase(Locale.ROOT));
    final Map<Integer, BigDecimal> totals = new HashMap<>();
    namedJdbcTemplate.query(
        "SELECT txn_type,COALESCE(SUM(txn_amount),0) total FROM m_cashier_transactions"
            + " WHERE cashier_id=:cashierId AND txn_date=:date AND LOWER(currency_code)=:currency GROUP BY txn_type",
        params, rs -> { while (rs.next()) totals.put(rs.getInt("txn_type"), rs.getBigDecimal("total")); });
    final BigDecimal opening = totals.getOrDefault(ALLOCATION, BigDecimal.ZERO);
    final BigDecimal inflows = totals.getOrDefault(CASH_IN, BigDecimal.ZERO);
    final BigDecimal outflows = totals.getOrDefault(CASH_OUT, BigDecimal.ZERO);
    final BigDecimal settlements = totals.getOrDefault(SETTLEMENT, BigDecimal.ZERO);
    final List<CashierCheckData> eligibleChecks = eligibleChecks(cashierId, currencyCode, user);
    final BigDecimal expected =
        opening
            .add(inflows)
            .subtract(outflows)
            .subtract(settlements)
            .add(sumChecks(eligibleChecks));
    final boolean closed = namedJdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM m_cashier_reconciliation WHERE cashier_id=:cashierId AND business_date=:date"
            + " AND LOWER(currency_code)=:currency", params, Integer.class) > 0;
    return new CashierClosingContextData(date, cashier.getOfficeId(), cashier.getOfficeName(), cashier.getTellerId(),
        teller.getName(), cashierId, cashier.getStaffName(), currencyCode.toUpperCase(Locale.ROOT), opening, inflows,
        outflows, settlements, expected, eligibleChecks,
        closed ? CashManagementStatus.COMPLETED : CashManagementStatus.OPEN);
  }

  private CashierData cashier(final Long id, final LocalDate date, final AppUser user) {
    final CashierData cashier;
    try { cashier = tellerService.findCashier(id); }
    catch (RuntimeException ex) { throw invalid("cashier.not.found", "Cashier was not found."); }
    final Integer allowed = namedJdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM m_office WHERE id=:officeId AND hierarchy LIKE :hierarchy",
        Map.of("officeId", cashier.getOfficeId(), "hierarchy", user.getOffice().getHierarchy() + "%"), Integer.class);
    if (allowed == null || allowed == 0) throw invalid("cashier.office.forbidden", "Cashier is outside the authorized office hierarchy.");
    if ((cashier.getStartDate() != null && date.isBefore(cashier.getStartDate()))
        || (cashier.getEndDate() != null && date.isAfter(cashier.getEndDate()))) {
      throw invalid("cashier.not.allocated", "Cashier has no active allocation for the business date.");
    }
    return cashier;
  }

  private List<CashierCheckData> eligibleChecks(final Long cashierId, final String currency, final AppUser user) {
    return selectedChecks(null, cashierId, currency, user, false);
  }

  private List<CashierCheckData> selectedChecks(
      final List<Long> ids, final Long cashierId, final String currency, final AppUser user, final boolean lock) {
    if (ids != null && ids.isEmpty()) return List.of();
    final Map<String, Object> params = new HashMap<>();
    params.put("cashierId", cashierId); params.put("currency", currency.toLowerCase(Locale.ROOT));
    params.put("hierarchy", user.getOffice().getHierarchy() + "%");
    String sql = "SELECT cd.id,cd.check_bank,cd.check_number,cd.amount FROM m_base_teller_deposit_check_detail cd"
        + " JOIN m_base_teller_deposit d ON d.id=cd.deposit_id JOIN m_office o ON o.id=d.office_id"
        + " WHERE d.cashier_id=:cashierId AND LOWER(d.currency_code)=:currency AND d.status='COMPLETED'"
        + " AND cd.clearing_status='CLEARED' AND o.hierarchy LIKE :hierarchy"
        + " AND NOT EXISTS (SELECT 1 FROM m_cashier_reconciliation_check rc WHERE rc.deposit_check_detail_id=cd.id)"
        + " AND NOT EXISTS (SELECT 1 FROM m_cash_operation_check oc WHERE oc.deposit_check_detail_id=cd.id)";
    if (ids != null) { sql += " AND cd.id IN (:ids)"; params.put("ids", ids); }
    sql += " ORDER BY cd.id" + (lock ? " FOR UPDATE" : "");
    final List<CashierCheckData> result = namedJdbcTemplate.query(sql, params,
        (rs, row) -> new CashierCheckData(rs.getLong("id"), rs.getString("check_bank"), rs.getString("check_number"), rs.getBigDecimal("amount")));
    if (ids != null && result.size() != ids.size()) throw invalid("check.ineligible", "One or more checks are missing, ineligible, mismatched, or already reconciled.");
    return result;
  }

  private void insertDenominations(final String table, final String parentColumn, final Long parentId,
      final List<BaseTellerDenominationData> denominations) {
    if (denominations == null) return;
    for (BaseTellerDenominationData item : denominations) {
      jdbcTemplate.update("INSERT INTO " + table + " (" + parentColumn
              + ",denomination_identifier,denomination_value,quantity,line_total) VALUES (?,?,?,?,?)",
          parentId, item.denominationId(), item.value(), item.quantity(),
          item.value().multiply(BigDecimal.valueOf(item.quantity())));
    }
  }

  private void insertChecks(final String table, final String parentColumn, final Long parentId, final List<CashierCheckData> checks) {
    for (CashierCheckData check : checks) jdbcTemplate.update(
        "INSERT INTO " + table + " (" + parentColumn + ",deposit_check_detail_id,amount) VALUES (?,?,?)",
        parentId, check.id(), check.amount());
  }

  private SettlementResult settleCash(
      final CashierData cashier,
      final BigDecimal amount,
      final String currency,
      final LocalDate date,
      final String note,
      final String idempotencyKey) {
    if (amount.signum() == 0) {
      return new SettlementResult(null, null);
    }
    final Map<String, Object> body = new HashMap<>();
    body.put("currencyCode", currency.toUpperCase(Locale.ROOT));
    body.put("txnAmount", amount);
    body.put("txnNote", note);
    body.put("locale", "en");
    body.put("dateFormat", "yyyy-MM-dd");
    body.put("txnDate", date.toString());
    final CommandWrapper command =
        new CommandWrapperBuilder()
            .settleCashFromCashier(cashier.getTellerId(), cashier.getId())
            .withJson(GSON.toJson(body))
            .build(idempotencyKey);
    final CommandProcessingResult result = commandService.logCommandSource(command);
    return new SettlementResult(result.getResourceId(), result.getTransactionId());
  }

  private List<BaseTellerDenominationData> denominations(final Long id) {
    return jdbcTemplate.query("SELECT denomination_identifier,denomination_value,quantity FROM m_cashier_reconciliation_denomination WHERE reconciliation_id=? ORDER BY denomination_value DESC",
        (rs, row) -> new BaseTellerDenominationData(rs.getString(1), rs.getBigDecimal(2), rs.getLong(3)), id);
  }

  private List<CashierCheckData> reconciliationChecks(final Long id) {
    return jdbcTemplate.query("SELECT rc.deposit_check_detail_id,cd.check_bank,cd.check_number,rc.amount FROM m_cashier_reconciliation_check rc JOIN m_base_teller_deposit_check_detail cd ON cd.id=rc.deposit_check_detail_id WHERE rc.reconciliation_id=? ORDER BY rc.id",
        (rs, row) -> new CashierCheckData(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4)), id);
  }

  private CashOperationData operation(final Long id, final AppUser user) {
    final List<CashOperationData> rows = namedJdbcTemplate.query(
        "SELECT x.*,s.display_name cashier_name,u.username actor_username FROM m_cash_operation_transaction x"
            + " JOIN m_office o ON o.id=x.office_id JOIN m_cashiers c ON c.id=x.cashier_id"
            + " JOIN m_staff s ON s.id=c.staff_id JOIN m_appuser u ON u.id=x.actor_id"
            + " WHERE x.id=:id AND o.hierarchy LIKE :hierarchy",
        Map.of("id", id, "hierarchy", user.getOffice().getHierarchy() + "%"), (rs, row) ->
            new CashOperationData(rs.getLong("id"), rs.getString("receipt_number"), CashOperationType.valueOf(rs.getString("transaction_type")),
                rs.getObject("business_date", LocalDate.class), rs.getString("currency_code"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("cash_total"), rs.getBigDecimal("check_total"), rs.getLong("office_id"), rs.getLong("teller_id"),
                rs.getLong("cashier_id"), rs.getString("cashier_name"), rs.getLong("actor_id"), rs.getString("actor_username"),
                rs.getString("description"), CashManagementStatus.valueOf(rs.getString("status")), offset(rs.getTimestamp("created_on_utc"))));
    if (rows.isEmpty()) throw new PlatformDataIntegrityException("error.msg.base.teller.operation.not.found", "Cash operation not found.");
    return rows.get(0);
  }

  private int currencyDecimalPlaces(final String currency) {
    if (StringUtils.isBlank(currency)) return 0;
    final List<Integer> values = jdbcTemplate.query("SELECT decimal_places FROM m_currency WHERE LOWER(code)=LOWER(?)",
        (rs, row) -> rs.getInt(1), currency);
    if (values.isEmpty()) throw invalid("currency.unsupported", "Currency is not enabled in Fineract.");
    return values.get(0);
  }

  private void validateBusinessDate(final LocalDate date) {
    if (!DateUtils.getBusinessLocalDate().equals(date)) throw invalid("business.date.invalid", "Business date must equal the current Fineract business date.");
  }

  private Existing existingReconciliation(final String key) { return existing("m_cashier_reconciliation", key); }
  private Existing existingOperation(final String key) { return existing("m_cash_operation_transaction", key); }
  private Existing existing(final String table, final String key) {
    if (key == null) return null;
    final List<Existing> rows = jdbcTemplate.query("SELECT id,request_fingerprint FROM " + table + " WHERE idempotency_key=?",
        (rs, row) -> new Existing(rs.getLong(1), rs.getString(2)), key);
    return rows.isEmpty() ? null : rows.get(0);
  }
  private Long reconciliationId(final String key) { return jdbcTemplate.queryForObject("SELECT id FROM m_cashier_reconciliation WHERE idempotency_key=?", Long.class, key); }
  private Long operationId(final String key) { return jdbcTemplate.queryForObject("SELECT id FROM m_cash_operation_transaction WHERE idempotency_key=?", Long.class, key); }
  private void validateFingerprint(final Existing existing, final String fingerprint) {
    if (!existing.fingerprint().equals(fingerprint)) throw invalid("idempotency.conflict", "idempotencyKey was already used for a different request.");
  }

  private static BigDecimal sumChecks(final List<CashierCheckData> checks) { return checks.stream().map(CashierCheckData::amount).reduce(BigDecimal.ZERO, BigDecimal::add); }
  private static BigDecimal sum(
      final List<CashierClosingReceiptData> closings,
      final java.util.function.Function<CashierClosingReceiptData, BigDecimal> value) {
    return closings.stream().map(value).reduce(BigDecimal.ZERO, BigDecimal::add);
  }
  private static CashDifferenceType differenceType(final BigDecimal value) { return value.signum() == 0 ? CashDifferenceType.BALANCED : value.signum() > 0 ? CashDifferenceType.OVERAGE : CashDifferenceType.SHORTAGE; }
  private static LocalDate dateOrToday(final LocalDate date) { return date == null ? DateUtils.getBusinessLocalDate() : date; }
  private static OffsetDateTime offset(final Timestamp value) { return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC); }
  private static GeneralPlatformDomainRuleException invalid(final String code, final String message) { return new GeneralPlatformDomainRuleException("error.msg.base.teller." + code, message); }
  private static String fingerprint(final Object request) { return hash(GSON.toJson(request)); }
  private static String shortHash(final String value) { return hash(value).substring(0, 12).toUpperCase(Locale.ROOT); }
  private static String hash(final String value) {
    try {
      final byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      final StringBuilder result = new StringBuilder();
      for (byte item : bytes) result.append(String.format("%02x", item));
      return result.toString();
    } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  private static void appendFilters(final StringBuilder where, final Map<String, Object> params,
      final LocalDate from, final LocalDate to, final Long cashierId, final String currency,
      final CashManagementStatus status, final CashOperationType type, final String query) {
    if (from != null) { where.append(" AND x.business_date>=:from"); params.put("from", from); }
    if (to != null) { where.append(" AND x.business_date<=:to"); params.put("to", to); }
    if (cashierId != null) { where.append(" AND x.cashier_id=:cashierId"); params.put("cashierId", cashierId); }
    if (StringUtils.isNotBlank(currency)) { where.append(" AND LOWER(x.currency_code)=:currency"); params.put("currency", currency.toLowerCase(Locale.ROOT)); }
    if (status != null) { where.append(" AND x.status=:status"); params.put("status", status.name()); }
    if (type != null) { where.append(" AND x.transaction_type=:type"); params.put("type", type.name()); }
    if (StringUtils.isNotBlank(query)) { where.append(" AND (LOWER(COALESCE(x.description,'')) LIKE :query OR LOWER(x.receipt_number) LIKE :query)"); params.put("query", "%" + query.toLowerCase(Locale.ROOT) + "%"); }
  }

  private record Existing(Long id, String fingerprint) {}

  private record SettlementResult(Long resourceId, String transactionId) {}
}
