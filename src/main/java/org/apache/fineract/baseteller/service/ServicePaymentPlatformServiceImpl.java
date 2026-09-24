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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.BaseTellerCustomerData;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.ServicePaymentCommissionType;
import org.apache.fineract.baseteller.data.ServicePaymentDenominationData;
import org.apache.fineract.baseteller.data.ServicePaymentPayerType;
import org.apache.fineract.baseteller.data.ServicePaymentQuoteData;
import org.apache.fineract.baseteller.data.ServicePaymentQuoteRequest;
import org.apache.fineract.baseteller.data.ServicePaymentReceiptData;
import org.apache.fineract.baseteller.data.ServicePaymentRequest;
import org.apache.fineract.baseteller.data.ServicePaymentServiceData;
import org.apache.fineract.baseteller.validation.ServicePaymentValidator;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.teller.data.CashierData;
import org.apache.fineract.organisation.teller.data.TellerData;
import org.apache.fineract.organisation.teller.service.TellerManagementReadPlatformService;
import org.apache.fineract.portfolio.paymenttype.data.PaymentTypeData;
import org.apache.fineract.portfolio.paymenttype.service.PaymentTypeReadService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ServicePaymentPlatformServiceImpl
    implements ServicePaymentReadPlatformService, ServicePaymentWritePlatformService {

  private static final String RESOURCE = "BASE_TELLER_SERVICE_PAYMENT";
  private static final int CASH_IN = 103;
  private static final Gson GSON = new Gson();

  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;
  private final PlatformSecurityContext context;
  private final ServicePaymentValidator validator;
  private final PaymentTypeReadService paymentTypeReadService;
  private final TellerManagementReadPlatformService tellerService;
  private final PortfolioCommandSourceWritePlatformService commandService;

  @Override
  @Transactional(readOnly = true)
  public List<ServicePaymentServiceData> services() {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return jdbcTemplate.query(
        "SELECT"
            + " id,code,name,active,currency_code,commission_type,commission_value,commission_vat_rate"
            + " FROM m_service_payment_service WHERE active=true ORDER BY name,id",
        (rs, row) ->
            new ServicePaymentServiceData(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("active"),
                rs.getString("currency_code"),
                ServicePaymentCommissionType.valueOf(rs.getString("commission_type")),
                rs.getBigDecimal("commission_value"),
                rs.getBigDecimal("commission_vat_rate"),
                denominations(rs.getString("currency_code"))));
  }

  @Override
  @Transactional(readOnly = true)
  public BaseTellerCustomerData client(final Long clientId) {
    final AppUser user = context.authenticatedUser();
    user.validateHasReadPermission(RESOURCE);
    return resolveClient(clientId, user);
  }

  @Override
  @Transactional(readOnly = true)
  public ServicePaymentQuoteData quote(final ServicePaymentQuoteRequest request) {
    final AppUser user = context.authenticatedUser();
    user.validateHasReadPermission(RESOURCE);
    validator.validateQuote(request);
    return calculate(
            request.payerType(),
            request.clientId(),
            request.payerName(),
            request.serviceId(),
            request.serviceReference(),
            request.baseAmount(),
            request.currencyCode(),
            user)
        .quote();
  }

  @Override
  @Transactional
  public ServicePaymentReceiptData create(final ServicePaymentRequest request) {
    final AppUser user = context.authenticatedUser();
    user.validateHasCreatePermission(RESOURCE);
    validator.validatePayment(request);
    final LocalDate businessDate = parseBusinessDate(request.businessDate());
    validateBusinessDate(businessDate);
    final String fingerprint = hash(GSON.toJson(request));
    final Existing existing = existing(request.idempotencyKey());
    if (existing != null) {
      validateFingerprint(existing, fingerprint);
      return receipt(existing.id(), user);
    }

    final Calculation calculation =
        calculate(
            request.payerType(),
            request.clientId(),
            request.payerName(),
            request.serviceId(),
            request.serviceReference(),
            request.baseAmount(),
            request.currencyCode(),
            user);
    validateCashPaymentType(request.paymentTypeId());
    final CashierData cashier = resolveCashier(user);
    final List<BaseTellerDenominationData> tender =
        authoritativeTender(
            request.denominations(),
            calculation.service().currencyCode(),
            calculation.decimalPlaces());
    final ServicePaymentCashAmounts cash =
        ServicePaymentCalculator.calculateCash(calculation.quote().totalToPay(), tender);
    validateAccounting(calculation.service(), calculation.quote());
    final String receiptNumber = "SP-" + businessDate + "-" + shortHash(request.idempotencyKey());

    try {
      insertPayment(
          request,
          businessDate,
          calculation,
          user,
          cashier,
          fingerprint,
          receiptNumber,
          cash.amountReceived(),
          cash.change());
    } catch (DuplicateKeyException duplicate) {
      final Existing concurrent = existing(request.idempotencyKey());
      if (concurrent != null) {
        validateFingerprint(concurrent, fingerprint);
        return receipt(concurrent.id(), user);
      }
      throw duplicate;
    }
    final Long id = existing(request.idempotencyKey()).id();
    insertTender(id, tender);
    final Long cashierTransactionId =
        insertCashierTransaction(
            id,
            cashier,
            calculation.quote().totalToPay(),
            businessDate,
            calculation.service().currencyCode(),
            receiptNumber);
    final String accountingTransactionId =
        postAccounting(
            request.idempotencyKey(),
            receiptNumber,
            calculation,
            user.getOffice().getId(),
            businessDate);
    jdbcTemplate.update(
        "UPDATE m_service_payment SET cashier_transaction_id=?,accounting_transaction_id=?,"
            + "status='COMPLETED',completed_on_utc=CURRENT_TIMESTAMP WHERE id=?",
        cashierTransactionId,
        accountingTransactionId,
        id);
    return receipt(id, user);
  }

  @Override
  @Transactional(readOnly = true)
  public ServicePaymentReceiptData receipt(final Long transactionId) {
    final AppUser user = context.authenticatedUser();
    user.validateHasReadPermission(RESOURCE);
    return receipt(transactionId, user);
  }

  private Calculation calculate(
      final ServicePaymentPayerType payerType,
      final Long clientId,
      final String payerName,
      final Long serviceId,
      final String reference,
      final BigDecimal baseAmount,
      final String currencyCode,
      final AppUser user) {
    final ServiceConfiguration service = service(serviceId);
    if (!service.active()) throw invalid("service.inactive", "Service is inactive.");
    if (!service.currencyCode().equalsIgnoreCase(currencyCode)) {
      throw invalid(
          "currency.mismatch", "currencyCode does not match the configured service currency.");
    }
    final int decimalPlaces = currencyDecimalPlaces(service.currencyCode());
    final Payer payer =
        payerType == ServicePaymentPayerType.CLIENT
            ? clientPayer(resolveClient(clientId, user))
            : new Payer(null, null, normalizeName(payerName));
    final ServicePaymentAmounts amounts =
        ServicePaymentCalculator.calculate(
            baseAmount,
            service.commissionType(),
            service.commissionValue(),
            service.vatRate(),
            decimalPlaces);
    final ServicePaymentQuoteData quote =
        new ServicePaymentQuoteData(
            payerType,
            payer.clientId(),
            payer.accountNo(),
            payer.name(),
            service.id(),
            service.code(),
            service.name(),
            reference.trim(),
            amounts.baseAmount(),
            amounts.commission(),
            amounts.commissionVat(),
            amounts.totalToPay(),
            service.currencyCode(),
            DateUtils.getBusinessLocalDate());
    return new Calculation(service, quote, decimalPlaces);
  }

  private ServiceConfiguration service(final Long id) {
    final List<ServiceConfiguration> rows =
        jdbcTemplate.query(
            "SELECT"
                + " id,code,name,active,currency_code,commission_type,commission_value,commission_vat_rate,"
                + "cash_gl_account_id,settlement_gl_account_id,commission_gl_account_id,vat_gl_account_id"
                + " FROM m_service_payment_service WHERE id=?",
            (rs, row) ->
                new ServiceConfiguration(
                    rs.getLong("id"),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getBoolean("active"),
                    rs.getString("currency_code"),
                    ServicePaymentCommissionType.valueOf(rs.getString("commission_type")),
                    rs.getBigDecimal("commission_value"),
                    rs.getBigDecimal("commission_vat_rate"),
                    nullableLong(rs.getObject("cash_gl_account_id")),
                    nullableLong(rs.getObject("settlement_gl_account_id")),
                    nullableLong(rs.getObject("commission_gl_account_id")),
                    nullableLong(rs.getObject("vat_gl_account_id"))),
            id);
    if (rows.isEmpty()) throw invalid("service.not.found", "Service was not found.");
    return rows.get(0);
  }

  private BaseTellerCustomerData resolveClient(final Long id, final AppUser user) {
    if (id == null || id <= 0) throw invalid("client.id.invalid", "A valid clientId is required.");
    final List<BaseTellerCustomerData> rows =
        namedJdbcTemplate.query(
            "SELECT c.id,c.account_no,c.external_id,c.display_name,c.office_id,o.name"
                + " office_name,c.status_enum FROM m_client c JOIN m_office o ON o.id=c.office_id"
                + " WHERE c.id=:id AND o.hierarchy LIKE :hierarchy",
            Map.of("id", id, "hierarchy", user.getOffice().getHierarchy() + "%"),
            (rs, row) ->
                new BaseTellerCustomerData(
                    rs.getLong("id"),
                    rs.getString("account_no"),
                    rs.getString("external_id"),
                    rs.getString("display_name"),
                    rs.getLong("office_id"),
                    rs.getString("office_name"),
                    String.valueOf(rs.getInt("status_enum"))));
    if (rows.isEmpty())
      throw invalid("client.not.found", "Client was not found in the authorized office hierarchy.");
    return rows.get(0);
  }

  private List<BaseTellerDenominationData> authoritativeTender(
      final List<BaseTellerDenominationData> requested,
      final String currency,
      final int decimalPlaces) {
    final List<BaseTellerDenominationData> result = new ArrayList<>();
    for (BaseTellerDenominationData item : requested) {
      final List<BigDecimal> values =
          jdbcTemplate.query(
              "SELECT value FROM m_service_payment_denomination WHERE LOWER(currency_code)=LOWER(?)"
                  + " AND LOWER(identifier)=LOWER(?) AND active=true",
              (rs, row) -> rs.getBigDecimal(1),
              currency,
              item.denominationId().trim());
      if (values.isEmpty())
        throw invalid(
            "denomination.unsupported", "A denomination is not active for the service currency.");
      final BigDecimal authoritativeValue = values.get(0);
      if (authoritativeValue.signum() <= 0) {
        throw invalid(
            "denomination.configuration.invalid",
            "Configured denomination must be greater than zero.");
      }
      if (authoritativeValue.stripTrailingZeros().scale() > decimalPlaces) {
        throw invalid(
            "denomination.precision.invalid",
            "Configured denomination exceeds currency precision.");
      }
      if (item.value() != null && item.value().compareTo(authoritativeValue) != 0) {
        throw invalid(
            "denomination.value.mismatch",
            "Submitted denomination value does not match configuration.");
      }
      result.add(
          new BaseTellerDenominationData(
              item.denominationId().trim(), authoritativeValue, item.quantity()));
    }
    return result;
  }

  private void validateCashPaymentType(final Long id) {
    final PaymentTypeData paymentType = paymentTypeReadService.retrieveOne(id);
    if (!Boolean.TRUE.equals(paymentType.getIsCashPayment())) {
      throw invalid(
          "payment.type.unsupported", "Service payments support cash payment types only.");
    }
  }

  private CashierData resolveCashier(final AppUser user) {
    if (user.getStaffId() == null || user.getOffice() == null) {
      throw invalid(
          "cashier.context.required", "Authenticated user must be linked to staff and office.");
    }
    final LocalDate date = DateUtils.getBusinessLocalDate();
    final Collection<TellerData> tellers = tellerService.getTellers(user.getOffice().getId());
    for (TellerData teller : tellers) {
      for (CashierData cashier : tellerService.getCashiersForTeller(teller.getId(), date, date)) {
        if (user.getStaffId().equals(cashier.getStaffId())) return cashier;
      }
    }
    throw invalid("cashier.not.allocated", "Authenticated user has no active cashier allocation.");
  }

  private void insertPayment(
      final ServicePaymentRequest request,
      final LocalDate businessDate,
      final Calculation calculation,
      final AppUser user,
      final CashierData cashier,
      final String fingerprint,
      final String receipt,
      final BigDecimal received,
      final BigDecimal change) {
    final ServicePaymentQuoteData q = calculation.quote();
    jdbcTemplate.update(
        "INSERT INTO m_service_payment"
            + " (idempotency_key,request_fingerprint,receipt_number,business_date,"
            + "service_id,service_code,service_name,service_reference,payer_type,client_id,client_account_no,"
            + "payer_name,currency_code,base_amount,commission_amount,commission_vat_amount,total_to_pay,"
            + "amount_received,change_amount,payment_type_id,office_id,teller_id,cashier_id,operator_id,status)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        request.idempotencyKey(),
        fingerprint,
        receipt,
        businessDate,
        q.serviceId(),
        q.serviceCode(),
        q.serviceName(),
        q.serviceReference(),
        q.payerType().name(),
        q.clientId(),
        q.clientAccountNo(),
        q.payerName(),
        q.currencyCode(),
        q.baseAmount(),
        q.commission(),
        q.commissionVat(),
        q.totalToPay(),
        received,
        change,
        request.paymentTypeId(),
        cashier.getOfficeId(),
        cashier.getTellerId(),
        cashier.getId(),
        user.getId(),
        "PROCESSING");
  }

  private void insertTender(final Long id, final List<BaseTellerDenominationData> tender) {
    for (BaseTellerDenominationData item : tender) {
      jdbcTemplate.update(
          "INSERT INTO m_service_payment_cash_detail (service_payment_id,denomination_identifier,"
              + "denomination_value,quantity,line_total) VALUES (?,?,?,?,?)",
          id,
          item.denominationId(),
          item.value(),
          item.quantity(),
          item.value().multiply(BigDecimal.valueOf(item.quantity())));
    }
  }

  private Long insertCashierTransaction(
      final Long id,
      final CashierData cashier,
      final BigDecimal amount,
      final LocalDate date,
      final String currency,
      final String receipt) {
    jdbcTemplate.update(
        "INSERT INTO m_cashier_transactions"
            + " (cashier_id,txn_type,txn_date,txn_amount,txn_note,entity_type,entity_id,currency_code)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        cashier.getId(),
        CASH_IN,
        date,
        amount,
        "Service payment " + receipt,
        RESOURCE,
        id,
        currency);
    return jdbcTemplate.queryForObject(
        "SELECT MAX(id) FROM m_cashier_transactions WHERE cashier_id=? AND txn_type=? AND"
            + " entity_type=? AND entity_id=?",
        Long.class,
        cashier.getId(),
        CASH_IN,
        RESOURCE,
        id);
  }

  private String postAccounting(
      final String idempotencyKey,
      final String receipt,
      final Calculation calculation,
      final Long officeId,
      final LocalDate date) {
    final ServiceConfiguration service = calculation.service();
    final ServicePaymentQuoteData quote = calculation.quote();
    final List<Map<String, Object>> credits = new ArrayList<>();
    credits.add(entry(service.settlementGlAccountId(), quote.baseAmount()));
    if (quote.commission().signum() > 0)
      credits.add(entry(service.commissionGlAccountId(), quote.commission()));
    if (quote.commissionVat().signum() > 0)
      credits.add(entry(service.vatGlAccountId(), quote.commissionVat()));
    final Map<String, Object> body = new HashMap<>();
    body.put("officeId", officeId);
    body.put("transactionDate", date.toString());
    body.put("currencyCode", quote.currencyCode());
    body.put("comments", "Service payment " + receipt);
    body.put("locale", "en");
    body.put("dateFormat", "yyyy-MM-dd");
    body.put("debits", List.of(entry(service.cashGlAccountId(), quote.totalToPay())));
    body.put("credits", credits);
    final CommandWrapper command =
        new CommandWrapperBuilder()
            .createJournalEntry()
            .withJson(GSON.toJson(body))
            .build(idempotencyKey + ":accounting");
    final CommandProcessingResult result = commandService.logCommandSource(command);
    return result.getTransactionId() != null
        ? result.getTransactionId()
        : result.getResourceId() == null ? null : String.valueOf(result.getResourceId());
  }

  private ServicePaymentReceiptData receipt(final Long id, final AppUser user) {
    final List<ServicePaymentReceiptData> rows =
        namedJdbcTemplate.query(
            "SELECT p.*,o.name office_name,t.name teller_name,s.display_name"
                + " cashier_name,u.username operator_name FROM m_service_payment p JOIN m_office o"
                + " ON o.id=p.office_id JOIN m_tellers t ON t.id=p.teller_id JOIN m_cashiers c ON"
                + " c.id=p.cashier_id JOIN m_staff s ON s.id=c.staff_id JOIN m_appuser u ON"
                + " u.id=p.operator_id WHERE p.id=:id AND o.hierarchy LIKE :hierarchy",
            Map.of("id", id, "hierarchy", user.getOffice().getHierarchy() + "%"),
            (rs, row) ->
                new ServicePaymentReceiptData(
                    rs.getLong("id"),
                    rs.getString("receipt_number"),
                    rs.getString("status"),
                    rs.getObject("business_date", LocalDate.class),
                    rs.getLong("office_id"),
                    rs.getString("office_name"),
                    rs.getLong("teller_id"),
                    rs.getString("teller_name"),
                    rs.getLong("cashier_id"),
                    rs.getString("cashier_name"),
                    rs.getLong("operator_id"),
                    rs.getString("operator_name"),
                    ServicePaymentPayerType.valueOf(rs.getString("payer_type")),
                    nullableLong(rs.getObject("client_id")),
                    rs.getString("client_account_no"),
                    rs.getString("payer_name"),
                    rs.getLong("service_id"),
                    rs.getString("service_code"),
                    rs.getString("service_name"),
                    rs.getString("service_reference"),
                    rs.getBigDecimal("base_amount"),
                    rs.getBigDecimal("commission_amount"),
                    rs.getBigDecimal("commission_vat_amount"),
                    rs.getBigDecimal("total_to_pay"),
                    rs.getBigDecimal("amount_received"),
                    rs.getBigDecimal("change_amount"),
                    rs.getString("currency_code"),
                    nullableLong(rs.getObject("cashier_transaction_id")),
                    rs.getString("accounting_transaction_id"),
                    offset(rs.getTimestamp("created_on_utc")),
                    offset(rs.getTimestamp("completed_on_utc")),
                    tender(id)));
    if (rows.isEmpty())
      throw new PlatformDataIntegrityException(
          "error.msg.base.teller.service.payment.not.found", "Service payment was not found.");
    return rows.get(0);
  }

  private List<BaseTellerDenominationData> tender(final Long id) {
    return jdbcTemplate.query(
        "SELECT denomination_identifier,denomination_value,quantity FROM"
            + " m_service_payment_cash_detail WHERE service_payment_id=? ORDER BY"
            + " denomination_value DESC",
        (rs, row) ->
            new BaseTellerDenominationData(rs.getString(1), rs.getBigDecimal(2), rs.getLong(3)),
        id);
  }

  private List<ServicePaymentDenominationData> denominations(final String currency) {
    return jdbcTemplate.query(
        "SELECT identifier,value,denomination_type FROM m_service_payment_denomination"
            + " WHERE LOWER(currency_code)=LOWER(?) AND active=true ORDER BY value DESC",
        (rs, row) ->
            new ServicePaymentDenominationData(
                rs.getString(1), rs.getBigDecimal(2), rs.getString(3)),
        currency);
  }

  private int currencyDecimalPlaces(final String currency) {
    final List<Integer> rows =
        jdbcTemplate.query(
            "SELECT decimal_places FROM m_currency WHERE LOWER(code)=LOWER(?)",
            (rs, row) -> rs.getInt(1),
            currency);
    if (rows.isEmpty())
      throw invalid("currency.unsupported", "Currency is not enabled in Fineract.");
    return rows.get(0);
  }

  private void validateAccounting(
      final ServiceConfiguration service, final ServicePaymentQuoteData quote) {
    if (service.cashGlAccountId() == null
        || service.settlementGlAccountId() == null
        || (quote.commission().signum() > 0 && service.commissionGlAccountId() == null)
        || (quote.commissionVat().signum() > 0 && service.vatGlAccountId() == null)) {
      throw invalid(
          "accounting.configuration.missing",
          "The service has incomplete GL accounting configuration.");
    }
  }

  private void validateBusinessDate(final LocalDate date) {
    if (date == null || !DateUtils.getBusinessLocalDate().equals(date)) {
      throw invalid(
          "business.date.invalid", "businessDate must equal the current Fineract business date.");
    }
  }

  private LocalDate parseBusinessDate(final String value) {
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException invalidDate) {
      throw invalid("business.date.invalid", "businessDate must use ISO yyyy-MM-dd format.");
    }
  }

  private Existing existing(final String key) {
    if (key == null) return null;
    final List<Existing> rows =
        jdbcTemplate.query(
            "SELECT id,request_fingerprint FROM m_service_payment WHERE idempotency_key=?",
            (rs, row) -> new Existing(rs.getLong(1), rs.getString(2)),
            key);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private void validateFingerprint(final Existing existing, final String fingerprint) {
    if (!existing.fingerprint().equals(fingerprint)) {
      throw invalid(
          "idempotency.conflict", "idempotencyKey was already used for a different request.");
    }
  }

  private static Map<String, Object> entry(final Long accountId, final BigDecimal amount) {
    return Map.of("glAccountId", accountId, "amount", amount);
  }

  private static Payer clientPayer(final BaseTellerCustomerData client) {
    return new Payer(client.clientId(), client.accountNo(), client.displayName());
  }

  private static String normalizeName(final String value) {
    return StringUtils.normalizeSpace(value).trim();
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
        "error.msg.base.teller.service.payment." + code, message);
  }

  private record Existing(Long id, String fingerprint) {}

  private record Payer(Long clientId, String accountNo, String name) {}

  private record Calculation(
      ServiceConfiguration service, ServicePaymentQuoteData quote, int decimalPlaces) {}

  private record ServiceConfiguration(
      Long id,
      String code,
      String name,
      boolean active,
      String currencyCode,
      ServicePaymentCommissionType commissionType,
      BigDecimal commissionValue,
      BigDecimal vatRate,
      Long cashGlAccountId,
      Long settlementGlAccountId,
      Long commissionGlAccountId,
      Long vatGlAccountId) {}
}
