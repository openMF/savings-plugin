package org.apache.fineract.baseteller.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.BaseTellerAccountSummaryData;
import org.apache.fineract.baseteller.data.BaseTellerCheckData;
import org.apache.fineract.baseteller.data.BaseTellerCheckClearingStatus;
import org.apache.fineract.baseteller.data.BaseTellerCustomerData;
import org.apache.fineract.baseteller.data.BaseTellerCustomerPositionData;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.BaseTellerDepositCheckData;
import org.apache.fineract.baseteller.data.BaseTellerDepositReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerDepositStatus;
import org.apache.fineract.baseteller.data.BaseTellerFundingType;
import org.apache.fineract.baseteller.data.BaseTellerOpeningReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerOpeningStatus;
import org.apache.fineract.baseteller.data.BaseTellerSavingsProductData;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.savings.data.SavingsProductData;
import org.apache.fineract.portfolio.savings.service.SavingsProductReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BaseTellerReadPlatformServiceImpl implements BaseTellerReadPlatformService {

  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 100;

  private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  private final PlatformSecurityContext context;
  private final ClientRepositoryWrapper clientRepository;
  private final SavingsProductReadPlatformService savingsProductReadPlatformService;

  @Override
  public List<BaseTellerCustomerData> searchCustomers(
      final Long clientId, final String accountNo, final String query, final Integer limit) {
    final AppUser user = context.authenticatedUser();
    user.validateHasReadPermission("CLIENT");
    final Map<String, Object> params = new HashMap<>();
    params.put("officeHierarchy", user.getOffice().getHierarchy() + "%");
    final StringBuilder sql =
        new StringBuilder(
            "SELECT c.id AS client_id, c.account_no, c.external_id, c.display_name,"
                + " c.office_id, o.name AS office_name, c.status_enum"
                + " FROM m_client c JOIN m_office o ON o.id = c.office_id"
                + " WHERE o.hierarchy LIKE :officeHierarchy");
    if (clientId != null) {
      sql.append(" AND c.id = :clientId");
      params.put("clientId", clientId);
    }
    if (StringUtils.isNotBlank(accountNo)) {
      sql.append(" AND c.account_no = :accountNo");
      params.put("accountNo", accountNo);
    }
    if (StringUtils.isNotBlank(query)) {
      sql.append(" AND LOWER(COALESCE(c.display_name, '')) LIKE :query");
      params.put("query", "%" + query.toLowerCase(Locale.ROOT) + "%");
    }
    sql.append(" ORDER BY c.display_name, c.id LIMIT :limit");
    params.put("limit", Math.min(limit == null || limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
    return namedParameterJdbcTemplate.query(sql.toString(), params, new CustomerMapper());
  }

  @Override
  public BaseTellerCustomerPositionData retrieveCustomerPosition(final Long clientId) {
    context.authenticatedUser().validateHasReadPermission("CLIENT");
    context.authenticatedUser().validateHasReadPermission("savingsaccount");
    context.authenticatedUser().validateHasReadPermission("loan");
    final Client client = clientRepository.findOneWithNotFoundDetection(clientId);
    final BaseTellerCustomerData customer =
        new BaseTellerCustomerData(
            client.getId(),
            client.getAccountNumber(),
            client.getExternalId() == null ? null : client.getExternalId().getValue(),
            client.getDisplayName(),
            client.officeId(),
            client.getOffice() == null ? null : client.getOffice().getName(),
            String.valueOf(client.getStatus()));
    final Map<String, Object> params = Map.of("clientId", clientId);
    return new BaseTellerCustomerPositionData(
        customer, savingsAccounts(params), loanAccounts(params));
  }

  @Override
  public List<BaseTellerSavingsProductData> retrieveSavingsProducts(final String currencyCode) {
    context.authenticatedUser().validateHasReadPermission("savingsproduct");
    return savingsProductReadPlatformService.retrieveAll().stream()
        .filter(
            product ->
                StringUtils.isBlank(currencyCode)
                    || currencyCode.equalsIgnoreCase(currencyCode(product.getCurrency())))
        .map(this::toProductData)
        .toList();
  }

  @Override
  public BaseTellerOpeningReceiptData retrieveOpeningReceipt(final String receiptNumber) {
    context.authenticatedUser().validateHasReadPermission("BASE_TELLER_SAVINGS_OPENING");
    final Map<String, Object> params = Map.of("receiptNumber", receiptNumber);
    final List<BaseTellerOpeningReceiptData> receipts =
        namedParameterJdbcTemplate.query(
            openingReceiptSql() + " WHERE o.receipt_number = :receiptNumber",
            params,
            new OpeningReceiptMapper(this::openingDenominations));
    if (receipts.isEmpty()) {
      throw new PlatformDataIntegrityException(
          "error.msg.base.teller.receipt.not.found",
          "Base teller savings account opening receipt not found.");
    }
    return receipts.get(0);
  }

  @Override
  public BaseTellerDepositReceiptData retrieveDepositReceipt(final String receiptNumber) {
    context.authenticatedUser().validateHasReadPermission("BASE_TELLER_DEPOSIT");
    final Map<String, Object> params = Map.of("receiptNumber", receiptNumber);
    final List<BaseTellerDepositReceiptData> receipts =
        namedParameterJdbcTemplate.query(
            depositReceiptSql() + " WHERE d.receipt_number = :receiptNumber",
            params,
            new DepositReceiptMapper(this::depositDenominations, this::depositChecks));
    if (receipts.isEmpty()) {
      throw new PlatformDataIntegrityException(
          "error.msg.base.teller.deposit.receipt.not.found",
          "Base teller deposit receipt not found.");
    }
    return receipts.get(0);
  }

  private List<BaseTellerAccountSummaryData> savingsAccounts(final Map<String, Object> params) {
    return namedParameterJdbcTemplate.query(
        "SELECT sa.id AS account_id, sa.account_no, sa.product_id, sp.name AS product_name,"
            + " sa.currency_code, sa.status_enum AS status,"
            + " COALESCE(sas.account_balance_derived, 0) AS balance"
            + " FROM m_savings_account sa JOIN m_savings_product sp ON sp.id = sa.product_id"
            + " LEFT JOIN m_savings_account_summary sas ON sas.savings_account_id = sa.id"
            + " WHERE sa.client_id = :clientId ORDER BY sa.id",
        params,
        new AccountMapper());
  }

  private List<BaseTellerAccountSummaryData> loanAccounts(final Map<String, Object> params) {
    return namedParameterJdbcTemplate.query(
        "SELECT l.id AS account_id, l.account_no, l.product_id, lp.name AS product_name,"
            + " l.currency_code, l.loan_status_id AS status,"
            + " COALESCE(l.principal_outstanding_derived, 0) AS balance"
            + " FROM m_loan l JOIN m_product_loan lp ON lp.id = l.product_id"
            + " WHERE l.client_id = :clientId ORDER BY l.id",
        params,
        new AccountMapper());
  }

  private BaseTellerSavingsProductData toProductData(final SavingsProductData product) {
    return new BaseTellerSavingsProductData(
        product.getId(),
        product.getName(),
        currencyCode(product.getCurrency()),
        product.getNominalAnnualInterestRate(),
        product.getMinRequiredOpeningBalance(),
        product.isAllowOverdraft());
  }

  private static String currencyCode(final CurrencyData currency) {
    return currency == null ? null : currency.getCode();
  }

  private List<BaseTellerDenominationData> openingDenominations(final Long openingId) {
    return namedParameterJdbcTemplate.query(
        "SELECT denomination_identifier, denomination_value, quantity"
            + " FROM m_base_teller_savings_opening_cash_detail"
            + " WHERE opening_id = :openingId ORDER BY id",
        Map.of("openingId", openingId),
        (rs, row) ->
            new BaseTellerDenominationData(
                rs.getString("denomination_identifier"),
                rs.getBigDecimal("denomination_value"),
                rs.getLong("quantity")));
  }

  private List<BaseTellerDenominationData> depositDenominations(final Long depositId) {
    return namedParameterJdbcTemplate.query(
        "SELECT denomination_identifier, denomination_value, quantity"
            + " FROM m_base_teller_deposit_cash_detail"
            + " WHERE deposit_id = :depositId ORDER BY id",
        Map.of("depositId", depositId),
        (rs, row) ->
            new BaseTellerDenominationData(
                rs.getString("denomination_identifier"),
                rs.getBigDecimal("denomination_value"),
                rs.getLong("quantity")));
  }

  private List<BaseTellerDepositCheckData> depositChecks(final Long depositId) {
    return namedParameterJdbcTemplate.query(
        "SELECT c.check_type, c.check_bank, c.check_number, c.amount, c.clearing_status,"
            + " c.check_account_number, c.check_routing_code, c.clearing_authorized_by,"
            + " au.username AS clearing_authorized_by_username, c.clearing_authorized_on_utc"
            + " FROM m_base_teller_deposit_check_detail c"
            + " LEFT JOIN m_appuser au ON au.id = c.clearing_authorized_by"
            + " WHERE c.deposit_id = :depositId ORDER BY c.id",
        Map.of("depositId", depositId),
        (rs, row) ->
            new BaseTellerDepositCheckData(
                rs.getString("check_type"),
                rs.getString("check_bank"),
                rs.getString("check_number"),
                rs.getBigDecimal("amount"),
                BaseTellerCheckClearingStatus.valueOf(rs.getString("clearing_status")),
                rs.getString("check_account_number"),
                rs.getString("check_routing_code"),
                nullableLong(rs, "clearing_authorized_by"),
                rs.getString("clearing_authorized_by_username"),
                offsetDateTime(rs, "clearing_authorized_on_utc")));
  }

  private static String depositReceiptSql() {
    return "SELECT d.id AS deposit_id, d.receipt_number, d.status, d.failure_message,"
        + " d.client_id, c.display_name AS customer_name, d.savings_account_id, sa.account_no,"
        + " sa.product_id AS savings_product_id, sp.name AS product_name,"
        + " d.savings_transaction_id, d.funding_type, d.amount, d.currency_code,"
        + " COALESCE(sas.account_balance_derived, 0) AS resulting_balance,"
        + " d.teller_id, d.cashier_id, d.operator_id, au.username AS operator_name,"
        + " d.office_id, off.name AS office_name, d.created_on_utc, d.completed_on_utc"
        + " FROM m_base_teller_deposit d"
        + " JOIN m_client c ON c.id = d.client_id"
        + " JOIN m_savings_account sa ON sa.id = d.savings_account_id"
        + " LEFT JOIN m_savings_product sp ON sp.id = sa.product_id"
        + " LEFT JOIN m_savings_account_summary sas ON sas.savings_account_id = sa.id"
        + " LEFT JOIN m_appuser au ON au.id = d.operator_id"
        + " LEFT JOIN m_office off ON off.id = d.office_id";
  }

  private static String openingReceiptSql() {
    return "SELECT o.id AS opening_id, o.receipt_number, o.status, o.failure_message,"
        + " o.client_id, c.display_name AS customer_name, o.savings_account_id, sa.account_no,"
        + " o.savings_product_id, sp.name AS product_name, o.initial_deposit_transaction_id,"
        + " o.funding_type, o.amount, o.currency_code,"
        + " COALESCE(sas.account_balance_derived, 0) AS resulting_balance,"
        + " o.teller_id, o.cashier_id, o.operator_id, au.username AS operator_name,"
        + " o.office_id, off.name AS office_name, o.created_on_utc, o.completed_on_utc,"
        + " o.check_type, o.check_bank, o.check_number, o.check_account_number,"
        + " o.check_routing_code"
        + " FROM m_base_teller_savings_opening o"
        + " JOIN m_client c ON c.id = o.client_id"
        + " LEFT JOIN m_savings_account sa ON sa.id = o.savings_account_id"
        + " LEFT JOIN m_savings_product sp ON sp.id = o.savings_product_id"
        + " LEFT JOIN m_savings_account_summary sas ON sas.savings_account_id = sa.id"
        + " LEFT JOIN m_appuser au ON au.id = o.operator_id"
        + " LEFT JOIN m_office off ON off.id = o.office_id";
  }

  private static Long nullableLong(final ResultSet rs, final String column) throws SQLException {
    final long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static OffsetDateTime offsetDateTime(final ResultSet rs, final String column)
      throws SQLException {
    final Timestamp timestamp = rs.getTimestamp(column);
    return timestamp == null
        ? null
        : OffsetDateTime.of(timestamp.toLocalDateTime(), ZoneOffset.UTC);
  }

  private static final class CustomerMapper implements RowMapper<BaseTellerCustomerData> {

    @Override
    public BaseTellerCustomerData mapRow(final ResultSet rs, final int rowNum)
        throws SQLException {
      return new BaseTellerCustomerData(
          rs.getLong("client_id"),
          rs.getString("account_no"),
          rs.getString("external_id"),
          rs.getString("display_name"),
          rs.getLong("office_id"),
          rs.getString("office_name"),
          rs.getString("status_enum"));
    }
  }

  private static final class AccountMapper implements RowMapper<BaseTellerAccountSummaryData> {

    @Override
    public BaseTellerAccountSummaryData mapRow(final ResultSet rs, final int rowNum)
        throws SQLException {
      return new BaseTellerAccountSummaryData(
          rs.getLong("account_id"),
          rs.getString("account_no"),
          rs.getLong("product_id"),
          rs.getString("product_name"),
          rs.getString("currency_code"),
          rs.getString("status"),
          rs.getBigDecimal("balance"));
    }
  }

  private static final class DepositReceiptMapper
      implements RowMapper<BaseTellerDepositReceiptData> {

    private final java.util.function.Function<Long, List<BaseTellerDenominationData>>
        denominationLookup;
    private final java.util.function.Function<Long, List<BaseTellerDepositCheckData>> checkLookup;

    private DepositReceiptMapper(
        final java.util.function.Function<Long, List<BaseTellerDenominationData>>
            denominationLookup,
        final java.util.function.Function<Long, List<BaseTellerDepositCheckData>> checkLookup) {
      this.denominationLookup = denominationLookup;
      this.checkLookup = checkLookup;
    }

    @Override
    public BaseTellerDepositReceiptData mapRow(final ResultSet rs, final int rowNum)
        throws SQLException {
      final Long depositId = rs.getLong("deposit_id");
      final BaseTellerFundingType fundingType =
          BaseTellerFundingType.valueOf(rs.getString("funding_type"));
      return new BaseTellerDepositReceiptData(
          rs.getString("receipt_number"),
          BaseTellerDepositStatus.valueOf(rs.getString("status")),
          rs.getString("failure_message"),
          rs.getLong("client_id"),
          rs.getString("customer_name"),
          rs.getLong("savings_account_id"),
          rs.getString("account_no"),
          nullableLong(rs, "savings_product_id"),
          rs.getString("product_name"),
          nullableLong(rs, "savings_transaction_id"),
          fundingType,
          rs.getBigDecimal("amount"),
          rs.getString("currency_code"),
          rs.getBigDecimal("resulting_balance"),
          nullableLong(rs, "teller_id"),
          nullableLong(rs, "cashier_id"),
          nullableLong(rs, "operator_id"),
          rs.getString("operator_name"),
          nullableLong(rs, "office_id"),
          rs.getString("office_name"),
          offsetDateTime(rs, "created_on_utc"),
          offsetDateTime(rs, "completed_on_utc"),
          fundingType == BaseTellerFundingType.CASH
              ? denominationLookup.apply(depositId)
              : List.of(),
          fundingType == BaseTellerFundingType.CHECK ? checkLookup.apply(depositId) : List.of());
    }
  }

  private static final class OpeningReceiptMapper
      implements RowMapper<BaseTellerOpeningReceiptData> {

    private final java.util.function.Function<Long, List<BaseTellerDenominationData>> lookup;

    private OpeningReceiptMapper(
        final java.util.function.Function<Long, List<BaseTellerDenominationData>> lookup) {
      this.lookup = lookup;
    }

    @Override
    public BaseTellerOpeningReceiptData mapRow(final ResultSet rs, final int rowNum)
        throws SQLException {
      final Long openingId = rs.getLong("opening_id");
      final BaseTellerFundingType fundingType =
          BaseTellerFundingType.valueOf(rs.getString("funding_type"));
      return new BaseTellerOpeningReceiptData(
          rs.getString("receipt_number"),
          BaseTellerOpeningStatus.valueOf(rs.getString("status")),
          rs.getString("failure_message"),
          rs.getLong("client_id"),
          rs.getString("customer_name"),
          nullableLong(rs, "savings_account_id"),
          rs.getString("account_no"),
          nullableLong(rs, "savings_product_id"),
          rs.getString("product_name"),
          nullableLong(rs, "initial_deposit_transaction_id"),
          fundingType,
          rs.getBigDecimal("amount"),
          rs.getString("currency_code"),
          rs.getBigDecimal("resulting_balance"),
          nullableLong(rs, "teller_id"),
          nullableLong(rs, "cashier_id"),
          nullableLong(rs, "operator_id"),
          rs.getString("operator_name"),
          nullableLong(rs, "office_id"),
          rs.getString("office_name"),
          offsetDateTime(rs, "created_on_utc"),
          offsetDateTime(rs, "completed_on_utc"),
          fundingType == BaseTellerFundingType.CASH ? lookup.apply(openingId) : List.of(),
          fundingType == BaseTellerFundingType.CHECK
              ? new BaseTellerCheckData(
                  rs.getString("check_type"),
                  rs.getString("check_bank"),
                  rs.getString("check_number"),
                  rs.getString("check_account_number"),
                  rs.getString("check_routing_code"))
              : null);
    }
  }
}
