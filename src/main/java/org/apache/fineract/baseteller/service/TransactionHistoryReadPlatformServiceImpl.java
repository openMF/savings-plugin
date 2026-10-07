package org.apache.fineract.baseteller.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.TransactionHistoryCancellationData;
import org.apache.fineract.baseteller.data.TransactionHistoryClientData;
import org.apache.fineract.baseteller.data.TransactionHistoryContextData;
import org.apache.fineract.baseteller.data.TransactionHistoryCurrencyData;
import org.apache.fineract.baseteller.data.TransactionHistoryDenominationLineData;
import org.apache.fineract.baseteller.data.TransactionHistoryDenominationsData;
import org.apache.fineract.baseteller.data.TransactionHistoryDetailData;
import org.apache.fineract.baseteller.data.TransactionHistoryItemData;
import org.apache.fineract.baseteller.data.TransactionHistoryOptionData;
import org.apache.fineract.baseteller.data.TransactionHistoryQuery;
import org.apache.fineract.baseteller.data.TransactionHistoryReceiptData;
import org.apache.fineract.baseteller.data.TransactionHistoryReportData;
import org.apache.fineract.baseteller.data.TransactionHistorySearchData;
import org.apache.fineract.baseteller.data.TransactionHistoryTellerData;
import org.apache.fineract.baseteller.data.TransactionHistoryTotalsData;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionHistoryReadPlatformServiceImpl
    implements TransactionHistoryReadPlatformService {

  private static final String PERMISSION = "READ_BASE_TELLER_TRANSACTION_HISTORY";
  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 500;
  private static final int MAX_REFERENCE_LENGTH = 200;
  private static final Set<String> SORT_FIELDS =
      Set.of("transactionDate", "operation", "inflow", "outflow", "currencyCode", "concept", "status", "reference");
  private static final Map<String, String> SORT_COLUMNS =
      Map.of(
          "transactionDate", "h.transaction_date",
          "operation", "h.operation",
          "inflow", "effective_inflow",
          "outflow", "effective_outflow",
          "currencyCode", "h.currency_code",
          "concept", "h.concept",
          "status", "h.status",
          "reference", "h.reference");

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final PlatformSecurityContext securityContext;
  private final BaseTellerReadPlatformService baseTellerReadPlatformService;
  private final CashAllocationReadPlatformService cashAllocationReadPlatformService;
  private final ServicePaymentReadPlatformService servicePaymentReadPlatformService;
  private final CreditPaymentReadPlatformService creditPaymentReadPlatformService;
  private final CashManagementReadPlatformService cashManagementReadPlatformService;

  @Override
  public TransactionHistoryContextData context() {
    final Scope scope = scope();
    final Map<String, Object> params = baseParams(scope);
    final String authorized = authorizedWhere(scope, params);
    return new TransactionHistoryContextData(
        tellers(scope),
        currencies(),
        options("status", authorized, params),
        options("transaction_type", authorized, params),
        options("operation", authorized, params),
        options("concept", authorized, params));
  }

  @Override
  public TransactionHistorySearchData search(final TransactionHistoryQuery query) {
    final Scope scope = scope();
    final ResolvedQuery resolved = validate(query, scope, true);
    return search(resolved, false);
  }

  @Override
  public TransactionHistoryReportData report(final TransactionHistoryQuery query) {
    final Scope scope = scope();
    final ResolvedQuery resolved = validate(query, scope, false);
    final TransactionHistorySearchData result = search(resolved, true);
    return new TransactionHistoryReportData(
        OffsetDateTime.now(ZoneOffset.UTC),
        result.items(),
        result.totalFilteredRecords(),
        result.totalsByCurrency());
  }

  @Override
  public TransactionHistoryDetailData detail(final String historyId) {
    final HistoryKey key = historyKey(historyId);
    final Scope scope = scope();
    final Map<String, Object> params = baseParams(scope);
    params.put("sourceType", key.sourceType());
    params.put("sourceId", key.sourceId());
    final String sql =
        historyCte()
            + " SELECT h.*,oc.decimal_places,c.display_name client_name,CAST(c.external_id AS varchar) identification,"
            + " t.name teller_name,s.display_name cashier_name,o.name office_name,"
            + " COALESCE(au.username,CAST(h.cashier_id AS varchar)) cashier_code FROM history h"
            + " JOIN m_office o ON o.id=h.office_id"
            + " LEFT JOIN m_organisation_currency oc ON UPPER(oc.code)=h.currency_code"
            + " LEFT JOIN m_client c ON c.id=h.client_id"
            + " LEFT JOIN m_tellers t ON t.id=h.teller_id"
            + " LEFT JOIN m_cashiers ca ON ca.id=h.cashier_id"
            + " LEFT JOIN m_staff s ON s.id=ca.staff_id"
            + " LEFT JOIN m_appuser au ON au.staff_id=s.id"
            + " WHERE h.source_type=:sourceType AND h.source_id=:sourceId"
            + " AND o.hierarchy LIKE :hierarchy"
            + authorizedClause(scope, params);
    final List<TransactionHistoryDetailData> rows =
        jdbcTemplate.query(sql, params, (rs, row) -> mapDetail(rs, historyId));
    if (rows.isEmpty()) {
      throw notFound();
    }
    return rows.get(0);
  }

  @Override
  public TransactionHistoryDenominationsData denominations(final String historyId) {
    final HistoryKey key = historyKey(historyId);
    detail(historyId);
    final DenominationSource source = denominationSource(key.sourceType());
    if (source == null) {
      return new TransactionHistoryDenominationsData(historyId, false, List.of(), false, List.of());
    }
    final Map<String, Object> params = Map.of("sourceId", key.sourceId());
    final List<TransactionHistoryDenominationLineData> lines =
        jdbcTemplate.query(
            "SELECT h.currency_code,d.denomination_identifier,d.denomination_value,d.quantity,"
                + source.amountExpression()
                + " amount,"
                + source.typeExpression()
                + " denomination_type FROM "
                + source.detailTable()
                + " d JOIN "
                + source.headerTable()
                + " h ON h.id=d."
                + source.foreignKey()
                + " WHERE h.id=:sourceId AND d.quantity>0"
                + " ORDER BY d.denomination_value DESC,d.denomination_identifier",
            params,
            (rs, row) ->
                new TransactionHistoryDenominationLineData(
                    rs.getString("currency_code"),
                    rs.getString("denomination_identifier"),
                    rs.getBigDecimal("denomination_value"),
                    rs.getLong("quantity"),
                    rs.getBigDecimal("amount"),
                    rs.getString("denomination_type")));
    return new TransactionHistoryDenominationsData(historyId, true, lines, false, List.of());
  }

  @Override
  public TransactionHistoryReceiptData receipt(final String historyId) {
    final HistoryKey key = historyKey(historyId);
    final TransactionHistoryDetailData detail = detail(historyId);
    if (!detail.receiptSupported()) {
      throw invalid("receipt.unsupported", "The original transaction source does not support a receipt.");
    }
    final Object receipt =
        switch (key.sourceType()) {
          case "SAVINGS_DEPOSIT" ->
              baseTellerReadPlatformService.retrieveDepositReceipt(detail.reference());
          case "SAVINGS_OPENING" ->
              baseTellerReadPlatformService.retrieveOpeningReceipt(detail.reference());
          case "RETURNED_CHECK_PAYMENT" ->
              baseTellerReadPlatformService.retrieveReturnedCheckReceipt(detail.reference());
          case "SERVICE_PAYMENT" -> servicePaymentReadPlatformService.receipt(key.sourceId());
          case "CREDIT_PAYMENT" -> creditPaymentReadPlatformService.receipt(detail.reference());
          case "CASH_ALLOCATION" -> cashAllocationReadPlatformService.reprint(key.sourceId());
          case "CASHIER_CLOSING" -> cashManagementReadPlatformService.closing(key.sourceId());
          default -> throw invalid("receipt.unsupported", "The original transaction source does not support a receipt.");
        };
    return new TransactionHistoryReceiptData(historyId, key.sourceType(), key.sourceId(), receipt);
  }

  private TransactionHistorySearchData search(final ResolvedQuery query, final boolean allRows) {
    final Map<String, Object> params = baseParams(query.scope());
    final String where = filteredWhere(query, params);
    final Long count =
        jdbcTemplate.queryForObject(
            historyCte() + " SELECT COUNT(*) FROM history h JOIN m_office o ON o.id=h.office_id" + where,
            params,
            Long.class);
    final List<TransactionHistoryTotalsData> totals =
        jdbcTemplate.query(
            historyCte()
                + " SELECT h.currency_code,oc.decimal_places,"
                + " COALESCE(SUM("
                + effectiveInflow(query.tellerId())
                + "),0) total_inflows,COALESCE(SUM("
                + effectiveOutflow(query.tellerId())
                + "),0) total_outflows FROM history h"
                + " JOIN m_office o ON o.id=h.office_id"
                + " LEFT JOIN m_organisation_currency oc ON UPPER(oc.code)=h.currency_code"
                + where
                + " GROUP BY h.currency_code,oc.decimal_places ORDER BY h.currency_code",
            params,
            (rs, row) -> {
              final BigDecimal inflows = rs.getBigDecimal("total_inflows");
              final BigDecimal outflows = rs.getBigDecimal("total_outflows");
              return new TransactionHistoryTotalsData(
                  rs.getString("currency_code"),
                  nullableInteger(rs, "decimal_places"),
                  inflows,
                  outflows,
                  inflows.subtract(outflows));
            });
    final String orderBy = orderBy(query);
    String itemSql =
        historyCte()
            + " SELECT h.*,oc.decimal_places,"
            + effectiveInflow(query.tellerId())
            + " effective_inflow,"
            + effectiveOutflow(query.tellerId())
            + " effective_outflow FROM history h"
            + " JOIN m_office o ON o.id=h.office_id"
            + " LEFT JOIN m_organisation_currency oc ON UPPER(oc.code)=h.currency_code"
            + where
            + orderBy;
    if (!allRows) {
      itemSql += " LIMIT :limit OFFSET :offset";
      params.put("limit", query.limit());
      params.put("offset", query.offset());
    }
    final List<TransactionHistoryItemData> items =
        jdbcTemplate.query(itemSql, params, this::mapItem);
    return new TransactionHistorySearchData(
        items,
        count == null ? 0 : count,
        allRows ? 0 : query.offset(),
        allRows ? items.size() : query.limit(),
        totals);
  }

  private TransactionHistoryItemData mapItem(final ResultSet rs, final int row)
      throws SQLException {
    return new TransactionHistoryItemData(
        historyId(rs.getString("source_type"), rs.getLong("source_id")),
        offset(rs.getTimestamp("transaction_date")),
        rs.getString("operation"),
        rs.getBigDecimal("effective_inflow"),
        rs.getBigDecimal("effective_outflow"),
        rs.getString("currency_code"),
        nullableInteger(rs, "decimal_places"),
        rs.getString("concept"),
        rs.getString("status"),
        rs.getString("reference"),
        nullableLong(rs, "cashier_id"),
        nullableLong(rs, "client_id"));
  }

  private TransactionHistoryDetailData mapDetail(final ResultSet rs, final String historyId)
      throws SQLException {
    final Long clientId = nullableLong(rs, "client_id");
    final Long cashierId = nullableLong(rs, "cashier_id");
    final TransactionHistoryClientData client =
        clientId == null
            ? null
            : new TransactionHistoryClientData(
                clientId, rs.getString("client_name"), rs.getString("identification"));
    final TransactionHistoryTellerData teller =
        cashierId == null
            ? null
            : new TransactionHistoryTellerData(
                cashierId,
                nullableLong(rs, "teller_id"),
                rs.getString("cashier_code"),
                rs.getString("cashier_name"),
                rs.getLong("office_id"),
                rs.getString("office_name"));
    final Timestamp cancelledAt = rs.getTimestamp("cancellation_date");
    final String cancellationReason = rs.getString("cancellation_reason");
    final TransactionHistoryCancellationData cancellation =
        cancelledAt == null && cancellationReason == null
            ? null
            : new TransactionHistoryCancellationData(
                cancellationReason,
                rs.getString("cancellation_user"),
                offset(cancelledAt));
    return new TransactionHistoryDetailData(
        historyId,
        offset(rs.getTimestamp("transaction_date")),
        client,
        rs.getString("operation"),
        rs.getString("concept"),
        rs.getString("reference"),
        teller,
        rs.getString("status"),
        rs.getString("currency_code"),
        nullableInteger(rs, "decimal_places"),
        rs.getBigDecimal("cash_received"),
        rs.getBigDecimal("checks_received"),
        rs.getBigDecimal("change_amount"),
        rs.getBigDecimal("adjustment"),
        rs.getBigDecimal("total_amount"),
        cancellation,
        rs.getBoolean("receipt_supported"));
  }

  private ResolvedQuery validate(
      final TransactionHistoryQuery value, final Scope scope, final boolean paged) {
    final TransactionHistoryQuery query =
        value == null
            ? new TransactionHistoryQuery(null, null, null, null, null, null, null, null, null, null, null, null, null)
            : value;
    if (query.fromDate() != null
        && query.toDate() != null
        && query.fromDate().isAfter(query.toDate())) {
      throw invalid("date.range.invalid", "fromDate must be on or before toDate.");
    }
    if (query.tellerId() != null && !scope.cashierIds().contains(query.tellerId())) {
      throw invalid("teller.forbidden", "The teller is outside the authenticated user's scope.");
    }
    if (query.reference() != null && query.reference().length() > MAX_REFERENCE_LENGTH) {
      throw invalid("reference.too.long", "reference must not exceed 200 characters.");
    }
    final int offset = query.offset() == null ? 0 : query.offset();
    final int limit = query.limit() == null ? DEFAULT_LIMIT : query.limit();
    if (offset < 0) {
      throw invalid("offset.invalid", "offset must be zero or greater.");
    }
    if (paged && (limit < 1 || limit > MAX_LIMIT)) {
      throw invalid("limit.invalid", "limit must be between 1 and 500.");
    }
    final String sort = StringUtils.defaultIfBlank(query.sort(), "transactionDate");
    if (!SORT_FIELDS.contains(sort)) {
      throw invalid("sort.invalid", "Unsupported sort field.");
    }
    final String order = StringUtils.defaultIfBlank(query.order(), "DESC").toUpperCase(Locale.ROOT);
    if (!Set.of("ASC", "DESC").contains(order)) {
      throw invalid("order.invalid", "order must be ASC or DESC.");
    }
    validateCurrency(query.currencyCode());
    final String status = normalize(query.status());
    final String type = normalize(query.type());
    final String operation = normalize(query.operation());
    final String concept = normalize(query.concept());
    validateHistoryOptions(scope, status, type, operation, concept);
    return new ResolvedQuery(
        scope,
        query.fromDate(),
        query.toDate(),
        query.tellerId(),
        normalize(query.currencyCode()),
        status,
        type,
        operation,
        concept,
        StringUtils.trimToNull(query.reference()),
        offset,
        limit,
        sort,
        order);
  }

  private String filteredWhere(final ResolvedQuery query, final Map<String, Object> params) {
    final StringBuilder where = new StringBuilder(authorizedWhere(query.scope(), params));
    if (query.fromDate() != null) {
      where.append(" AND h.business_date>=:fromDate");
      params.put("fromDate", query.fromDate());
    }
    if (query.toDate() != null) {
      where.append(" AND h.business_date<=:toDate");
      params.put("toDate", query.toDate());
    }
    if (query.tellerId() != null) {
      where.append(" AND (h.source_cashier_id=:tellerId OR h.destination_cashier_id=:tellerId)");
      params.put("tellerId", query.tellerId());
    }
    appendEqual(where, params, "currency_code", "currencyCode", query.currencyCode());
    appendEqual(where, params, "status", "status", query.status());
    appendEqual(where, params, "transaction_type", "transactionType", query.type());
    appendEqual(where, params, "operation", "operation", query.operation());
    appendEqual(where, params, "concept", "concept", query.concept());
    if (query.reference() != null) {
      where.append(" AND LOWER(h.searchable_reference) LIKE :reference ESCAPE '\\'");
      params.put("reference", "%" + escapeLike(query.reference().toLowerCase(Locale.ROOT)) + "%");
    }
    return where.toString();
  }

  private static void appendEqual(
      final StringBuilder where,
      final Map<String, Object> params,
      final String column,
      final String parameter,
      final String value) {
    if (value != null) {
      where.append(" AND UPPER(h.").append(column).append(")=:").append(parameter);
      params.put(parameter, value);
    }
  }

  private String authorizedWhere(final Scope scope, final Map<String, Object> params) {
    return " WHERE o.hierarchy LIKE :hierarchy" + authorizedClause(scope, params);
  }

  private String authorizedClause(final Scope scope, final Map<String, Object> params) {
    if (scope.assignedCashierId() == null) {
      return "";
    }
    params.put("authorizedCashierId", scope.assignedCashierId());
    return " AND (h.source_cashier_id=:authorizedCashierId OR h.destination_cashier_id=:authorizedCashierId)";
  }

  private Scope scope() {
    final AppUser user = securityContext.authenticatedUser();
    user.validateHasPermissionTo(PERMISSION);
    final LocalDate date = DateUtils.getBusinessLocalDate();
    final Map<String, Object> params =
        new HashMap<>(Map.of("hierarchy", user.getOffice().getHierarchy() + "%", "date", date));
    Long assignedCashierId = null;
    if (user.getStaffId() != null && !user.hasAnyPermission("READ_GLOBAL_SETTLEMENT")) {
      params.put("staffId", user.getStaffId());
      final List<Long> ids =
          jdbcTemplate.queryForList(
              "SELECT c.id FROM m_cashiers c JOIN m_tellers t ON t.id=c.teller_id"
                  + " JOIN m_office o ON o.id=t.office_id WHERE c.staff_id=:staffId"
                  + " AND o.hierarchy LIKE :hierarchy"
                  + " AND (c.start_date IS NULL OR c.start_date<=:date)"
                  + " AND (c.end_date IS NULL OR c.end_date>=:date) ORDER BY c.id",
              params,
              Long.class);
      if (!ids.isEmpty()) {
        assignedCashierId = ids.get(0);
      }
    }
    final Map<String, Object> tellerParams =
        new HashMap<>(Map.of("hierarchy", user.getOffice().getHierarchy() + "%"));
    String tellerSql =
        "SELECT c.id cashier_id,c.teller_id,COALESCE(au.username,CAST(c.id AS varchar)) code,"
            + " s.display_name,o.id office_id,o.name office_name FROM m_cashiers c"
            + " JOIN m_tellers t ON t.id=c.teller_id JOIN m_office o ON o.id=t.office_id"
            + " JOIN m_staff s ON s.id=c.staff_id LEFT JOIN m_appuser au ON au.staff_id=s.id"
            + " WHERE o.hierarchy LIKE :hierarchy";
    if (assignedCashierId != null) {
      tellerSql += " AND c.id=:assignedCashierId";
      tellerParams.put("assignedCashierId", assignedCashierId);
    }
    tellerSql += " ORDER BY s.display_name,c.id";
    final List<TransactionHistoryTellerData> tellers =
        jdbcTemplate.query(
            tellerSql,
            tellerParams,
            (rs, row) ->
                new TransactionHistoryTellerData(
                    rs.getLong("cashier_id"),
                    rs.getLong("teller_id"),
                    rs.getString("code"),
                    rs.getString("display_name"),
                    rs.getLong("office_id"),
                    rs.getString("office_name")));
    return new Scope(
        user.getOffice().getHierarchy() + "%",
        assignedCashierId,
        tellers,
        tellers.stream().map(TransactionHistoryTellerData::id).toList());
  }

  private List<TransactionHistoryTellerData> tellers(final Scope scope) {
    return scope.tellers();
  }

  private List<TransactionHistoryCurrencyData> currencies() {
    return jdbcTemplate.query(
        "SELECT UPPER(code) code,name,decimal_places FROM m_organisation_currency ORDER BY code",
        (rs, row) ->
            new TransactionHistoryCurrencyData(
                rs.getString("code"), rs.getString("name"), rs.getInt("decimal_places")));
  }

  private List<TransactionHistoryOptionData> options(
      final String column, final String authorized, final Map<String, Object> params) {
    final List<String> values =
        jdbcTemplate.queryForList(
            historyCte()
                + " SELECT DISTINCT h."
                + column
                + " FROM history h JOIN m_office o ON o.id=h.office_id"
                + authorized
                + " AND h."
                + column
                + " IS NOT NULL ORDER BY h."
                + column,
            params,
            String.class);
    return values.stream().map(value -> new TransactionHistoryOptionData(value, label(value))).toList();
  }

  private void validateCurrency(final String currency) {
    if (StringUtils.isBlank(currency)) {
      return;
    }
    final Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM m_organisation_currency WHERE UPPER(code)=:currency",
            Map.of("currency", normalize(currency)),
            Integer.class);
    if (count == null || count == 0) {
      throw invalid("currency.unsupported", "Currency is not enabled for the Fineract tenant.");
    }
  }

  private void validateHistoryOptions(
      final Scope scope,
      final String status,
      final String type,
      final String operation,
      final String concept) {
    final Map<String, String> requested = new LinkedHashMap<>();
    requested.put("status", status);
    requested.put("transaction_type", type);
    requested.put("operation", operation);
    requested.put("concept", concept);
    requested.values().removeIf(java.util.Objects::isNull);
    if (requested.isEmpty()) {
      return;
    }
    final Map<String, Object> params = baseParams(scope);
    final String authorized = authorizedWhere(scope, params);
    final StringBuilder sql = new StringBuilder(historyCte()).append(" SELECT ");
    int index = 0;
    for (Map.Entry<String, String> entry : requested.entrySet()) {
      if (index > 0) {
        sql.append(',');
      }
      final String parameter = "option" + index;
      sql.append("COALESCE(BOOL_OR(UPPER(h.")
          .append(entry.getKey())
          .append(")=:")
          .append(parameter)
          .append("),false) option_")
          .append(index);
      params.put(parameter, entry.getValue());
      index++;
    }
    sql.append(" FROM history h JOIN m_office o ON o.id=h.office_id").append(authorized);
    final Map<String, Object> result = jdbcTemplate.queryForMap(sql.toString(), params);
    index = 0;
    for (Map.Entry<String, String> entry : requested.entrySet()) {
      if (!Boolean.TRUE.equals(result.get("option_" + index))) {
        throw invalid(entry.getKey() + ".unsupported", "Unsupported or unauthorized history filter value.");
      }
      index++;
    }
  }

  private static String effectiveInflow(final Long tellerId) {
    if (tellerId == null) {
      return "h.inflow";
    }
    return "CASE WHEN h.transfer_amount>0 AND h.destination_cashier_id=:tellerId"
        + " THEN h.transfer_amount ELSE h.inflow END";
  }

  private static String effectiveOutflow(final Long tellerId) {
    if (tellerId == null) {
      return "h.outflow";
    }
    return "CASE WHEN h.transfer_amount>0 AND h.source_cashier_id=:tellerId"
        + " THEN h.transfer_amount ELSE h.outflow END";
  }

  private static String orderBy(final ResolvedQuery query) {
    final String primary = SORT_COLUMNS.get(query.sort());
    return " ORDER BY "
        + primary
        + " "
        + query.order()
        + ("h.transaction_date".equals(primary) ? "" : ",h.transaction_date DESC")
        + ",h.source_type DESC,h.source_id DESC";
  }

  private static Map<String, Object> baseParams(final Scope scope) {
    return new HashMap<>(Map.of("hierarchy", scope.hierarchy()));
  }

  private static HistoryKey historyKey(final String value) {
    if (StringUtils.isBlank(value)) {
      throw invalid("history.id.invalid", "historyId is required.");
    }
    final int delimiter = value.indexOf(':');
    if (delimiter < 1 || delimiter == value.length() - 1) {
      throw invalid("history.id.invalid", "historyId must contain a source type and source id.");
    }
    try {
      return new HistoryKey(
          value.substring(0, delimiter).toUpperCase(Locale.ROOT),
          Long.valueOf(value.substring(delimiter + 1)));
    } catch (NumberFormatException exception) {
      throw invalid("history.id.invalid", "historyId contains an invalid source id.");
    }
  }

  private static DenominationSource denominationSource(final String sourceType) {
    return switch (sourceType) {
      case "SAVINGS_DEPOSIT" ->
          new DenominationSource(
              "m_base_teller_deposit_cash_detail",
              "m_base_teller_deposit",
              "deposit_id",
              "d.denomination_value*d.quantity",
              "CAST(NULL AS varchar)");
      case "SAVINGS_OPENING" ->
          new DenominationSource(
              "m_base_teller_savings_opening_cash_detail",
              "m_base_teller_savings_opening",
              "opening_id",
              "d.denomination_value*d.quantity",
              "CAST(NULL AS varchar)");
      case "RETURNED_CHECK_PAYMENT" ->
          new DenominationSource(
              "m_base_teller_returned_check_payment_cash_detail",
              "m_base_teller_returned_check_payment",
              "settlement_id",
              "d.denomination_value*d.quantity",
              "CAST(NULL AS varchar)");
      case "SERVICE_PAYMENT" ->
          new DenominationSource(
              "m_service_payment_cash_detail",
              "m_service_payment",
              "service_payment_id",
              "d.line_total",
              "CAST(NULL AS varchar)");
      case "CREDIT_PAYMENT" ->
          new DenominationSource(
              "m_base_teller_credit_payment_cash_detail",
              "m_base_teller_credit_payment",
              "credit_payment_id",
              "d.denomination_value*d.quantity",
              "CAST(NULL AS varchar)");
      case "CASH_ALLOCATION" ->
          new DenominationSource(
              "m_cash_allocation_denomination",
              "m_cash_allocation",
              "allocation_id",
              "d.line_total",
              "d.denomination_type");
      case "CASHIER_CLOSING" ->
          new DenominationSource(
              "m_cashier_reconciliation_denomination",
              "m_cashier_reconciliation",
              "reconciliation_id",
              "d.line_total",
              "CAST(NULL AS varchar)");
      case "CASH_OPERATION" ->
          new DenominationSource(
              "m_cash_operation_denomination",
              "m_cash_operation_transaction",
              "operation_id",
              "d.line_total",
              "CAST(NULL AS varchar)");
      default -> null;
    };
  }

  private static String historyId(final String sourceType, final Long sourceId) {
    return sourceType + ":" + sourceId;
  }

  private static String normalize(final String value) {
    return StringUtils.isBlank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private static String label(final String value) {
    final String[] words = value.toLowerCase(Locale.ROOT).split("_");
    final StringBuilder result = new StringBuilder();
    for (String word : words) {
      if (!result.isEmpty()) {
        result.append(' ');
      }
      result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return result.toString();
  }

  private static String escapeLike(final String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static OffsetDateTime offset(final Timestamp value) {
    return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
  }

  private static Long nullableLong(final ResultSet rs, final String column) throws SQLException {
    final Object value = rs.getObject(column);
    return value == null ? null : ((Number) value).longValue();
  }

  private static Integer nullableInteger(final ResultSet rs, final String column)
      throws SQLException {
    final Object value = rs.getObject(column);
    return value == null ? null : ((Number) value).intValue();
  }

  private static PlatformDataIntegrityException notFound() {
    return new PlatformDataIntegrityException(
        "error.msg.base.teller.transaction.history.not.found", "Transaction history record not found.");
  }

  private static GeneralPlatformDomainRuleException invalid(
      final String code, final String message) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.base.teller.transaction.history." + code, message);
  }

  private static String historyCte() {
    return """
        WITH history AS (
          SELECT 'SAVINGS_DEPOSIT' source_type,d.id source_id,
            COALESCE(d.completed_on_utc,d.created_on_utc) transaction_date,
            CAST(COALESCE(d.completed_on_utc,d.created_on_utc) AS date) business_date,
            'SAVINGS_DEPOSIT' operation,d.funding_type transaction_type,
            'SAVINGS_DEPOSIT' concept,d.status,d.receipt_number reference,
            CONCAT_WS(' ',d.receipt_number,
              (SELECT STRING_AGG(cd.check_number,' ') FROM m_base_teller_deposit_check_detail cd WHERE cd.deposit_id=d.id)) searchable_reference,
            UPPER(d.currency_code) currency_code,d.amount inflow,CAST(0 AS decimal(19,6)) outflow,
            d.client_id,d.office_id,d.teller_id,d.cashier_id,d.cashier_id source_cashier_id,
            d.cashier_id destination_cashier_id,CAST(0 AS decimal(19,6)) transfer_amount,
            CASE WHEN d.funding_type='CASH' THEN d.amount END cash_received,
            CASE WHEN d.funding_type='CHECK' THEN d.amount END checks_received,
            CAST(NULL AS decimal(19,6)) change_amount,CAST(NULL AS decimal(19,6)) adjustment,
            d.amount total_amount,CAST(NULL AS varchar) cancellation_reason,
            CAST(NULL AS varchar) cancellation_user,CAST(NULL AS timestamp) cancellation_date,true receipt_supported
          FROM m_base_teller_deposit d
          UNION ALL
          SELECT 'SAVINGS_OPENING',x.id,COALESCE(x.completed_on_utc,x.created_on_utc),
            CAST(COALESCE(x.completed_on_utc,x.created_on_utc) AS date),'OPEN_SAVINGS_ACCOUNT',x.funding_type,
            'SAVINGS_ACCOUNT_OPENING',x.status,x.receipt_number,CONCAT_WS(' ',x.receipt_number,x.check_number),
            UPPER(x.currency_code),x.amount,CAST(0 AS decimal(19,6)),x.client_id,x.office_id,x.teller_id,x.cashier_id,
            x.cashier_id,x.cashier_id,CAST(0 AS decimal(19,6)),
            CASE WHEN x.funding_type='CASH' THEN x.amount END,
            CASE WHEN x.funding_type='CHECK' THEN x.amount END,
            NULL,NULL,x.amount,NULL,NULL,NULL,true
          FROM m_base_teller_savings_opening x
          UNION ALL
          SELECT 'RETURNED_CHECK_PAYMENT',p.id,COALESCE(p.completed_on_utc,p.created_on_utc),
            CAST(COALESCE(p.completed_on_utc,p.created_on_utc) AS date),'SETTLE_RETURNED_CHECK','CASH',
            'RETURNED_CHECK_PAYMENT',p.status,p.receipt_number,CONCAT_WS(' ',p.receipt_number,rc.check_number),
            UPPER(p.currency_code),rc.amount,CAST(0 AS decimal(19,6)),rc.client_id,p.office_id,p.teller_id,p.cashier_id,
            p.cashier_id,p.cashier_id,CAST(0 AS decimal(19,6)),p.cash_received,CAST(NULL AS decimal(19,6)),
            p.change_amount,NULL,rc.amount,NULL,NULL,NULL,true
          FROM m_base_teller_returned_check_payment p
          JOIN m_base_teller_returned_check rc ON rc.id=p.returned_check_id
          UNION ALL
          SELECT 'SERVICE_PAYMENT',p.id,COALESCE(p.completed_on_utc,p.created_on_utc),p.business_date,
            'PAY_SERVICE','CASH',p.service_name,p.status,p.receipt_number,
            CONCAT_WS(' ',p.receipt_number,p.service_reference),UPPER(p.currency_code),p.total_to_pay,
            CAST(0 AS decimal(19,6)),p.client_id,p.office_id,p.teller_id,p.cashier_id,p.cashier_id,p.cashier_id,
            CAST(0 AS decimal(19,6)),p.amount_received,CAST(NULL AS decimal(19,6)),p.change_amount,NULL,
            p.total_to_pay,NULL,NULL,NULL,true
          FROM m_service_payment p
          UNION ALL
          SELECT 'CREDIT_PAYMENT',p.id,COALESCE(p.completed_on_utc,p.created_on_utc),p.business_date,
            'PAY_CREDIT',p.payment_method,'LOAN_REPAYMENT',p.status,p.receipt_number,
            CONCAT_WS(' ',p.receipt_number,c.check_number),UPPER(p.currency_code),p.amount,
            CAST(0 AS decimal(19,6)),p.client_id,p.office_id,p.teller_id,p.cashier_id,p.cashier_id,p.cashier_id,
            CAST(0 AS decimal(19,6)),CASE WHEN p.payment_method='CASH' THEN p.tender_amount END,
            CASE WHEN p.payment_method='CHECK' THEN p.amount END,p.change_amount,NULL,p.amount,
            CASE WHEN p.status='RETURNED' THEN c.return_reason END,
            CASE WHEN p.status='RETURNED' THEN ru.username END,
            CASE WHEN p.status='RETURNED' THEN c.returned_on_utc END,true
          FROM m_base_teller_credit_payment p
          LEFT JOIN m_base_teller_credit_payment_check c ON c.credit_payment_id=p.id
          LEFT JOIN m_appuser ru ON ru.id=c.returned_by
          UNION ALL
          SELECT 'CASH_ALLOCATION',a.id,COALESCE(a.completed_on_utc,a.created_on_utc),a.business_date,
            'ALLOCATE_CASH','INTERNAL_MOVEMENT',a.operation_type,a.status,a.receipt_number,
            a.receipt_number,UPPER(a.currency_code),
            CASE WHEN a.destination_cashier_id IS NOT NULL THEN a.amount ELSE CAST(0 AS decimal(19,6)) END,
            CASE WHEN a.source_cashier_id IS NOT NULL THEN a.amount ELSE CAST(0 AS decimal(19,6)) END,
            NULL,a.office_id,NULL,COALESCE(a.destination_cashier_id,a.source_cashier_id),
            a.source_cashier_id,a.destination_cashier_id,a.amount,NULL,NULL,NULL,NULL,a.amount,
            NULL,NULL,NULL,true
          FROM m_cash_allocation a
          UNION ALL
          SELECT 'CASHIER_CLOSING',r.id,r.completed_on_utc,r.business_date,'CLOSE_CASHIER','MIXED',
            'CASHIER_RECONCILIATION',r.status,r.receipt_number,r.receipt_number,UPPER(r.currency_code),
            CAST(0 AS decimal(19,6)),r.actual_amount,NULL,r.office_id,r.teller_id,r.cashier_id,r.cashier_id,r.cashier_id,
            CAST(0 AS decimal(19,6)),NULL,NULL,NULL,r.difference_amount,r.actual_amount,NULL,NULL,NULL,true
          FROM m_cashier_reconciliation r
          UNION ALL
          SELECT 'CASH_OPERATION',x.id,COALESCE(x.completed_on_utc,x.created_on_utc),x.business_date,
            x.transaction_type,
            CASE WHEN x.cash_total>0 AND x.check_total>0 THEN 'MIXED' WHEN x.check_total>0 THEN 'CHECK' ELSE 'CASH' END,
            'CASH_OPERATION',x.status,x.receipt_number,CONCAT_WS(' ',x.receipt_number,x.description),
            UPPER(x.currency_code),CAST(0 AS decimal(19,6)),x.amount,NULL,x.office_id,x.teller_id,x.cashier_id,
            x.cashier_id,x.cashier_id,CAST(0 AS decimal(19,6)),NULL,NULL,NULL,NULL,x.amount,NULL,NULL,NULL,false
          FROM m_cash_operation_transaction x
          UNION ALL
          SELECT 'CASHIER_TRANSACTION',ct.id,COALESCE(ct.created_date,CAST(ct.txn_date AS timestamp)),ct.txn_date,
            CASE ct.txn_type WHEN 101 THEN 'CASH_ALLOCATION' WHEN 102 THEN 'CASH_SETTLEMENT'
              WHEN 103 THEN 'CASH_IN' WHEN 104 THEN 'CASH_OUT' ELSE 'CASHIER_TRANSACTION' END,
            'CASH','NATIVE_CASHIER_TRANSACTION','COMPLETED',COALESCE(ct.txn_note,CAST(ct.id AS varchar)),
            CONCAT_WS(' ',ct.txn_note,ct.entity_type),UPPER(ct.currency_code),
            CASE WHEN ct.txn_type IN (101,103) THEN ct.txn_amount ELSE CAST(0 AS decimal(19,6)) END,
            CASE WHEN ct.txn_type IN (102,104) THEN ct.txn_amount ELSE CAST(0 AS decimal(19,6)) END,
            NULL,t.office_id,c.teller_id,ct.cashier_id,ct.cashier_id,ct.cashier_id,CAST(0 AS decimal(19,6)),
            NULL,NULL,NULL,NULL,ct.txn_amount,NULL,NULL,NULL,false
          FROM m_cashier_transactions ct
          JOIN m_cashiers c ON c.id=ct.cashier_id JOIN m_tellers t ON t.id=c.teller_id
          WHERE NOT (ct.entity_type='BASE_TELLER_DEPOSIT'
            AND EXISTS (SELECT 1 FROM m_base_teller_deposit d WHERE d.id=ct.entity_id))
            AND NOT (ct.entity_type='BASE_TELLER_SAVINGS_OPENING'
            AND EXISTS (SELECT 1 FROM m_base_teller_savings_opening x WHERE x.id=ct.entity_id))
            AND NOT EXISTS (SELECT 1 FROM m_base_teller_returned_check_payment p WHERE p.cashier_transaction_id=ct.id)
            AND NOT EXISTS (SELECT 1 FROM m_base_teller_credit_payment p WHERE p.cashier_transaction_id=ct.id)
            AND NOT EXISTS (SELECT 1 FROM m_service_payment p WHERE p.cashier_transaction_id=ct.id)
            AND NOT EXISTS (SELECT 1 FROM m_cash_allocation a WHERE a.source_cashier_transaction_id=ct.id OR a.destination_cashier_transaction_id=ct.id)
            AND NOT EXISTS (SELECT 1 FROM m_cashier_reconciliation r WHERE r.cashier_transaction_id=ct.id)
            AND NOT EXISTS (SELECT 1 FROM m_cash_operation_transaction x WHERE x.cashier_transaction_id=ct.id)
        )
        """;
  }

  private record Scope(
      String hierarchy,
      Long assignedCashierId,
      List<TransactionHistoryTellerData> tellers,
      List<Long> cashierIds) {}

  private record ResolvedQuery(
      Scope scope,
      LocalDate fromDate,
      LocalDate toDate,
      Long tellerId,
      String currencyCode,
      String status,
      String type,
      String operation,
      String concept,
      String reference,
      int offset,
      int limit,
      String sort,
      String order) {}

  private record HistoryKey(String sourceType, Long sourceId) {}

  private record DenominationSource(
      String detailTable,
      String headerTable,
      String foreignKey,
      String amountExpression,
      String typeExpression) {}
}
