package org.apache.fineract.baseteller.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.BaseTellerCustomerData;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.CreditPaymentBankData;
import org.apache.fineract.baseteller.data.CreditPaymentCheckClassification;
import org.apache.fineract.baseteller.data.CreditPaymentCheckData;
import org.apache.fineract.baseteller.data.CreditPaymentCheckRequest;
import org.apache.fineract.baseteller.data.CreditPaymentContextData;
import org.apache.fineract.baseteller.data.CreditPaymentDenominationData;
import org.apache.fineract.baseteller.data.CreditPaymentLoanData;
import org.apache.fineract.baseteller.data.CreditPaymentMethod;
import org.apache.fineract.baseteller.data.CreditPaymentPreviewData;
import org.apache.fineract.baseteller.data.CreditPaymentReceiptData;
import org.apache.fineract.baseteller.data.CreditPaymentRequest;
import org.apache.fineract.baseteller.data.CreditPaymentStatus;
import org.apache.fineract.baseteller.data.CreditPaymentTransitionRequest;
import org.apache.fineract.baseteller.validation.CreditPaymentValidator;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.teller.data.CashierData;
import org.apache.fineract.organisation.teller.data.TellerData;
import org.apache.fineract.organisation.teller.service.TellerManagementReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.data.LoanAccountData;
import org.apache.fineract.portfolio.loanaccount.data.LoanSummaryData;
import org.apache.fineract.portfolio.loanaccount.data.LoanTransactionData;
import org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformService;
import org.apache.fineract.portfolio.paymenttype.data.PaymentTypeData;
import org.apache.fineract.portfolio.paymenttype.service.PaymentTypeReadService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditPaymentPlatformServiceImpl
    implements CreditPaymentReadPlatformService, CreditPaymentWritePlatformService {

  static final String RESOURCE = "BASE_TELLER_CREDIT_PAYMENT";
  static final String CLEAR_PERMISSION = "AUTHORIZE_BASE_TELLER_CHECK_CLEARING";
  static final String RETURN_PERMISSION = "RETURN_BASE_TELLER_CREDIT_PAYMENT_CHECK";
  private static final int CASHIER_TXN_CASH_IN = 103;
  private static final Gson GSON = new Gson();

  private final JdbcTemplate jdbcTemplate;
  private final PlatformSecurityContext context;
  private final CreditPaymentValidator validator;
  private final BaseTellerReadPlatformService baseTellerReadPlatformService;
  private final LoanReadPlatformService loanReadPlatformService;
  private final PaymentTypeReadService paymentTypeReadService;
  private final TellerManagementReadPlatformService tellerManagementReadPlatformService;
  private final PortfolioCommandSourceWritePlatformService commandService;

  @Override
  public CreditPaymentContextData context() {
    final AppUser user = readableUser();
    final CashierData cashier = resolveCashier(user);
    final List<CreditPaymentDenominationData> denominations =
        jdbcTemplate.query(
            "SELECT identifier,currency_code,value,denomination_type FROM m_service_payment_denomination"
                + " WHERE active=true ORDER BY currency_code,value DESC",
            (rs, row) ->
                new CreditPaymentDenominationData(
                    rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4)));
    final List<CreditPaymentBankData> banks =
        jdbcTemplate.query(
            "SELECT id,code,name FROM m_base_teller_bank WHERE active=true ORDER BY name",
            (rs, row) -> new CreditPaymentBankData(rs.getLong(1), rs.getString(2), rs.getString(3)));
    return new CreditPaymentContextData(
        DateUtils.getBusinessLocalDate(),
        user.getOffice().getId(),
        cashier.getTellerId(),
        cashier.getId(),
        denominations,
        banks,
        paymentTypeReadService.retrieveAllPaymentTypes());
  }

  @Override
  public List<BaseTellerCustomerData> searchCustomers(
      final Long clientId, final String accountNo, final String query, final Integer limit) {
    readableUser();
    return baseTellerReadPlatformService.searchCustomers(clientId, accountNo, query, limit);
  }

  @Override
  public List<CreditPaymentLoanData> loans(final Long clientId) {
    final AppUser user = readableUser();
    validateClientAccess(clientId, user);
    final List<Long> ids =
        jdbcTemplate.query(
            "SELECT id FROM m_loan WHERE client_id=? ORDER BY id DESC",
            (rs, row) -> rs.getLong(1),
            clientId);
    return ids.stream().map(id -> loanData(id, false)).toList();
  }

  @Override
  public CreditPaymentLoanData loan(final Long loanId) {
    final AppUser user = readableUser();
    final LoanAccountData loan = loanReadPlatformService.retrieveOne(loanId);
    validateLoanAccess(loan, user);
    return loanData(loan, true);
  }

  @Override
  public CreditPaymentReceiptData receipt(final String receiptNumber) {
    final AppUser user = readableUser();
    user.validateHasPermissionTo("REPRINT_BASE_TELLER_CREDIT_PAYMENT");
    final List<CreditPaymentReceiptData> rows =
        jdbcTemplate.query(receiptSql() + " WHERE p.receipt_number=?", this::mapReceipt, receiptNumber);
    if (rows.isEmpty()) throw invalid("receipt.not.found", "Credit payment receipt was not found.");
    validateOffice(rows.get(0).officeId(), user);
    return rows.get(0);
  }

  @Override
  public CreditPaymentPreviewData preview(final CreditPaymentRequest request) {
    final AppUser user = readableUser();
    validator.validate(request, false);
    final Prepared prepared = prepare(request, user);
    final LoanSummaryData summary = prepared.loan().getSummary();
    final LoanTransactionData template =
        loanReadPlatformService.retrieveLoanTransactionTemplate(prepared.loan().getId());
    return new CreditPaymentPreviewData(
        prepared.loan().getId(),
        request.paymentMethod(),
        request.check() == null ? null : request.check().classification(),
        prepared.loan().getCurrency().getCode(),
        DateUtils.getBusinessLocalDate(),
        request.amount(),
        prepared.tenderAmount(),
        prepared.changeAmount(),
        value(summary == null ? null : summary.getPrincipalOutstanding()),
        value(summary == null ? null : summary.getInterestOutstanding()),
        value(summary == null ? null : summary.getFeeChargesOutstanding()),
        value(summary == null ? null : summary.getPenaltyChargesOutstanding()),
        null,
        template,
        prepared.denominations(),
        request.paymentMethod() == CreditPaymentMethod.CASH
            || request.check().classification() == CreditPaymentCheckClassification.CLEARED_FUNDS);
  }

  @Override
  @Transactional
  public CreditPaymentReceiptData create(final CreditPaymentRequest request) {
    final AppUser user = context.authenticatedUser();
    user.validateHasCreatePermission(RESOURCE);
    user.validateHasPermissionTo("REPAYMENT_LOAN");
    validator.validate(request, true);
    final String fingerprint = hash(GSON.toJson(request));
    final Existing existing = existing(request.idempotencyKey());
    if (existing != null) return existingResult(existing, fingerprint);

    if (request.paymentMethod() == CreditPaymentMethod.CHECK
        && request.check().classification() == CreditPaymentCheckClassification.CLEARED_FUNDS) {
      user.validateHasPermissionTo(CLEAR_PERMISSION);
    }
    final Prepared prepared = prepare(request, user);
    final String receiptNumber =
        "CP-" + DateUtils.getBusinessLocalDate() + "-" + shortHash(request.idempotencyKey());
    final CreditPaymentStatus initialStatus =
        request.paymentMethod() == CreditPaymentMethod.CHECK
                && request.check().classification()
                    == CreditPaymentCheckClassification.SUBJECT_TO_COLLECTION
            ? CreditPaymentStatus.PENDING_COLLECTION
            : CreditPaymentStatus.IN_PROGRESS;
    final long paymentId;
    try {
      paymentId = insertPayment(request, prepared, user, receiptNumber, fingerprint, initialStatus);
      if (request.paymentMethod() == CreditPaymentMethod.CASH) {
        insertDenominations(paymentId, prepared.denominations());
      } else {
        insertCheck(paymentId, request.loanId(), request.check(), prepared.bank(), user, initialStatus);
      }
    } catch (DuplicateKeyException duplicate) {
      final Existing concurrent = existing(request.idempotencyKey());
      if (concurrent != null) return existingResult(concurrent, fingerprint);
      throw duplicate;
    }

    if (initialStatus == CreditPaymentStatus.PENDING_COLLECTION) {
      return receiptByPaymentId(paymentId, user);
    }
    final Long loanTransactionId = postNativeRepayment(request, prepared.loan());
    final NativeAllocation allocation = nativeAllocation(loanTransactionId);
    final Long cashierTransactionId =
        request.paymentMethod() == CreditPaymentMethod.CASH
            ? insertCashierIn(paymentId, request.amount(), prepared.loan().getCurrency().getCode(), user, prepared.cashier(), request.note())
            : null;
    final CreditPaymentStatus completed =
        request.paymentMethod() == CreditPaymentMethod.CASH
            ? CreditPaymentStatus.COMPLETED
            : CreditPaymentStatus.CLEARED;
    jdbcTemplate.update(
        "UPDATE m_base_teller_credit_payment SET status=?,loan_transaction_id=?,"
            + " cashier_transaction_id=?,principal_portion=?,interest_portion=?,fee_portion=?,"
            + " penalty_portion=?,overpayment_portion=?,completed_on_utc=CURRENT_TIMESTAMP WHERE id=?",
        completed.name(), loanTransactionId, cashierTransactionId, allocation.principal(),
        allocation.interest(), allocation.fee(), allocation.penalty(), allocation.overpayment(), paymentId);
    if (request.paymentMethod() == CreditPaymentMethod.CHECK) {
      jdbcTemplate.update(
          "UPDATE m_base_teller_credit_payment_check SET status=?,cleared_by=?,"
              + " cleared_on_utc=CURRENT_TIMESTAMP WHERE credit_payment_id=?",
          CreditPaymentStatus.CLEARED.name(), user.getId(), paymentId);
    }
    return receiptByPaymentId(paymentId, user);
  }

  @Override
  @Transactional
  public CreditPaymentReceiptData clear(
      final Long checkId, final CreditPaymentTransitionRequest request) {
    final AppUser user = context.authenticatedUser();
    user.validateHasPermissionTo(CLEAR_PERMISSION);
    user.validateHasPermissionTo("REPAYMENT_LOAN");
    validator.validateTransition(request, false);
    final String fingerprint = hash(GSON.toJson(request));
    final LockedCheck check = lockCheck(checkId, user);
    if (check.clearKey() != null) {
      if (!check.clearKey().equals(request.idempotencyKey())
          || !fingerprint.equals(check.clearFingerprint())) {
        throw invalid("check.clear.conflict", "Check was already cleared by another request.");
      }
      return receiptByPaymentId(check.paymentId(), user);
    }
    if (check.status() != CreditPaymentStatus.PENDING_COLLECTION) {
      throw invalid("check.not.pending", "Only a pending-collection check can be cleared.");
    }
    final LoanAccountData loan = validatePayableLoan(check.loanId(), check.clientId(), check.currency(), user);
    final CreditPaymentRequest repayment =
        new CreditPaymentRequest(
            request.idempotencyKey(), check.clientId(), check.loanId(), CreditPaymentMethod.CHECK,
            check.amount(), check.currency(), check.paymentTypeId(), request.transactionDate(),
            request.dateFormat(), request.locale(), null, List.of(), check.request());
    final Long transactionId = postNativeRepayment(repayment, loan);
    final NativeAllocation allocation = nativeAllocation(transactionId);
    jdbcTemplate.update(
        "UPDATE m_base_teller_credit_payment_check SET status=?,clear_idempotency_key=?,"
            + " clear_request_fingerprint=?,cleared_by=?,cleared_on_utc=CURRENT_TIMESTAMP WHERE id=?",
        CreditPaymentStatus.CLEARED.name(), request.idempotencyKey(), fingerprint, user.getId(), checkId);
    jdbcTemplate.update(
        "UPDATE m_base_teller_credit_payment SET status=?,loan_transaction_id=?,"
            + " principal_portion=?,interest_portion=?,fee_portion=?,penalty_portion=?,"
            + " overpayment_portion=?,completed_on_utc=CURRENT_TIMESTAMP WHERE id=?",
        CreditPaymentStatus.CLEARED.name(), transactionId, allocation.principal(),
        allocation.interest(), allocation.fee(), allocation.penalty(), allocation.overpayment(),
        check.paymentId());
    return receiptByPaymentId(check.paymentId(), user);
  }

  @Override
  @Transactional
  public CreditPaymentReceiptData returnCheck(
      final Long checkId, final CreditPaymentTransitionRequest request) {
    final AppUser user = context.authenticatedUser();
    user.validateHasPermissionTo(RETURN_PERMISSION);
    validator.validateTransition(request, true);
    final String fingerprint = hash(GSON.toJson(request));
    final LockedCheck check = lockCheck(checkId, user);
    if (check.returnKey() != null) {
      if (!check.returnKey().equals(request.idempotencyKey())
          || !fingerprint.equals(check.returnFingerprint())) {
        throw invalid("check.return.conflict", "Check was already returned by another request.");
      }
      return receiptByPaymentId(check.paymentId(), user);
    }
    if (check.status() != CreditPaymentStatus.PENDING_COLLECTION) {
      throw invalid("check.not.returnable", "Only an uncleared pending check can be returned safely.");
    }
    jdbcTemplate.update(
        "UPDATE m_base_teller_credit_payment_check SET status=?,return_idempotency_key=?,"
            + " return_request_fingerprint=?,returned_by=?,returned_on_utc=CURRENT_TIMESTAMP,"
            + " return_reason=? WHERE id=?",
        CreditPaymentStatus.RETURNED.name(), request.idempotencyKey(), fingerprint, user.getId(), request.reason(), checkId);
    jdbcTemplate.update(
        "UPDATE m_base_teller_credit_payment SET status=?,completed_on_utc=CURRENT_TIMESTAMP WHERE id=?",
        CreditPaymentStatus.RETURNED.name(), check.paymentId());
    jdbcTemplate.update(
        "INSERT INTO m_base_teller_returned_check"
            + " (deposit_id,deposit_check_detail_id,client_id,savings_account_id,check_type,"
            + " check_bank,check_number,amount,currency_code,office_id,teller_id,cashier_id,"
            + " returned_on_date,return_reason,status,origin_type,credit_payment_id,"
            + " credit_payment_check_id,loan_id) VALUES (NULL,NULL,?,NULL,"
            + "?,?,?,?,?,?,?,?,?,?,?,'LOAN',?,?,?)",
        check.clientId(), check.checkType(), check.bankName(), check.checkNumber(), check.amount(),
        check.currency(), check.officeId(), check.tellerId(), check.cashierId(), DateUtils.getBusinessLocalDate(),
        request.reason(), "RETURNED", check.paymentId(), check.id(), check.loanId());
    return receiptByPaymentId(check.paymentId(), user);
  }

  private Prepared prepare(final CreditPaymentRequest request, final AppUser user) {
    final LoanAccountData loan = validatePayableLoan(request.loanId(), request.clientId(), request.currencyCode(), user);
    final PaymentTypeData paymentType = paymentTypeReadService.retrieveOne(request.paymentTypeId());
    if ((request.paymentMethod() == CreditPaymentMethod.CASH) != Boolean.TRUE.equals(paymentType.getIsCashPayment())) {
      throw invalid("payment.type.mismatch", "Payment type does not match the selected payment method.");
    }
    final CashierData cashier = resolveCashier(user);
    if (request.paymentMethod() == CreditPaymentMethod.CHECK) {
      final CreditPaymentBankData bank = activeBank(request.check().bankId());
      return new Prepared(loan, cashier, List.of(), request.amount(), BigDecimal.ZERO, bank);
    }
    final List<BaseTellerDenominationData> denominations = authoritativeDenominations(request.denominations(), request.currencyCode(), loan.getCurrency().getDecimalPlaces());
    final BigDecimal tender = denominations.stream().map(d -> d.value().multiply(BigDecimal.valueOf(d.quantity()))).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (tender.compareTo(request.amount()) < 0) throw invalid("cash.insufficient", "Cash tender is less than the payment amount.");
    return new Prepared(loan, cashier, denominations, tender, tender.subtract(request.amount()), null);
  }

  private LoanAccountData validatePayableLoan(final Long loanId, final Long clientId, final String currency, final AppUser user) {
    final LoanAccountData loan = loanReadPlatformService.retrieveOne(loanId);
    validateLoanAccess(loan, user);
    if (!clientId.equals(loan.getClientId())) throw invalid("loan.client.mismatch", "Loan does not belong to the selected client.");
    if (!loan.isActive()) throw invalid("loan.not.payable", "Only an active loan can receive a repayment.");
    if (loan.getCurrency() == null || !StringUtils.equalsIgnoreCase(currency, loan.getCurrency().getCode())) {
      throw invalid("currency.mismatch", "Payment currency must match the loan currency.");
    }
    return loan;
  }

  private void validateLoanAccess(final LoanAccountData loan, final AppUser user) {
    validateClientAccess(loan.getClientId(), user);
    if (!loan.getClientOfficeId().equals(user.getOffice().getId())) {
      final Integer count = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM m_office child JOIN m_office root ON root.id=?"
              + " WHERE child.id=? AND child.hierarchy LIKE CONCAT(root.hierarchy,'%')",
          Integer.class, user.getOffice().getId(), loan.getClientOfficeId());
      if (count == null || count == 0) throw invalid("office.mismatch", "Loan is outside the authorized office hierarchy.");
    }
  }

  private void validateClientAccess(final Long clientId, final AppUser user) {
    final Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM m_client c JOIN m_office o ON o.id=c.office_id"
            + " WHERE c.id=? AND o.hierarchy LIKE ?",
        Integer.class, clientId, user.getOffice().getHierarchy() + "%");
    if (count == null || count == 0) throw invalid("client.not.found", "Client is outside the authorized office hierarchy.");
  }

  private CreditPaymentLoanData loanData(final Long id, final boolean details) {
    return loanData(loanReadPlatformService.retrieveOne(id), details);
  }

  private CreditPaymentLoanData loanData(final LoanAccountData original, final boolean details) {
    final LoanAccountData loan = details ? loanReadPlatformService.fetchRepaymentScheduleData(original) : original;
    final LoanSummaryData s = loan.getSummary();
    return new CreditPaymentLoanData(
        loan.getId(), loan.getAccountNo(), loan.getClientId(), loan.getClientName(), loan.getClientOfficeId(),
        loan.getStatus() == null ? null : loan.getStatus().getCode(), loan.isActive(),
        loan.getCurrency() == null ? null : loan.getCurrency().getCode(),
        value(s == null ? null : s.getPrincipalOutstanding()), value(s == null ? null : s.getInterestOutstanding()),
        value(s == null ? null : s.getFeeChargesOutstanding()), value(s == null ? null : s.getPenaltyChargesOutstanding()), null,
        value(s == null ? null : s.getTotalOutstanding()), value(s == null ? null : s.getPrincipalOverdue()),
        value(s == null ? null : s.getInterestOverdue()), value(s == null ? null : s.getFeeChargesOverdue()),
        value(s == null ? null : s.getPenaltyChargesOverdue()), null, value(s == null ? null : s.getTotalOverdue()),
        details ? loan.getRepaymentSchedule() : null,
        details ? loanReadPlatformService.retrieveLoanTransactions(loan.getId()) : null);
  }

  private List<BaseTellerDenominationData> authoritativeDenominations(
      final List<BaseTellerDenominationData> requested, final String currency, final int decimals) {
    final List<BaseTellerDenominationData> result = new ArrayList<>();
    for (BaseTellerDenominationData item : requested) {
      final List<BigDecimal> values = jdbcTemplate.query(
          "SELECT value FROM m_service_payment_denomination WHERE active=true"
              + " AND LOWER(currency_code)=LOWER(?) AND LOWER(identifier)=LOWER(?)",
          (rs, row) -> rs.getBigDecimal(1), currency, item.denominationId().trim());
      if (values.isEmpty()) throw invalid("denomination.unsupported", "Denomination is not configured for this currency.");
      final BigDecimal configured = values.get(0);
      if (configured.signum() <= 0 || configured.stripTrailingZeros().scale() > decimals) {
        throw invalid("denomination.configuration.invalid", "Configured denomination is invalid for this currency.");
      }
      if (item.value() != null && item.value().compareTo(configured) != 0) {
        throw invalid("denomination.value.mismatch", "Submitted denomination value differs from backend configuration.");
      }
      result.add(new BaseTellerDenominationData(item.denominationId().trim(), configured, item.quantity()));
    }
    return result;
  }

  private CreditPaymentBankData activeBank(final Long id) {
    final List<CreditPaymentBankData> rows = jdbcTemplate.query(
        "SELECT id,code,name FROM m_base_teller_bank WHERE id=? AND active=true",
        (rs, row) -> new CreditPaymentBankData(rs.getLong(1), rs.getString(2), rs.getString(3)), id);
    if (rows.isEmpty()) throw invalid("bank.invalid", "Bank is not active or does not exist.");
    return rows.get(0);
  }

  private long insertPayment(
      final CreditPaymentRequest request, final Prepared prepared, final AppUser user,
      final String receipt, final String fingerprint, final CreditPaymentStatus status) {
    final KeyHolder keys = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      final PreparedStatement ps = connection.prepareStatement(
          "INSERT INTO m_base_teller_credit_payment (idempotency_key,request_fingerprint,"
              + " receipt_number,status,payment_method,client_id,loan_id,amount,tender_amount,"
              + " change_amount,currency_code,payment_type_id,business_date,operator_id,office_id,"
              + " teller_id,cashier_id,client_name_snapshot,loan_account_no_snapshot,"
              + " operator_name_snapshot,note) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          new String[] {"id"});
      int i = 1;
      ps.setString(i++, request.idempotencyKey()); ps.setString(i++, fingerprint); ps.setString(i++, receipt);
      ps.setString(i++, status.name()); ps.setString(i++, request.paymentMethod().name()); ps.setLong(i++, request.clientId());
      ps.setLong(i++, request.loanId()); ps.setBigDecimal(i++, request.amount()); ps.setBigDecimal(i++, prepared.tenderAmount());
      ps.setBigDecimal(i++, prepared.changeAmount()); ps.setString(i++, prepared.loan().getCurrency().getCode());
      ps.setLong(i++, request.paymentTypeId()); ps.setObject(i++, DateUtils.getBusinessLocalDate()); ps.setLong(i++, user.getId());
      ps.setLong(i++, user.getOffice().getId()); ps.setLong(i++, prepared.cashier().getTellerId()); ps.setLong(i++, prepared.cashier().getId());
      ps.setString(i++, prepared.loan().getClientName()); ps.setString(i++, prepared.loan().getAccountNo());
      ps.setString(i++, user.getUsername()); ps.setString(i, request.note());
      return ps;
    }, keys);
    return keys.getKey().longValue();
  }

  private void insertDenominations(final long paymentId, final List<BaseTellerDenominationData> values) {
    for (BaseTellerDenominationData item : values) jdbcTemplate.update(
        "INSERT INTO m_base_teller_credit_payment_cash_detail"
            + " (credit_payment_id,denomination_identifier,denomination_value,quantity) VALUES (?,?,?,?)",
        paymentId, item.denominationId(), item.value(), item.quantity());
  }

  private void insertCheck(
      final long paymentId, final Long loanId, final CreditPaymentCheckRequest check,
      final CreditPaymentBankData bank, final AppUser user, final CreditPaymentStatus status) {
    jdbcTemplate.update(
        "INSERT INTO m_base_teller_credit_payment_check"
            + " (credit_payment_id,loan_id,bank_id,bank_name_snapshot,check_type,check_number,"
            + " check_account_number,routing_code,classification,status,accepted_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        paymentId, loanId, bank.id(), bank.name(), check.checkType(), check.checkNumber(),
        check.accountNumber(), check.routingCode(), check.classification().name(), status.name(),
        user.getId());
  }

  private Long postNativeRepayment(final CreditPaymentRequest request, final LoanAccountData loan) {
    final JsonObject json = new JsonObject();
    json.addProperty("locale", "en"); json.addProperty("dateFormat", "yyyy-MM-dd");
    json.addProperty("transactionDate", DateUtils.getBusinessLocalDate().toString());
    json.addProperty("transactionAmount", request.amount()); json.addProperty("paymentTypeId", request.paymentTypeId());
    if (request.paymentMethod() == CreditPaymentMethod.CHECK && request.check() != null) {
      json.addProperty("checkNumber", request.check().checkNumber());
      json.addProperty("bankNumber", activeBank(request.check().bankId()).code());
      if (StringUtils.isNotBlank(request.check().accountNumber())) json.addProperty("accountNumber", request.check().accountNumber());
      if (StringUtils.isNotBlank(request.check().routingCode())) json.addProperty("routingCode", request.check().routingCode());
    }
    final CommandWrapper command = new CommandWrapperBuilder().loanRepaymentTransaction(loan.getId()).withJson(GSON.toJson(json)).build(request.idempotencyKey() + ":loan-repayment");
    final CommandProcessingResult result = commandService.logCommandSource(command);
    final String transactionId = result.getTransactionId();
    return transactionId == null ? result.getResourceId() : Long.valueOf(transactionId);
  }

  private Long insertCashierIn(
      final long paymentId, final BigDecimal amount, final String currency, final AppUser user,
      final CashierData cashier, final String note) {
    final KeyHolder keys = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      final PreparedStatement ps = connection.prepareStatement(
          "INSERT INTO m_cashier_transactions (cashier_id,txn_type,txn_date,txn_amount,created_date,txn_note,"
              + " entity_type,entity_id,currency_code) VALUES (?,?,?,?,CURRENT_TIMESTAMP,?,?,?,?)",
          new String[] {"id"});
      ps.setLong(1, cashier.getId()); ps.setInt(2, CASHIER_TXN_CASH_IN); ps.setObject(3, DateUtils.getBusinessLocalDate());
      ps.setBigDecimal(4, amount); ps.setString(5, StringUtils.defaultIfBlank(note, "Loan cash repayment"));
      ps.setString(6, RESOURCE); ps.setLong(7, paymentId); ps.setString(8, currency); return ps;
    }, keys);
    return keys.getKey().longValue();
  }

  private NativeAllocation nativeAllocation(final Long transactionId) {
    return jdbcTemplate.queryForObject(
        "SELECT principal_portion_derived,interest_portion_derived,fee_charges_portion_derived,"
            + " penalty_charges_portion_derived,overpayment_portion_derived"
            + " FROM m_loan_transaction WHERE id=?",
        (rs, row) -> new NativeAllocation(rs.getBigDecimal(1), rs.getBigDecimal(2),
            rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5)), transactionId);
  }

  private LockedCheck lockCheck(final Long id, final AppUser user) {
    final List<LockedCheck> rows = jdbcTemplate.query(
        "SELECT c.id,c.credit_payment_id,c.status,c.clear_idempotency_key,c.clear_request_fingerprint,"
            + " c.return_idempotency_key,c.return_request_fingerprint,c.bank_id,c.bank_name_snapshot,"
            + " c.check_type,c.check_number,c.check_account_number,c.routing_code,c.classification,"
            + " p.client_id,p.loan_id,p.amount,p.currency_code,p.payment_type_id,p.office_id,p.teller_id,p.cashier_id"
            + " FROM m_base_teller_credit_payment_check c JOIN m_base_teller_credit_payment p"
            + " ON p.id=c.credit_payment_id WHERE c.id=? FOR UPDATE",
        (rs, row) -> new LockedCheck(
            rs.getLong(1), rs.getLong(2), CreditPaymentStatus.valueOf(rs.getString(3)), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getString(7), rs.getLong(8), rs.getString(9), rs.getString(10), rs.getString(11),
            rs.getString(12), rs.getString(13), CreditPaymentCheckClassification.valueOf(rs.getString(14)),
            rs.getLong(15), rs.getLong(16), rs.getBigDecimal(17), rs.getString(18), rs.getLong(19), rs.getLong(20),
            nullableLong(rs, 21), nullableLong(rs, 22)), id);
    if (rows.isEmpty()) throw invalid("check.not.found", "Credit-payment check was not found.");
    validateOffice(rows.get(0).officeId(), user);
    return rows.get(0);
  }

  private Existing existing(final String key) {
    final List<Existing> rows = jdbcTemplate.query(
        "SELECT id,request_fingerprint,receipt_number FROM m_base_teller_credit_payment WHERE idempotency_key=?",
        (rs, row) -> new Existing(rs.getLong(1), rs.getString(2), rs.getString(3)), key);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private CreditPaymentReceiptData existingResult(final Existing existing, final String fingerprint) {
    if (!fingerprint.equals(existing.fingerprint())) throw invalid("idempotency.conflict", "idempotencyKey was used for a different request.");
    return receiptByPaymentId(existing.id(), context.authenticatedUser());
  }

  private CreditPaymentReceiptData receiptByPaymentId(final Long id, final AppUser user) {
    final List<CreditPaymentReceiptData> rows = jdbcTemplate.query(receiptSql() + " WHERE p.id=?", this::mapReceipt, id);
    if (rows.isEmpty()) throw invalid("receipt.not.found", "Credit payment receipt was not found.");
    validateOffice(rows.get(0).officeId(), user); return rows.get(0);
  }

  private CreditPaymentReceiptData mapReceipt(final ResultSet rs, final int row) throws SQLException {
    final Long id = rs.getLong("payment_id");
    final List<BaseTellerDenominationData> denominations = jdbcTemplate.query(
        "SELECT denomination_identifier,denomination_value,quantity FROM"
            + " m_base_teller_credit_payment_cash_detail WHERE credit_payment_id=? ORDER BY denomination_value DESC",
        (r, n) -> new BaseTellerDenominationData(r.getString(1), r.getBigDecimal(2), r.getLong(3)), id);
    final Long checkId = nullableLong(rs, "check_id");
    final CreditPaymentCheckData check = checkId == null ? null : new CreditPaymentCheckData(
        checkId, rs.getLong("bank_id"), rs.getString("bank_name_snapshot"), rs.getString("check_type"),
        rs.getString("check_number"), rs.getString("check_account_number"), rs.getString("routing_code"),
        CreditPaymentCheckClassification.valueOf(rs.getString("classification")), CreditPaymentStatus.valueOf(rs.getString("check_status")),
        rs.getLong("accepted_by"), offset(rs, "accepted_on_utc"), nullableLong(rs, "cleared_by"), offset(rs, "cleared_on_utc"),
        nullableLong(rs, "returned_by"), offset(rs, "returned_on_utc"), rs.getString("return_reason"));
    return new CreditPaymentReceiptData(
        id, rs.getString("receipt_number"), CreditPaymentStatus.valueOf(rs.getString("status")),
        CreditPaymentMethod.valueOf(rs.getString("payment_method")), rs.getLong("client_id"), rs.getString("client_name_snapshot"),
        rs.getLong("loan_id"), rs.getString("loan_account_no_snapshot"), rs.getBigDecimal("amount"),
        rs.getBigDecimal("tender_amount"), rs.getBigDecimal("change_amount"), rs.getString("currency_code"),
        rs.getLong("payment_type_id"), nullableLong(rs, "loan_transaction_id"), nullableLong(rs, "cashier_transaction_id"),
        rs.getBigDecimal("principal_portion"), rs.getBigDecimal("interest_portion"),
        rs.getBigDecimal("fee_portion"), rs.getBigDecimal("penalty_portion"),
        rs.getBigDecimal("overpayment_portion"),
        rs.getDate("business_date").toLocalDate(), rs.getLong("operator_id"), rs.getString("operator_name_snapshot"),
        rs.getLong("office_id"), nullableLong(rs, "teller_id"), nullableLong(rs, "cashier_id"), rs.getString("note"),
        rs.getString("failure_message"), offset(rs, "created_on_utc"), offset(rs, "completed_on_utc"), denominations, check);
  }

  private static String receiptSql() {
    return "SELECT p.id payment_id,p.receipt_number,p.status,p.payment_method,p.client_id,"
        + " p.client_name_snapshot,p.loan_id,p.loan_account_no_snapshot,p.amount,p.tender_amount,"
        + " p.change_amount,p.currency_code,p.payment_type_id,p.loan_transaction_id,"
        + " p.cashier_transaction_id,p.principal_portion,p.interest_portion,p.fee_portion,"
        + " p.penalty_portion,p.overpayment_portion,p.business_date,p.operator_id,p.operator_name_snapshot,"
        + " p.office_id,p.teller_id,p.cashier_id,p.note,p.failure_message,p.created_on_utc,"
        + " p.completed_on_utc,c.id check_id,c.bank_id,c.bank_name_snapshot,c.check_type,"
        + " c.check_number,c.check_account_number,c.routing_code,c.classification,c.status check_status,"
        + " c.accepted_by,c.accepted_on_utc,c.cleared_by,c.cleared_on_utc,c.returned_by,"
        + " c.returned_on_utc,c.return_reason FROM m_base_teller_credit_payment p LEFT JOIN"
        + " m_base_teller_credit_payment_check c ON c.credit_payment_id=p.id";
  }

  private AppUser readableUser() { final AppUser user = context.authenticatedUser(); user.validateHasReadPermission(RESOURCE); return user; }

  private CashierData resolveCashier(final AppUser user) {
    if (user.getStaffId() == null || user.getOffice() == null) throw invalid("cashier.context.required", "Authenticated user must be linked to staff and office.");
    final LocalDate date = DateUtils.getBusinessLocalDate();
    final Collection<TellerData> tellers = tellerManagementReadPlatformService.getTellers(user.getOffice().getId());
    for (TellerData teller : tellers) for (CashierData cashier : tellerManagementReadPlatformService.getCashiersForTeller(teller.getId(), date, date))
      if (user.getStaffId().equals(cashier.getStaffId())) return cashier;
    throw invalid("cashier.not.allocated", "Authenticated user has no active cashier allocation.");
  }

  private static void validateOffice(final Long officeId, final AppUser user) {
    if (user.getOffice() == null || !officeId.equals(user.getOffice().getId())) throw invalid("office.mismatch", "Operation belongs to another office.");
  }

  private static BigDecimal value(final BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
  private static Long nullableLong(final ResultSet rs, final int index) throws SQLException { final long v = rs.getLong(index); return rs.wasNull() ? null : v; }
  private static Long nullableLong(final ResultSet rs, final String name) throws SQLException { final long v = rs.getLong(name); return rs.wasNull() ? null : v; }
  private static OffsetDateTime offset(final ResultSet rs, final String name) throws SQLException { final Timestamp v = rs.getTimestamp(name); return v == null ? null : OffsetDateTime.of(v.toLocalDateTime(), ZoneOffset.UTC); }
  private static String shortHash(final String input) { return hash(input).substring(0, 16).toUpperCase(Locale.ROOT); }
  private static String hash(final String input) {
    try { final byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)); final StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b)); return out.toString(); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  private static GeneralPlatformDomainRuleException invalid(final String code, final String message) { return new GeneralPlatformDomainRuleException("error.msg.base.teller.credit.payment." + code, message); }

  private record Prepared(LoanAccountData loan, CashierData cashier, List<BaseTellerDenominationData> denominations, BigDecimal tenderAmount, BigDecimal changeAmount, CreditPaymentBankData bank) {}
  private record Existing(Long id, String fingerprint, String receipt) {}
  private record NativeAllocation(BigDecimal principal, BigDecimal interest, BigDecimal fee,
      BigDecimal penalty, BigDecimal overpayment) {}
  private record LockedCheck(Long id, Long paymentId, CreditPaymentStatus status, String clearKey, String clearFingerprint, String returnKey, String returnFingerprint, Long bankId, String bankName, String checkType, String checkNumber, String accountNumber, String routingCode, CreditPaymentCheckClassification classification, Long clientId, Long loanId, BigDecimal amount, String currency, Long paymentTypeId, Long officeId, Long tellerId, Long cashierId) {
    CreditPaymentCheckRequest request() { return new CreditPaymentCheckRequest(bankId, checkType, checkNumber, accountNumber, routingCode, classification); }
  }
}
