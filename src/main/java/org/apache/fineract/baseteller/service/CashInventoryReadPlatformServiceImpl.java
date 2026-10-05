package org.apache.fineract.baseteller.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.CashInventoryContextData;
import org.apache.fineract.baseteller.data.CashInventoryCurrencyData;
import org.apache.fineract.baseteller.data.CashInventoryCustodianData;
import org.apache.fineract.baseteller.data.CashInventoryCustodianType;
import org.apache.fineract.baseteller.data.CashInventoryData;
import org.apache.fineract.baseteller.data.CashInventoryTransactionTypeData;
import org.apache.fineract.baseteller.data.CashInventoryType;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CashInventoryReadPlatformServiceImpl implements CashInventoryReadPlatformService {

  private static final String PERMISSION = "READ_BASE_TELLER_CASH_INVENTORY";
  private static final int ALLOCATION = 101;
  private static final int SETTLEMENT = 102;
  private static final int CASH_IN = 103;
  private static final int CASH_OUT = 104;

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final PlatformSecurityContext context;

  @Override
  @Transactional(readOnly = true)
  public CashInventoryContextData context() {
    final AppUser user = authenticatedUser();
    final Scope scope = scope(user, DateUtils.getBusinessLocalDate());
    return new CashInventoryContextData(
        scope.custodians(),
        List.of(
            new CashInventoryTransactionTypeData(CashInventoryType.CASH.name(), "Cash"),
            new CashInventoryTransactionTypeData(CashInventoryType.CHECK.name(), "Check")),
        currencies(null));
  }

  @Override
  @Transactional(readOnly = true)
  public List<CashInventoryData> inventory(
      final String custodianKey,
      final String transactionType,
      final String currencyCode,
      final boolean showLastCutOff) {
    final AppUser user = authenticatedUser();
    final LocalDate businessDate = DateUtils.getBusinessLocalDate();
    final Scope fullScope = scope(user, businessDate);
    final CashInventoryType requestedType = inventoryType(transactionType);
    final String requestedCurrency = normalizedCurrency(currencyCode);
    final Scope requestedScope = fullScope.restrict(custodianKey);
    final Map<String, CashInventoryCurrencyData> currencyMetadata = currencyMap(requestedCurrency);
    final OffsetDateTime asOf = OffsetDateTime.now(ZoneOffset.UTC);
    final Map<InventoryKey, Amounts> amounts = new HashMap<>();

    if (requestedType == null || requestedType == CashInventoryType.CASH) {
      aggregateCash(requestedScope, businessDate, requestedCurrency, amounts);
    }
    if (requestedType == null || requestedType == CashInventoryType.CHECK) {
      aggregateChecks(requestedScope, businessDate, requestedCurrency, amounts);
    }

    final Map<String, CashInventoryCustodianData> custodians = requestedScope.byKey();
    final Map<String, TellerCustodian> tellers = requestedScope.tellersByKey();
    return amounts.entrySet().stream()
        .filter(entry -> custodians.containsKey(entry.getKey().custodianKey()))
        .filter(entry -> currencyMetadata.containsKey(entry.getKey().currency()))
        .map(
            entry ->
                data(
                    custodians.get(entry.getKey().custodianKey()),
                    entry.getKey(),
                    entry.getValue(),
                    tellers.get(entry.getKey().custodianKey()),
                    currencyMetadata.get(entry.getKey().currency()),
                    showLastCutOff,
                    asOf))
        .sorted(
            Comparator.comparing(CashInventoryData::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(value -> value.inventoryType().name())
                .thenComparing(CashInventoryData::currencyCode)
                .thenComparing(CashInventoryData::custodianKey))
        .toList();
  }

  private AppUser authenticatedUser() {
    final AppUser user = context.authenticatedUser();
    user.validateHasPermissionTo(PERMISSION);
    return user;
  }

  private Scope scope(final AppUser user, final LocalDate date) {
    final Map<String, Object> params = new HashMap<>();
    params.put("hierarchy", user.getOffice().getHierarchy() + "%");
    params.put("date", date);
    final boolean hasAssignedCashier =
        user.getStaffId() != null
            && Boolean.TRUE.equals(
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*)>0 FROM m_cashiers c JOIN m_tellers t ON t.id=c.teller_id"
                        + " JOIN m_office o ON o.id=t.office_id WHERE c.staff_id=:staffId"
                        + " AND o.hierarchy LIKE :hierarchy"
                        + " AND (c.start_date IS NULL OR c.start_date<=:date)"
                        + " AND (c.end_date IS NULL OR c.end_date>=:date)",
                    Map.of(
                        "staffId", user.getStaffId(),
                        "hierarchy", user.getOffice().getHierarchy() + "%",
                        "date", date),
                    Boolean.class));
    final boolean assignedCashier =
        hasAssignedCashier && !user.hasAnyPermission("READ_GLOBAL_SETTLEMENT");
    if (assignedCashier) {
      params.put("staffId", user.getStaffId());
    }

    String sql =
        "SELECT c.id cashier_id,c.staff_id,s.display_name,t.office_id,o.name office_name,"
            + "MAX(au.id) user_id,MAX(au.username) username FROM m_cashiers c"
            + " JOIN m_tellers t ON t.id=c.teller_id JOIN m_office o ON o.id=t.office_id"
            + " JOIN m_staff s ON s.id=c.staff_id LEFT JOIN m_appuser au ON au.staff_id=s.id"
            + " WHERE o.hierarchy LIKE :hierarchy AND t.state=300"
            + " AND (t.valid_from IS NULL OR t.valid_from<=:date)"
            + " AND (t.valid_to IS NULL OR t.valid_to>=:date)"
            + " AND (c.start_date IS NULL OR c.start_date<=:date)"
            + " AND (c.end_date IS NULL OR c.end_date>=:date)";
    if (assignedCashier) {
      sql += " AND c.staff_id=:staffId";
    }
    sql +=
        " GROUP BY c.id,c.staff_id,s.display_name,t.office_id,o.name"
            + " ORDER BY s.display_name,c.id";

    final List<TellerCustodian> tellers =
        jdbcTemplate.query(
            sql,
            params,
            (rs, row) ->
                new TellerCustodian(
                    rs.getLong("cashier_id"),
                    rs.getLong("staff_id"),
                    nullableLong(rs.getObject("user_id")),
                    rs.getString("username"),
                    rs.getString("display_name"),
                    rs.getLong("office_id"),
                    rs.getString("office_name")));
    final List<CashInventoryCustodianData> result = new ArrayList<>();
    for (TellerCustodian teller : tellers) {
      result.add(
          new CashInventoryCustodianData(
              teller.key(),
              CashInventoryCustodianType.TELLER,
              teller.cashierId(),
              StringUtils.defaultIfBlank(teller.username(), String.valueOf(teller.staffId())),
              teller.name()));
    }

    final Set<Long> officeIds = new LinkedHashSet<>();
    final Map<Long, String> officeNames = new LinkedHashMap<>();
    for (TellerCustodian teller : tellers) {
      officeIds.add(teller.officeId());
      officeNames.put(teller.officeId(), teller.officeName());
    }
    if (!assignedCashier) {
      officeIds.add(user.getOffice().getId());
      officeNames.put(user.getOffice().getId(), user.getOffice().getName());
      for (Long officeId : officeIds) {
        final String officeName = officeNames.get(officeId);
        result.add(
            new CashInventoryCustodianData(
                vaultKey(officeId),
                CashInventoryCustodianType.VAULT,
                officeId,
                String.valueOf(officeId),
                "Safe/Vault - " + officeName));
        result.add(
            new CashInventoryCustodianData(
                transitKey(officeId),
                CashInventoryCustodianType.TRANSIT,
                officeId,
                String.valueOf(officeId),
                "Transit - " + officeName));
      }
    }
    result.sort(
        Comparator.comparing(CashInventoryCustodianData::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(CashInventoryCustodianData::key));
    return new Scope(result, tellers, List.copyOf(officeIds));
  }

  private void aggregateCash(
      final Scope scope,
      final LocalDate date,
      final String currency,
      final Map<InventoryKey, Amounts> values) {
    if (!scope.tellerIds().isEmpty()) {
      final Map<String, Object> params = filterParams(scope.tellerIds(), date, currency);
      jdbcTemplate.query(
          "SELECT cashier_id,UPPER(currency_code) currency_code,"
              + "COALESCE(SUM(CASE WHEN txn_type=:allocation THEN txn_amount ELSE 0 END),0) initial_balance,"
              + "COALESCE(SUM(CASE WHEN txn_type=:cashIn THEN txn_amount ELSE 0 END),0) inflows,"
              + "COALESCE(SUM(CASE WHEN txn_type=:cashOut OR (txn_type=:settlement AND"
              + " (EXISTS (SELECT 1 FROM m_cash_allocation a WHERE a.source_cashier_transaction_id=ct.id)"
              + " OR EXISTS (SELECT 1 FROM m_cash_operation_transaction op"
              + " WHERE op.cashier_transaction_id=ct.id))) THEN txn_amount ELSE 0 END),0) outflows,"
              + "COALESCE(SUM(CASE WHEN txn_type=:settlement AND"
              + " NOT EXISTS (SELECT 1 FROM m_cash_allocation a WHERE a.source_cashier_transaction_id=ct.id)"
              + " AND NOT EXISTS (SELECT 1 FROM m_cash_operation_transaction op"
              + " WHERE op.cashier_transaction_id=ct.id) THEN txn_amount ELSE 0 END),0) cutoffs,"
              + "MAX(CASE WHEN txn_type=:settlement AND"
              + " NOT EXISTS (SELECT 1 FROM m_cash_allocation a WHERE a.source_cashier_transaction_id=ct.id)"
              + " AND NOT EXISTS (SELECT 1 FROM m_cash_operation_transaction op"
              + " WHERE op.cashier_transaction_id=ct.id) THEN created_date END) last_cutoff_at"
              + " FROM m_cashier_transactions ct WHERE cashier_id IN (:cashierIds) AND txn_date=:date"
              + currencyClause(currency)
              + " GROUP BY cashier_id,UPPER(currency_code)",
          transactionParams(params),
          rs -> {
            final Amounts amount =
                amount(
                    values,
                    tellerKey(rs.getLong("cashier_id")),
                    CashInventoryType.CASH,
                    rs.getString("currency_code"));
            amount.initial = amount.initial.add(rs.getBigDecimal("initial_balance"));
            amount.inflows = amount.inflows.add(rs.getBigDecimal("inflows"));
            amount.outflows = amount.outflows.add(rs.getBigDecimal("outflows"));
            amount.cutOffs = amount.cutOffs.add(rs.getBigDecimal("cutoffs"));
            amount.lastCutOffAt = offset(rs.getTimestamp("last_cutoff_at"));
          });

      jdbcTemplate.query(
          "SELECT cashier_id,UPPER(currency_code) currency_code,SUM(amount) amount FROM ("
              + "SELECT d.cashier_id,d.currency_code,d.amount FROM m_base_teller_deposit d"
              + " JOIN m_savings_account_transaction st ON st.id=d.savings_transaction_id"
              + " WHERE d.cashier_id IN (:cashierIds) AND d.funding_type='CASH'"
              + " AND d.status='COMPLETED' AND st.transaction_date=:date"
              + " AND NOT EXISTS (SELECT 1 FROM m_cashier_transactions ct"
              + " WHERE ct.entity_type='BASE_TELLER_DEPOSIT' AND ct.entity_id=d.id)"
              + currencyClauseFor("d", currency)
              + " UNION ALL SELECT o.cashier_id,o.currency_code,o.amount"
              + " FROM m_base_teller_savings_opening o"
              + " JOIN m_savings_account_transaction st ON st.id=o.initial_deposit_transaction_id"
              + " WHERE o.cashier_id IN (:cashierIds) AND o.funding_type='CASH'"
              + " AND o.status='COMPLETED' AND st.transaction_date=:date"
              + " AND NOT EXISTS (SELECT 1 FROM m_cashier_transactions ct"
              + " WHERE ct.entity_type='BASE_TELLER_SAVINGS_OPENING' AND ct.entity_id=o.id)"
              + currencyClauseFor("o", currency)
              + ") inventory_cash GROUP BY cashier_id,UPPER(currency_code)",
          params,
          rs -> {
            final Amounts amount =
                amount(
                    values,
                    tellerKey(rs.getLong("cashier_id")),
                    CashInventoryType.CASH,
                    rs.getString("currency_code"));
            amount.inflows = amount.inflows.add(rs.getBigDecimal("amount"));
          });
      latestCashCutOffs(scope, date, currency, values);
    }
    aggregateVaultAndTransitCash(scope, date, currency, values);
  }

  private void aggregateVaultAndTransitCash(
      final Scope scope,
      final LocalDate date,
      final String currency,
      final Map<InventoryKey, Amounts> values) {
    if (scope.officeIds().isEmpty()) {
      return;
    }
    final Map<String, Object> params = officeFilterParams(scope.officeIds(), date, currency);
    jdbcTemplate.query(
        "SELECT office_id,UPPER(currency_code) currency_code,"
            + "SUM(CASE WHEN operation_type='SAFE_VAULT_OPENING' THEN amount ELSE 0 END) initial_balance,"
            + "SUM(CASE WHEN operation_type='HEAD_CASHIER_ALLOCATION' THEN amount ELSE 0 END) outflows"
            + " FROM m_cash_allocation WHERE office_id IN (:officeIds) AND business_date=:date"
            + " AND status='COMPLETED'"
            + currencyClause(currency)
            + " GROUP BY office_id,UPPER(currency_code)",
        params,
        rs -> {
          final Amounts amount =
              amount(
                  values,
                  vaultKey(rs.getLong("office_id")),
                  CashInventoryType.CASH,
                  rs.getString("currency_code"));
          amount.initial = amount.initial.add(rs.getBigDecimal("initial_balance"));
          amount.outflows = amount.outflows.add(rs.getBigDecimal("outflows"));
        });
    jdbcTemplate.query(
        "SELECT office_id,UPPER(currency_code) currency_code,SUM(cash_total) amount"
            + " FROM m_cashier_reconciliation WHERE office_id IN (:officeIds)"
            + " AND business_date=:date AND status='COMPLETED'"
            + currencyClause(currency)
            + " GROUP BY office_id,UPPER(currency_code)",
        params,
        rs -> {
          add(
              values,
              vaultKey(rs.getLong("office_id")),
              CashInventoryType.CASH,
              rs.getString("currency_code"),
              Category.INFLOW,
              rs.getBigDecimal("amount"));
        });
    jdbcTemplate.query(
        "SELECT office_id,UPPER(currency_code) currency_code,"
            + "SUM(CASE WHEN business_date<:date THEN cash_total ELSE 0 END) initial_balance,"
            + "SUM(CASE WHEN business_date=:date THEN cash_total ELSE 0 END) inflows"
            + " FROM m_cash_operation_transaction WHERE office_id IN (:officeIds)"
            + " AND business_date<=:date AND transaction_type='DEPOSIT_IN_TRANSIT'"
            + " AND status='COMPLETED'"
            + currencyClause(currency)
            + " GROUP BY office_id,UPPER(currency_code)",
        params,
        rs -> {
          final Amounts amount =
              amount(
                  values,
                  transitKey(rs.getLong("office_id")),
                  CashInventoryType.CASH,
                  rs.getString("currency_code"));
          amount.initial = amount.initial.add(rs.getBigDecimal("initial_balance"));
          amount.inflows = amount.inflows.add(rs.getBigDecimal("inflows"));
        });
  }

  private void aggregateChecks(
      final Scope scope,
      final LocalDate date,
      final String currency,
      final Map<InventoryKey, Amounts> values) {
    if (!scope.tellerIds().isEmpty()) {
      final Map<String, Object> params = filterParams(scope.tellerIds(), date, currency);
      aggregateAcceptedChecks(params, date, currency, values);
      aggregateRemovedChecks(params, date, currency, values);
    }
    aggregateVaultAndTransitChecks(scope, date, currency, values);
  }

  private void aggregateAcceptedChecks(
      final Map<String, Object> params,
      final LocalDate date,
      final String currency,
      final Map<InventoryKey, Amounts> values) {
    jdbcTemplate.query(
        "SELECT cashier_id,UPPER(currency_code) currency_code,"
            + "SUM(CASE WHEN accepted_date<:date THEN amount ELSE 0 END) initial_balance,"
            + "SUM(CASE WHEN accepted_date=:date THEN amount ELSE 0 END) inflows FROM ("
            + "SELECT d.cashier_id,d.currency_code,cd.amount,CAST(d.created_on_utc AS date) accepted_date"
            + " FROM m_base_teller_deposit_check_detail cd JOIN m_base_teller_deposit d ON d.id=cd.deposit_id"
            + " WHERE d.cashier_id IN (:cashierIds) AND d.status IN ('PENDING_COLLECTION','COMPLETED')"
            + currencyClauseFor("d", currency)
            + " UNION ALL SELECT o.cashier_id,o.currency_code,o.amount,CAST(o.created_on_utc AS date)"
            + " FROM m_base_teller_savings_opening o WHERE o.cashier_id IN (:cashierIds)"
            + " AND o.funding_type='CHECK' AND o.status='COMPLETED'"
            + currencyClauseFor("o", currency)
            + " UNION ALL SELECT p.cashier_id,p.currency_code,p.amount,CAST(c.accepted_on_utc AS date)"
            + " FROM m_base_teller_credit_payment_check c"
            + " JOIN m_base_teller_credit_payment p ON p.id=c.credit_payment_id"
            + " WHERE p.cashier_id IN (:cashierIds) AND c.status IN ('PENDING_COLLECTION','CLEARED','RETURNED')"
            + currencyClauseFor("p", currency)
            + ") accepted WHERE accepted_date<=:date GROUP BY cashier_id,UPPER(currency_code)",
        params,
        rs -> {
          final Amounts amount =
              amount(
                  values,
                  tellerKey(rs.getLong("cashier_id")),
                  CashInventoryType.CHECK,
                  rs.getString("currency_code"));
          amount.initial = amount.initial.add(rs.getBigDecimal("initial_balance"));
          amount.inflows = amount.inflows.add(rs.getBigDecimal("inflows"));
        });
  }

  private void aggregateRemovedChecks(
      final Map<String, Object> params,
      final LocalDate date,
      final String currency,
      final Map<InventoryKey, Amounts> values) {
    final String removals =
        "SELECT d.cashier_id,d.currency_code,oc.amount,o.business_date removed_date,'OUTFLOW' category,o.completed_on_utc removed_at"
            + " FROM m_cash_operation_check oc JOIN m_cash_operation_transaction o ON o.id=oc.operation_id"
            + " JOIN m_base_teller_deposit_check_detail cd ON cd.id=oc.deposit_check_detail_id"
            + " JOIN m_base_teller_deposit d ON d.id=cd.deposit_id"
            + " WHERE d.cashier_id IN (:cashierIds) AND o.status='COMPLETED'"
            + currencyClauseFor("d", currency)
            + " UNION ALL SELECT d.cashier_id,d.currency_code,rc.amount,r.business_date,'CUTOFF',r.completed_on_utc"
            + " FROM m_cashier_reconciliation_check rc JOIN m_cashier_reconciliation r ON r.id=rc.reconciliation_id"
            + " JOIN m_base_teller_deposit_check_detail cd ON cd.id=rc.deposit_check_detail_id"
            + " JOIN m_base_teller_deposit d ON d.id=cd.deposit_id"
            + " WHERE d.cashier_id IN (:cashierIds) AND r.status='COMPLETED'"
            + currencyClauseFor("d", currency)
            + " UNION ALL SELECT rc.cashier_id,rc.currency_code,rc.amount,rc.returned_on_date,'OUTFLOW',NULL"
            + " FROM m_base_teller_returned_check rc WHERE rc.cashier_id IN (:cashierIds)"
            + " AND rc.deposit_check_detail_id IS NOT NULL AND rc.status IN ('RETURNED','SETTLED')"
            + currencyClauseFor("rc", currency)
            + " UNION ALL SELECT p.cashier_id,p.currency_code,p.amount,CAST(c.returned_on_utc AS date),'OUTFLOW',c.returned_on_utc"
            + " FROM m_base_teller_credit_payment_check c JOIN m_base_teller_credit_payment p ON p.id=c.credit_payment_id"
            + " WHERE p.cashier_id IN (:cashierIds) AND c.status='RETURNED'"
            + currencyClauseFor("p", currency);
    jdbcTemplate.query(
        "SELECT cashier_id,UPPER(currency_code) currency_code,category,"
            + "SUM(CASE WHEN removed_date<:date THEN amount ELSE 0 END) prior_amount,"
            + "SUM(CASE WHEN removed_date=:date THEN amount ELSE 0 END) current_amount,"
            + "MAX(CASE WHEN removed_date=:date AND category='CUTOFF' THEN removed_at END) last_cutoff_at"
            + " FROM ("
            + removals
            + ") removed WHERE removed_date<=:date"
            + " GROUP BY cashier_id,UPPER(currency_code),category",
        params,
        rs -> {
          final Amounts amount =
              amount(
                  values,
                  tellerKey(rs.getLong("cashier_id")),
                  CashInventoryType.CHECK,
                  rs.getString("currency_code"));
          amount.initial = amount.initial.subtract(rs.getBigDecimal("prior_amount"));
          if ("CUTOFF".equals(rs.getString("category"))) {
            amount.cutOffs = amount.cutOffs.add(rs.getBigDecimal("current_amount"));
            amount.lastCutOffAt = offset(rs.getTimestamp("last_cutoff_at"));
            amount.lastCutOffAmount = rs.getBigDecimal("current_amount");
          } else {
            amount.outflows = amount.outflows.add(rs.getBigDecimal("current_amount"));
          }
        });
  }

  private void aggregateVaultAndTransitChecks(
      final Scope scope,
      final LocalDate date,
      final String currency,
      final Map<InventoryKey, Amounts> values) {
    if (scope.officeIds().isEmpty()) {
      return;
    }
    final Map<String, Object> params = officeFilterParams(scope.officeIds(), date, currency);
    jdbcTemplate.query(
        "SELECT r.office_id,UPPER(r.currency_code) currency_code,"
            + "SUM(CASE WHEN r.business_date<:date THEN rc.amount ELSE 0 END) initial_balance,"
            + "SUM(CASE WHEN r.business_date=:date THEN rc.amount ELSE 0 END) inflows"
            + " FROM m_cashier_reconciliation_check rc JOIN m_cashier_reconciliation r ON r.id=rc.reconciliation_id"
            + " WHERE r.office_id IN (:officeIds) AND r.business_date<=:date AND r.status='COMPLETED'"
            + currencyClauseFor("r", currency)
            + " GROUP BY r.office_id,UPPER(r.currency_code)",
        params,
        rs -> {
          final Amounts amount =
              amount(
                  values,
                  vaultKey(rs.getLong("office_id")),
                  CashInventoryType.CHECK,
                  rs.getString("currency_code"));
          amount.initial = amount.initial.add(rs.getBigDecimal("initial_balance"));
          amount.inflows = amount.inflows.add(rs.getBigDecimal("inflows"));
        });
    jdbcTemplate.query(
        "SELECT o.office_id,UPPER(o.currency_code) currency_code,"
            + "SUM(CASE WHEN o.business_date<:date THEN oc.amount ELSE 0 END) initial_balance,"
            + "SUM(CASE WHEN o.business_date=:date THEN oc.amount ELSE 0 END) inflows"
            + " FROM m_cash_operation_check oc JOIN m_cash_operation_transaction o ON o.id=oc.operation_id"
            + " WHERE o.office_id IN (:officeIds) AND o.business_date<=:date"
            + " AND o.transaction_type='DEPOSIT_IN_TRANSIT' AND o.status='COMPLETED'"
            + currencyClauseFor("o", currency)
            + " GROUP BY o.office_id,UPPER(o.currency_code)",
        params,
        rs -> {
          final Amounts amount =
              amount(
                  values,
                  transitKey(rs.getLong("office_id")),
                  CashInventoryType.CHECK,
                  rs.getString("currency_code"));
          amount.initial = amount.initial.add(rs.getBigDecimal("initial_balance"));
          amount.inflows = amount.inflows.add(rs.getBigDecimal("inflows"));
        });
  }

  private void latestCashCutOffs(
      final Scope scope,
      final LocalDate date,
      final String currency,
      final Map<InventoryKey, Amounts> values) {
    final Map<String, Object> params = filterParams(scope.tellerIds(), date, currency);
    params.put("settlement", SETTLEMENT);
    jdbcTemplate.query(
        "SELECT ct.cashier_id,UPPER(ct.currency_code) currency_code,ct.txn_amount"
            + " FROM m_cashier_transactions ct WHERE ct.cashier_id IN (:cashierIds)"
            + " AND ct.txn_date=:date AND ct.txn_type=:settlement"
            + currencyClauseFor("ct", currency)
            + " AND NOT EXISTS (SELECT 1 FROM m_cash_allocation a"
            + " WHERE a.source_cashier_transaction_id=ct.id)"
            + " AND NOT EXISTS (SELECT 1 FROM m_cash_operation_transaction op"
            + " WHERE op.cashier_transaction_id=ct.id)"
            + " AND NOT EXISTS (SELECT 1 FROM m_cashier_transactions newer"
            + " WHERE newer.cashier_id=ct.cashier_id AND newer.txn_date=ct.txn_date"
            + " AND newer.txn_type=ct.txn_type AND UPPER(newer.currency_code)=UPPER(ct.currency_code)"
            + " AND newer.id>ct.id)",
        params,
        rs -> {
          amount(
                  values,
                  tellerKey(rs.getLong("cashier_id")),
                  CashInventoryType.CASH,
                  rs.getString("currency_code"))
              .lastCutOffAmount = rs.getBigDecimal("txn_amount");
        });
  }

  private CashInventoryData data(
      final CashInventoryCustodianData custodian,
      final InventoryKey key,
      final Amounts amount,
      final TellerCustodian teller,
      final CashInventoryCurrencyData currency,
      final boolean showLastCutOff,
      final OffsetDateTime asOf) {
    final BigDecimal initial = scale(amount.initial, currency.decimalPlaces());
    final BigDecimal inflows = scale(amount.inflows, currency.decimalPlaces());
    final BigDecimal outflows = scale(amount.outflows, currency.decimalPlaces());
    final BigDecimal cutOffs = scale(amount.cutOffs, currency.decimalPlaces());
    return new CashInventoryData(
        custodian.key(),
        custodian.type(),
        custodian.resourceId(),
        teller == null ? null : teller.userId(),
        teller == null
            ? custodian.code()
            : StringUtils.defaultIfBlank(teller.username(), String.valueOf(teller.staffId())),
        custodian.name(),
        key.type(),
        currency.code(),
        currency.decimalPlaces(),
        initial,
        inflows,
        outflows,
        cutOffs,
        CashInventoryBalance.calculate(initial, inflows, outflows, cutOffs, currency.decimalPlaces()),
        showLastCutOff ? amount.lastCutOffAt : null,
        showLastCutOff ? scaleNullable(amount.lastCutOffAmount, currency.decimalPlaces()) : null,
        asOf);
  }

  private List<CashInventoryCurrencyData> currencies(final String requested) {
    return new ArrayList<>(currencyMap(requested).values());
  }

  private Map<String, CashInventoryCurrencyData> currencyMap(final String requested) {
    final Map<String, Object> params = new HashMap<>();
    String sql = "SELECT UPPER(code) code,name,decimal_places FROM m_organisation_currency";
    if (requested != null) {
      sql += " WHERE UPPER(code)=:currency";
      params.put("currency", requested);
    }
    sql += " ORDER BY code";
    final Map<String, CashInventoryCurrencyData> result = new LinkedHashMap<>();
    jdbcTemplate
        .query(
            sql,
            params,
            (rs, row) ->
                new CashInventoryCurrencyData(
                    rs.getString("code"), rs.getString("name"), rs.getInt("decimal_places")))
        .forEach(value -> result.put(value.code(), value));
    if (requested != null && result.isEmpty()) {
      throw invalid("currency.unsupported", "Currency is not enabled for the Fineract tenant.");
    }
    return result;
  }

  private CashInventoryType inventoryType(final String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    try {
      return CashInventoryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw invalid("transaction.type.invalid", "transactionType must be CASH or CHECK.");
    }
  }

  private String normalizedCurrency(final String value) {
    return StringUtils.isBlank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private Map<String, Object> filterParams(
      final List<Long> cashierIds, final LocalDate date, final String currency) {
    final Map<String, Object> params = new HashMap<>();
    params.put("cashierIds", cashierIds);
    params.put("date", date);
    if (currency != null) {
      params.put("currency", currency);
    }
    return params;
  }

  private Map<String, Object> officeFilterParams(
      final List<Long> officeIds, final LocalDate date, final String currency) {
    final Map<String, Object> params = new HashMap<>();
    params.put("officeIds", officeIds);
    params.put("date", date);
    if (currency != null) {
      params.put("currency", currency);
    }
    return params;
  }

  private Map<String, Object> transactionParams(final Map<String, Object> source) {
    final Map<String, Object> result = new HashMap<>(source);
    result.put("allocation", ALLOCATION);
    result.put("settlement", SETTLEMENT);
    result.put("cashIn", CASH_IN);
    result.put("cashOut", CASH_OUT);
    return result;
  }

  private String currencyClause(final String currency) {
    return currency == null ? "" : " AND UPPER(currency_code)=:currency";
  }

  private String currencyClauseFor(final String alias, final String currency) {
    return currency == null ? "" : " AND UPPER(" + alias + ".currency_code)=:currency";
  }

  private void add(
      final Map<InventoryKey, Amounts> values,
      final String custodian,
      final CashInventoryType type,
      final String currency,
      final Category category,
      final BigDecimal value) {
    final Amounts amount = amount(values, custodian, type, currency);
    switch (category) {
      case INITIAL -> amount.initial = amount.initial.add(value);
      case INFLOW -> amount.inflows = amount.inflows.add(value);
      case OUTFLOW -> amount.outflows = amount.outflows.add(value);
      case CUTOFF -> amount.cutOffs = amount.cutOffs.add(value);
    }
  }

  private Amounts amount(
      final Map<InventoryKey, Amounts> values,
      final String custodian,
      final CashInventoryType type,
      final String currency) {
    return values.computeIfAbsent(
        new InventoryKey(custodian, type, currency.toUpperCase(Locale.ROOT)), ignored -> new Amounts());
  }

  private static BigDecimal scale(final BigDecimal value, final int decimalPlaces) {
    return (value == null ? BigDecimal.ZERO : value).setScale(decimalPlaces);
  }

  private static BigDecimal scaleNullable(final BigDecimal value, final int decimalPlaces) {
    return value == null ? null : value.setScale(decimalPlaces);
  }

  private static OffsetDateTime offset(final Timestamp value) {
    return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
  }

  private static Long nullableLong(final Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private static String tellerKey(final Long id) {
    return CashInventoryCustodianType.TELLER.name() + ":" + id;
  }

  private static String vaultKey(final Long id) {
    return CashInventoryCustodianType.VAULT.name() + ":" + id;
  }

  private static String transitKey(final Long id) {
    return CashInventoryCustodianType.TRANSIT.name() + ":" + id;
  }

  private static GeneralPlatformDomainRuleException invalid(
      final String code, final String message) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.base.teller.cash.inventory." + code, message);
  }

  private enum Category {
    INITIAL,
    INFLOW,
    OUTFLOW,
    CUTOFF
  }

  private record InventoryKey(String custodianKey, CashInventoryType type, String currency) {}

  private static final class Amounts {
    private BigDecimal initial = BigDecimal.ZERO;
    private BigDecimal inflows = BigDecimal.ZERO;
    private BigDecimal outflows = BigDecimal.ZERO;
    private BigDecimal cutOffs = BigDecimal.ZERO;
    private OffsetDateTime lastCutOffAt;
    private BigDecimal lastCutOffAmount;
  }

  private record TellerCustodian(
      Long cashierId,
      Long staffId,
      Long userId,
      String username,
      String name,
      Long officeId,
      String officeName) {
    String key() {
      return tellerKey(cashierId);
    }
  }

  private record Scope(
      List<CashInventoryCustodianData> custodians,
      List<TellerCustodian> tellers,
      List<Long> officeIds) {
    List<Long> tellerIds() {
      return tellers.stream().map(TellerCustodian::cashierId).toList();
    }

    Map<String, CashInventoryCustodianData> byKey() {
      final Map<String, CashInventoryCustodianData> result = new HashMap<>();
      custodians.forEach(value -> result.put(value.key(), value));
      return result;
    }

    Map<String, TellerCustodian> tellersByKey() {
      final Map<String, TellerCustodian> result = new HashMap<>();
      tellers.forEach(value -> result.put(value.key(), value));
      return result;
    }

    Scope restrict(final String requestedKey) {
      if (StringUtils.isBlank(requestedKey)) {
        return this;
      }
      final String normalized = requestedKey.trim().toUpperCase(Locale.ROOT);
      final CashInventoryCustodianData custodian = byKey().get(normalized);
      if (custodian == null) {
        throw invalid(
            "custodian.forbidden",
            "Custodian does not exist or is outside the authenticated user's authorized scope.");
      }
      final List<TellerCustodian> restrictedTellers =
          tellers.stream().filter(value -> value.key().equals(normalized)).toList();
      final List<Long> restrictedOffices =
          custodian.type() == CashInventoryCustodianType.TELLER
              ? List.of()
              : List.of(custodian.resourceId());
      return new Scope(List.of(custodian), restrictedTellers, restrictedOffices);
    }
  }
}
