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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccountRepositoryWrapper;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.CashAllocationCashierData;
import org.apache.fineract.baseteller.data.CashAllocationContextData;
import org.apache.fineract.baseteller.data.CashAllocationCurrencyData;
import org.apache.fineract.baseteller.data.CashAllocationDenominationData;
import org.apache.fineract.baseteller.data.CashAllocationPreviewData;
import org.apache.fineract.baseteller.data.CashAllocationReceiptData;
import org.apache.fineract.baseteller.data.CashAllocationRequest;
import org.apache.fineract.baseteller.data.CashAllocationType;
import org.apache.fineract.baseteller.data.CashManagementStatus;
import org.apache.fineract.baseteller.validation.CashAllocationValidator;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CashAllocationPlatformServiceImpl
    implements CashAllocationReadPlatformService, CashAllocationWritePlatformService {

  private static final String RESOURCE = "BASE_TELLER_CASH_ALLOCATION";
  private static final int ALLOCATION = 101;
  private static final int SETTLEMENT = 102;
  private static final int CASH_IN = 103;
  private static final int CASH_OUT = 104;
  private static final BigDecimal ZERO = BigDecimal.ZERO;
  private static final Gson GSON = new Gson();

  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;
  private final PlatformSecurityContext context;
  private final CashAllocationValidator validator;
  private final PortfolioCommandSourceWritePlatformService commandService;
  private final FinancialActivityAccountRepositoryWrapper financialActivityAccounts;

  @Override
  @Transactional(readOnly = true)
  public CashAllocationContextData context(final Long officeId, final String currencyCode) {
    final AppUser user = context.authenticatedUser();
    user.validateHasReadPermission(RESOURCE);
    final OfficeSnapshot office = office(officeId, user, false);
    final LocalDate businessDate = DateUtils.getBusinessLocalDate();
    final List<CashAllocationCurrencyData> currencies = currencies(currencyCode);
    final String selectedCurrency =
        StringUtils.isBlank(currencyCode) ? null : currencyCode.trim().toUpperCase(Locale.ROOT);
    return new CashAllocationContextData(
        businessDate,
        office.id(),
        office.name(),
        selectedCurrency,
        selectedCurrency == null ? ZERO : vaultBalance(office.id(), businessDate, selectedCurrency),
        currencies,
        activeCashiers(office.id(), businessDate, selectedCurrency));
  }

  @Override
  @Transactional(readOnly = true)
  public CashAllocationPreviewData preview(final CashAllocationRequest request) {
    final AppUser user = context.authenticatedUser();
    user.validateHasReadPermission(RESOURCE);
    final PreparedAllocation prepared = prepare(request, user, false, false);
    return preview(prepared);
  }

  @Override
  @Transactional
  public CashAllocationReceiptData allocate(final CashAllocationRequest request) {
    final AppUser user = context.authenticatedUser();
    user.validateHasCreatePermission(RESOURCE);
    validator.validate(request);
    final String fingerprint = hash(GSON.toJson(request));
    Existing existing = existing(request.idempotencyKey());
    if (existing != null) {
      validateFingerprint(existing, fingerprint);
      return receipt(existing.id(), user);
    }

    final PreparedAllocation prepared = prepare(request, user, true, true);
    existing = existing(request.idempotencyKey());
    if (existing != null) {
      validateFingerprint(existing, fingerprint);
      return receipt(existing.id(), user);
    }
    ensureDrawerNotOpened(prepared);
    final String reference =
        "CA-"
            + request.businessDate()
            + "-"
            + request.officeId()
            + "-"
            + shortHash(request.idempotencyKey());

    try {
      insertAllocation(prepared, user, fingerprint, reference);
    } catch (DuplicateKeyException duplicate) {
      final Existing concurrent = existing(request.idempotencyKey());
      if (concurrent != null) {
        validateFingerprint(concurrent, fingerprint);
        return receipt(concurrent.id(), user);
      }
      throw invalid(
          "drawer.already.opened",
          "The destination drawer or vault is already opened for this business date and currency.");
    }
    final Long id = allocationId(request.idempotencyKey());
    insertDenominations(id, prepared.denominations());

    Long sourceTransactionId = null;
    Long destinationTransactionId = null;
    String accountingTransactionId = null;
    if (request.operationType() == CashAllocationType.SAFE_VAULT_OPENING) {
      accountingTransactionId = postVaultOpening(prepared, reference, request.idempotencyKey());
    } else if (request.operationType() == CashAllocationType.HEAD_CASHIER_ALLOCATION) {
      final CommandProcessingResult allocation =
          allocateToCashier(
              prepared.destination(), prepared.total(), prepared.currency(), prepared.date(), reference,
              request.idempotencyKey() + ":destination");
      destinationTransactionId = requiredCashierTransactionId(allocation);
    } else {
      final CommandProcessingResult settlement =
          settleFromCashier(
              prepared.source(), prepared.total(), prepared.currency(), prepared.date(), reference,
              request.idempotencyKey() + ":source");
      sourceTransactionId = requiredCashierTransactionId(settlement);
      final CommandProcessingResult allocation =
          allocateToCashier(
              prepared.destination(), prepared.total(), prepared.currency(), prepared.date(), reference,
              request.idempotencyKey() + ":destination");
      destinationTransactionId = requiredCashierTransactionId(allocation);
    }

    jdbcTemplate.update(
        "UPDATE m_cash_allocation SET source_cashier_transaction_id=?,"
            + "destination_cashier_transaction_id=?,accounting_transaction_id=?,status='COMPLETED',"
            + "completed_on_utc=CURRENT_TIMESTAMP WHERE id=?",
        sourceTransactionId,
        destinationTransactionId,
        accountingTransactionId,
        id);
    return receipt(id, user);
  }

  @Override
  @Transactional(readOnly = true)
  public CashAllocationReceiptData retrieve(final Long allocationId) {
    final AppUser user = context.authenticatedUser();
    user.validateHasReadPermission(RESOURCE);
    return receipt(allocationId, user);
  }

  @Override
  @Transactional(readOnly = true)
  public CashAllocationReceiptData reprint(final Long allocationId) {
    final AppUser user = context.authenticatedUser();
    user.validateHasPermissionTo("REPRINT_BASE_TELLER_CASH_ALLOCATION");
    return receipt(allocationId, user);
  }

  private PreparedAllocation prepare(
      final CashAllocationRequest request,
      final AppUser user,
      final boolean lock,
      final boolean rejectOpenedDestination) {
    validator.validate(request);
    if (!DateUtils.getBusinessLocalDate().equals(request.businessDate())) {
      throw invalid(
          "business.date.invalid", "businessDate must equal the current Fineract business date.");
    }
    final OfficeSnapshot office = office(request.officeId(), user, lock);
    final CurrencySnapshot currency = currency(request.currencyCode());
    final List<CashAllocationDenominationData> denominations =
        authoritativeDenominations(request.denominations(), currency);
    final BigDecimal total = CashAllocationCalculator.total(denominations);
    validator.validateAuthoritativeTotal(request, total, currency.decimalPlaces());
    validateAccountingConfiguration(request.operationType());

    CashierSnapshot source = null;
    CashierSnapshot destination = null;
    if (request.sourceCashierId() != null) {
      source = cashier(request.sourceCashierId(), office.id(), request.businessDate(), lock);
    }
    if (request.destinationCashierId() != null) {
      destination = cashier(request.destinationCashierId(), office.id(), request.businessDate(), lock);
    }
    if (request.operationType() == CashAllocationType.OPERATIONAL_TELLER_ALLOCATION
        && !isFundedHeadCashier(source.id(), office.id(), request.businessDate(), currency.code())) {
      throw invalid(
          "source.not.head.cashier",
          "The source cashier has no completed head-cashier opening allocation for this business date and currency.");
    }
    if (source != null) rejectClosed(source.id(), request.businessDate(), currency.code());
    if (destination != null) rejectClosed(destination.id(), request.businessDate(), currency.code());

    final BigDecimal vaultBefore = vaultBalance(office.id(), request.businessDate(), currency.code());
    final BigDecimal sourceBefore =
        request.operationType() == CashAllocationType.HEAD_CASHIER_ALLOCATION
            ? vaultBefore
            : source == null ? null : cashierBalance(source.id(), request.businessDate(), currency.code());
    final BigDecimal destinationBefore =
        request.operationType() == CashAllocationType.SAFE_VAULT_OPENING
            ? vaultBefore
            : cashierBalance(destination.id(), request.businessDate(), currency.code());
    if (request.operationType() != CashAllocationType.SAFE_VAULT_OPENING
        && sourceBefore.compareTo(total) < 0) {
      throw invalid("source.insufficient", "The allocation exceeds the authoritative source cash balance.");
    }
    final BigDecimal sourceAfter = sourceBefore == null ? null : sourceBefore.subtract(total);
    final BigDecimal destinationAfter = destinationBefore.add(total);
    final PreparedAllocation prepared =
        new PreparedAllocation(
            request,
            office,
            currency.code(),
            request.businessDate(),
            total,
            denominations,
            source,
            destination,
            sourceBefore,
            sourceAfter,
            destinationBefore,
            destinationAfter);
    if (rejectOpenedDestination) ensureDrawerNotOpened(prepared);
    return prepared;
  }

  private CashAllocationPreviewData preview(final PreparedAllocation value) {
    final String source =
        value.request().operationType() == CashAllocationType.SAFE_VAULT_OPENING
            ? "Configured opening balance contra"
            : value.request().operationType() == CashAllocationType.HEAD_CASHIER_ALLOCATION
                ? "Safe/Vault - " + value.office().name()
                : value.source().displayName();
    final String destination =
        value.request().operationType() == CashAllocationType.SAFE_VAULT_OPENING
            ? "Safe/Vault - " + value.office().name()
            : value.destination().displayName();
    return new CashAllocationPreviewData(
        value.request().operationType(),
        value.date(),
        value.office().id(),
        value.currency(),
        source,
        destination,
        value.total(),
        value.total(),
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        value.total(),
        value.denominations());
  }

  private OfficeSnapshot office(final Long id, final AppUser user, final boolean lock) {
    if (id == null || user.getOffice() == null) {
      throw invalid("office.required", "A valid officeId and authenticated office are required.");
    }
    final String sql =
        "SELECT id,name FROM m_office WHERE id=:id AND hierarchy LIKE :hierarchy"
            + (lock ? " FOR UPDATE" : "");
    final List<OfficeSnapshot> rows =
        namedJdbcTemplate.query(
            sql,
            Map.of("id", id, "hierarchy", user.getOffice().getHierarchy() + "%"),
            (rs, row) -> new OfficeSnapshot(rs.getLong(1), rs.getString(2)));
    if (rows.isEmpty()) {
      throw invalid("office.forbidden", "Office is outside the authenticated user's hierarchy.");
    }
    return rows.get(0);
  }

  private CurrencySnapshot currency(final String code) {
    final List<CurrencySnapshot> rows =
        jdbcTemplate.query(
            "SELECT code,decimal_places,name,display_symbol FROM m_organisation_currency"
                + " WHERE LOWER(code)=LOWER(?)",
            (rs, row) ->
                new CurrencySnapshot(
                    rs.getString("code").toUpperCase(Locale.ROOT),
                    rs.getInt("decimal_places"),
                    rs.getString("name"),
                    rs.getString("display_symbol")),
            code.trim());
    if (rows.isEmpty()) {
      throw invalid("currency.unsupported", "Currency is not enabled for the Fineract tenant.");
    }
    return rows.get(0);
  }

  private List<CashAllocationCurrencyData> currencies(final String requestedCurrency) {
    final Map<String, CurrencySnapshot> configured = new LinkedHashMap<>();
    String sql =
        "SELECT code,decimal_places,name,display_symbol FROM m_organisation_currency";
    final List<Object> arguments = new ArrayList<>();
    if (StringUtils.isNotBlank(requestedCurrency)) {
      sql += " WHERE LOWER(code)=LOWER(?)";
      arguments.add(requestedCurrency.trim());
    }
    sql += " ORDER BY code";
    jdbcTemplate
        .query(
            sql,
            (rs, row) ->
                new CurrencySnapshot(
                    rs.getString("code").toUpperCase(Locale.ROOT),
                    rs.getInt("decimal_places"),
                    rs.getString("name"),
                    rs.getString("display_symbol")),
            arguments.toArray())
        .forEach(item -> configured.put(item.code(), item));
    if (StringUtils.isNotBlank(requestedCurrency) && configured.isEmpty()) {
      throw invalid("currency.unsupported", "Currency is not enabled for the Fineract tenant.");
    }
    return configured.values().stream()
        .map(
            item ->
                new CashAllocationCurrencyData(
                    item.code(),
                    item.name(),
                    item.symbol(),
                    item.decimalPlaces(),
                    configuredDenominations(item.code())))
        .toList();
  }

  private List<CashAllocationDenominationData> configuredDenominations(final String currency) {
    return jdbcTemplate.query(
        "SELECT identifier,denomination_type,value FROM m_service_payment_denomination"
            + " WHERE LOWER(currency_code)=LOWER(?) AND active=true ORDER BY value DESC",
        (rs, row) ->
            new CashAllocationDenominationData(
                rs.getString(1), rs.getString(2), rs.getBigDecimal(3), 0L, ZERO),
        currency);
  }

  private List<CashAllocationDenominationData> authoritativeDenominations(
      final List<BaseTellerDenominationData> requested, final CurrencySnapshot currency) {
    return CashAllocationCalculator.calculate(
        requested, configuredDenominations(currency.code()), currency.decimalPlaces());
  }

  private List<CashAllocationCashierData> activeCashiers(
      final Long officeId, final LocalDate date, final String currency) {
    final List<CashierSnapshot> cashiers =
        namedJdbcTemplate.query(
            "SELECT c.id,t.id teller_id,t.name teller_name,c.staff_id,s.display_name FROM m_cashiers c"
                + " JOIN m_tellers t ON t.id=c.teller_id JOIN m_staff s ON s.id=c.staff_id"
                + " WHERE t.office_id=:officeId AND t.state=300"
                + " AND (t.valid_from IS NULL OR t.valid_from<=:date)"
                + " AND (t.valid_to IS NULL OR t.valid_to>=:date)"
                + " AND (c.start_date IS NULL OR c.start_date<=:date)"
                + " AND (c.end_date IS NULL OR c.end_date>=:date) ORDER BY s.display_name,c.id",
            Map.of("officeId", officeId, "date", date),
            (rs, row) ->
                new CashierSnapshot(
                    rs.getLong("id"),
                    rs.getLong("teller_id"),
                    rs.getString("teller_name"),
                    rs.getLong("staff_id"),
                    rs.getString("display_name")));
    return cashiers.stream()
        .map(
            cashier ->
                new CashAllocationCashierData(
                    cashier.id(),
                    cashier.tellerId(),
                    cashier.tellerName(),
                    cashier.staffId(),
                    cashier.cashierName(),
                    currency != null
                        && isFundedHeadCashier(cashier.id(), officeId, date, currency),
                    currency == null ? ZERO : cashierBalance(cashier.id(), date, currency)))
        .toList();
  }

  private CashierSnapshot cashier(
      final Long id, final Long officeId, final LocalDate date, final boolean lock) {
    final String sql =
        "SELECT c.id,t.id teller_id,t.name teller_name,c.staff_id,s.display_name FROM m_cashiers c"
            + " JOIN m_tellers t ON t.id=c.teller_id JOIN m_staff s ON s.id=c.staff_id"
            + " WHERE c.id=:id AND t.office_id=:officeId AND t.state=300"
            + " AND (t.valid_from IS NULL OR t.valid_from<=:date)"
            + " AND (t.valid_to IS NULL OR t.valid_to>=:date)"
            + " AND (c.start_date IS NULL OR c.start_date<=:date)"
            + " AND (c.end_date IS NULL OR c.end_date>=:date)"
            + (lock ? " FOR UPDATE OF c" : "");
    final List<CashierSnapshot> rows =
        namedJdbcTemplate.query(
            sql,
            Map.of("id", id, "officeId", officeId, "date", date),
            (rs, row) ->
                new CashierSnapshot(
                    rs.getLong("id"),
                    rs.getLong("teller_id"),
                    rs.getString("teller_name"),
                    rs.getLong("staff_id"),
                    rs.getString("display_name")));
    if (rows.isEmpty()) {
      throw invalid(
          "cashier.invalid",
          "Cashier is missing, inactive, outside the office, or not assigned on the business date.");
    }
    return rows.get(0);
  }

  private BigDecimal cashierBalance(
      final Long cashierId, final LocalDate date, final String currency) {
    final BigDecimal result =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN txn_type IN (?,?) THEN txn_amount"
                + " WHEN txn_type IN (?,?) THEN -txn_amount ELSE 0 END),0)"
                + " FROM m_cashier_transactions WHERE cashier_id=? AND txn_date=?"
                + " AND LOWER(currency_code)=LOWER(?)",
            BigDecimal.class,
            ALLOCATION,
            CASH_IN,
            SETTLEMENT,
            CASH_OUT,
            cashierId,
            date,
            currency);
    return result == null ? ZERO : result;
  }

  private BigDecimal vaultBalance(final Long officeId, final LocalDate date, final String currency) {
    final BigDecimal result =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN operation_type='SAFE_VAULT_OPENING' THEN amount"
                + " WHEN operation_type='HEAD_CASHIER_ALLOCATION' THEN -amount ELSE 0 END),0)"
                + " FROM m_cash_allocation WHERE office_id=? AND business_date=?"
                + " AND LOWER(currency_code)=LOWER(?) AND status='COMPLETED'",
            BigDecimal.class,
            officeId,
            date,
            currency);
    return result == null ? ZERO : result;
  }

  private boolean isFundedHeadCashier(
      final Long cashierId, final Long officeId, final LocalDate date, final String currency) {
    final Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_cash_allocation WHERE operation_type='HEAD_CASHIER_ALLOCATION'"
                + " AND destination_cashier_id=? AND office_id=? AND business_date=?"
                + " AND LOWER(currency_code)=LOWER(?) AND status='COMPLETED'",
            Integer.class,
            cashierId,
            officeId,
            date,
            currency);
    return count != null && count > 0;
  }

  private void rejectClosed(final Long cashierId, final LocalDate date, final String currency) {
    final Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_cashier_reconciliation WHERE cashier_id=? AND business_date=?"
                + " AND LOWER(currency_code)=LOWER(?)",
            Integer.class,
            cashierId,
            date,
            currency);
    if (count != null && count > 0) {
      throw invalid("drawer.closed", "Cash cannot be allocated to or from a reconciled drawer.");
    }
  }

  private void ensureDrawerNotOpened(final PreparedAllocation prepared) {
    final String destinationKey = destinationKey(prepared.request());
    final Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_cash_allocation WHERE office_id=? AND business_date=?"
                + " AND LOWER(currency_code)=LOWER(?) AND destination_key=? AND status='COMPLETED'",
            Integer.class,
            prepared.office().id(),
            prepared.date(),
            prepared.currency(),
            destinationKey);
    if (count != null && count > 0) {
      throw invalid(
          "drawer.already.opened",
          "The destination drawer or vault is already opened for this business date and currency.");
    }
  }

  private void validateAccountingConfiguration(final CashAllocationType type) {
    financialActivityAccounts.findByFinancialActivityTypeWithNotFoundDetection(
        FinancialActivity.CASH_AT_MAINVAULT.getValue());
    if (type == CashAllocationType.SAFE_VAULT_OPENING) {
      financialActivityAccounts.findByFinancialActivityTypeWithNotFoundDetection(
          FinancialActivity.OPENING_BALANCES_TRANSFER_CONTRA.getValue());
    } else {
      financialActivityAccounts.findByFinancialActivityTypeWithNotFoundDetection(
          FinancialActivity.CASH_AT_TELLER.getValue());
    }
  }

  private void insertAllocation(
      final PreparedAllocation value,
      final AppUser user,
      final String fingerprint,
      final String reference) {
    final CashAllocationRequest request = value.request();
    jdbcTemplate.update(
        "INSERT INTO m_cash_allocation (idempotency_key,request_fingerprint,receipt_number,"
            + "operation_type,status,business_date,office_id,office_name,initiated_by,initiated_by_username,"
            + "source_cashier_id,source_name,destination_cashier_id,destination_name,destination_key,"
            + "currency_code,amount,source_balance_before,source_balance_after,destination_balance_before,"
            + "destination_balance_after,note) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        request.idempotencyKey(),
        fingerprint,
        reference,
        request.operationType().name(),
        "PROCESSING",
        value.date(),
        value.office().id(),
        value.office().name(),
        user.getId(),
        user.getUsername(),
        value.source() == null ? null : value.source().id(),
        preview(value).source(),
        value.destination() == null ? null : value.destination().id(),
        preview(value).destination(),
        destinationKey(request),
        value.currency(),
        value.total(),
        value.sourceBefore(),
        value.sourceAfter(),
        value.destinationBefore(),
        value.destinationAfter(),
        StringUtils.abbreviate(request.note(), 500));
  }

  private void insertDenominations(
      final Long id, final List<CashAllocationDenominationData> denominations) {
    for (CashAllocationDenominationData item : denominations) {
      jdbcTemplate.update(
          "INSERT INTO m_cash_allocation_denomination (allocation_id,denomination_identifier,"
              + "denomination_type,denomination_value,quantity,line_total) VALUES (?,?,?,?,?,?)",
          id,
          item.identifier(),
          item.type(),
          item.value(),
          item.quantity(),
          item.subtotal());
    }
  }

  private String postVaultOpening(
      final PreparedAllocation value, final String reference, final String idempotencyKey) {
    final Long vaultAccount =
        (Long)
            financialActivityAccounts
                .findByFinancialActivityTypeWithNotFoundDetection(
                    FinancialActivity.CASH_AT_MAINVAULT.getValue())
                .getGlAccount()
                .getId();
    final Long contraAccount =
        (Long)
            financialActivityAccounts
                .findByFinancialActivityTypeWithNotFoundDetection(
                    FinancialActivity.OPENING_BALANCES_TRANSFER_CONTRA.getValue())
                .getGlAccount()
                .getId();
    final Map<String, Object> body = new HashMap<>();
    body.put("officeId", value.office().id());
    body.put("transactionDate", value.date().toString());
    body.put("currencyCode", value.currency());
    body.put("comments", "Safe/vault opening " + reference);
    body.put("locale", "en");
    body.put("dateFormat", "yyyy-MM-dd");
    body.put("debits", List.of(entry(vaultAccount, value.total())));
    body.put("credits", List.of(entry(contraAccount, value.total())));
    final CommandWrapper command =
        new CommandWrapperBuilder()
            .createJournalEntry()
            .withJson(GSON.toJson(body))
            .build(idempotencyKey + ":accounting");
    final CommandProcessingResult result = commandService.logCommandSource(command);
    if (result == null || StringUtils.isBlank(result.getTransactionId())) {
      throw invalid(
          "accounting.reference.missing",
          "Fineract did not return a transaction reference for the vault opening journal entry.");
    }
    return result.getTransactionId();
  }

  private Long requiredCashierTransactionId(final CommandProcessingResult result) {
    if (result == null || result.getSubResourceId() == null) {
      throw invalid(
          "cashier.transaction.reference.missing",
          "Fineract did not return a transaction reference for the cashier cash movement.");
    }
    return result.getSubResourceId();
  }

  private CommandProcessingResult allocateToCashier(
      final CashierSnapshot cashier,
      final BigDecimal amount,
      final String currency,
      final LocalDate date,
      final String reference,
      final String idempotencyKey) {
    return cashierCommand(
        new CommandWrapperBuilder().allocateCashToCashier(cashier.tellerId(), cashier.id()),
        amount,
        currency,
        date,
        "Cash allocation " + reference,
        idempotencyKey);
  }

  private CommandProcessingResult settleFromCashier(
      final CashierSnapshot cashier,
      final BigDecimal amount,
      final String currency,
      final LocalDate date,
      final String reference,
      final String idempotencyKey) {
    return cashierCommand(
        new CommandWrapperBuilder().settleCashFromCashier(cashier.tellerId(), cashier.id()),
        amount,
        currency,
        date,
        "Cash transfer " + reference,
        idempotencyKey);
  }

  private CommandProcessingResult cashierCommand(
      final CommandWrapperBuilder builder,
      final BigDecimal amount,
      final String currency,
      final LocalDate date,
      final String note,
      final String idempotencyKey) {
    final Map<String, Object> body = new HashMap<>();
    body.put("currencyCode", currency);
    body.put("txnAmount", amount);
    body.put("txnNote", note);
    body.put("locale", "en");
    body.put("dateFormat", "yyyy-MM-dd");
    body.put("txnDate", date.toString());
    return commandService.logCommandSource(
        builder.withJson(GSON.toJson(body)).build(idempotencyKey));
  }

  private CashAllocationReceiptData receipt(final Long id, final AppUser user) {
    if (id == null || id <= 0) {
      throw invalid("not.found", "Cash allocation was not found.");
    }
    final List<CashAllocationReceiptData> rows =
        namedJdbcTemplate.query(
            "SELECT a.* FROM m_cash_allocation a JOIN m_office o ON o.id=a.office_id"
                + " WHERE a.id=:id AND o.hierarchy LIKE :hierarchy",
            Map.of("id", id, "hierarchy", user.getOffice().getHierarchy() + "%"),
            (rs, row) ->
                new CashAllocationReceiptData(
                    rs.getLong("id"),
                    rs.getString("receipt_number"),
                    CashAllocationType.valueOf(rs.getString("operation_type")),
                    CashManagementStatus.valueOf(rs.getString("status")),
                    rs.getObject("business_date", LocalDate.class),
                    rs.getLong("office_id"),
                    rs.getString("office_name"),
                    rs.getLong("initiated_by"),
                    rs.getString("initiated_by_username"),
                    nullableLong(rs.getObject("source_cashier_id")),
                    rs.getString("source_name"),
                    nullableLong(rs.getObject("destination_cashier_id")),
                    rs.getString("destination_name"),
                    rs.getString("currency_code"),
                    rs.getBigDecimal("amount"),
                    rs.getBigDecimal("source_balance_before"),
                    rs.getBigDecimal("source_balance_after"),
                    rs.getBigDecimal("destination_balance_before"),
                    rs.getBigDecimal("destination_balance_after"),
                    nullableLong(rs.getObject("source_cashier_transaction_id")),
                    nullableLong(rs.getObject("destination_cashier_transaction_id")),
                    rs.getString("accounting_transaction_id"),
                    rs.getString("note"),
                    offset(rs.getTimestamp("created_on_utc")),
                    offset(rs.getTimestamp("completed_on_utc")),
                    receiptDenominations(rs.getLong("id"))));
    if (rows.isEmpty()) {
      throw new PlatformDataIntegrityException(
          "error.msg.base.teller.cash.allocation.not.found", "Cash allocation was not found.");
    }
    return rows.get(0);
  }

  private List<CashAllocationDenominationData> receiptDenominations(final Long allocationId) {
    return jdbcTemplate.query(
        "SELECT denomination_identifier,denomination_type,denomination_value,quantity,line_total"
            + " FROM m_cash_allocation_denomination WHERE allocation_id=?"
            + " ORDER BY denomination_value DESC,id",
        (rs, row) ->
            new CashAllocationDenominationData(
                rs.getString(1),
                rs.getString(2),
                rs.getBigDecimal(3),
                rs.getLong(4),
                rs.getBigDecimal(5)),
        allocationId);
  }

  private Existing existing(final String idempotencyKey) {
    if (idempotencyKey == null) return null;
    final List<Existing> rows =
        jdbcTemplate.query(
            "SELECT id,request_fingerprint FROM m_cash_allocation WHERE idempotency_key=?",
            (rs, row) -> new Existing(rs.getLong(1), rs.getString(2)),
            idempotencyKey);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Long allocationId(final String idempotencyKey) {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM m_cash_allocation WHERE idempotency_key=?", Long.class, idempotencyKey);
  }

  private void validateFingerprint(final Existing existing, final String fingerprint) {
    if (!existing.fingerprint().equals(fingerprint)) {
      throw invalid(
          "idempotency.conflict", "idempotencyKey was already used for a different request.");
    }
  }

  private static String destinationKey(final CashAllocationRequest request) {
    return request.operationType() == CashAllocationType.SAFE_VAULT_OPENING
        ? "VAULT"
        : "CASHIER:" + request.destinationCashierId();
  }

  private static Map<String, Object> entry(final Long accountId, final BigDecimal amount) {
    return Map.of("glAccountId", accountId, "amount", amount);
  }

  private static Long nullableLong(final Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private static OffsetDateTime offset(final Timestamp value) {
    return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
  }

  private static String shortHash(final String value) {
    return hash(value).substring(0, 12).toUpperCase(Locale.ROOT);
  }

  private static String hash(final String value) {
    try {
      final byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      final StringBuilder result = new StringBuilder();
      for (byte item : bytes) result.append(String.format("%02x", item));
      return result.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static GeneralPlatformDomainRuleException invalid(
      final String code, final String message) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.base.teller.cash.allocation." + code, message);
  }

  private record OfficeSnapshot(Long id, String name) {}

  private record CurrencySnapshot(String code, int decimalPlaces, String name, String symbol) {}

  private record CashierSnapshot(
      Long id, Long tellerId, String tellerName, Long staffId, String cashierName) {
    String displayName() {
      return cashierName + " (" + tellerName + ")";
    }
  }

  private record Existing(Long id, String fingerprint) {}

  private record PreparedAllocation(
      CashAllocationRequest request,
      OfficeSnapshot office,
      String currency,
      LocalDate date,
      BigDecimal total,
      List<CashAllocationDenominationData> denominations,
      CashierSnapshot source,
      CashierSnapshot destination,
      BigDecimal sourceBefore,
      BigDecimal sourceAfter,
      BigDecimal destinationBefore,
      BigDecimal destinationAfter) {}
}
