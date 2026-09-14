package org.apache.fineract.baseteller.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.BaseTellerCheckClearingStatus;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.BaseTellerDepositCheckRequest;
import org.apache.fineract.baseteller.data.BaseTellerDepositFundingData;
import org.apache.fineract.baseteller.data.BaseTellerDepositReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerDepositRequest;
import org.apache.fineract.baseteller.data.BaseTellerDepositStatus;
import org.apache.fineract.baseteller.data.BaseTellerFundingType;
import org.apache.fineract.baseteller.validation.BaseTellerDepositValidator;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.teller.data.CashierData;
import org.apache.fineract.organisation.teller.service.TellerManagementReadPlatformService;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.paymenttype.data.PaymentTypeData;
import org.apache.fineract.portfolio.paymenttype.service.PaymentTypeReadService;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BaseTellerWritePlatformServiceImpl implements BaseTellerWritePlatformService {

  private static final String DEPOSIT_RESOURCE = "BASE_TELLER_DEPOSIT";
  private static final String CHECK_CLEARING_PERMISSION = "AUTHORIZE_BASE_TELLER_CHECK_CLEARING";
  private static final Gson GSON = new Gson();

  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  private final PlatformSecurityContext context;
  private final BaseTellerDepositValidator depositValidator;
  private final BaseTellerReadPlatformService readPlatformService;
  private final ClientRepositoryWrapper clientRepository;
  private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
  private final PaymentTypeReadService paymentTypeReadService;
  private final TellerManagementReadPlatformService tellerManagementReadPlatformService;
  private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;

  @Override
  @Transactional
  public BaseTellerDepositReceiptData deposit(final BaseTellerDepositRequest request) {
    final AppUser user = context.authenticatedUser();
    user.validateHasCreatePermission(DEPOSIT_RESOURCE);
    user.validateHasCreatePermission("savingsaccount");
    depositValidator.validate(request);
    final String fingerprint = requestFingerprint(request);
    final ExistingDepositOperation existing = existingDepositOperation(request.idempotencyKey());
    if (existing != null) {
      if (!fingerprint.equals(existing.requestFingerprint())) {
        throw new GeneralPlatformDomainRuleException(
            "error.msg.base.teller.deposit.idempotency.conflict",
            "A different base teller deposit already exists for this idempotencyKey.");
      }
      return readPlatformService.retrieveDepositReceipt(existing.receiptNumber());
    }

    authorizeClearedChecksIfNeeded(request.funding(), user);
    clientRepository.findOneWithNotFoundDetection(request.clientId());
    final SavingsAccountData account =
        savingsAccountReadPlatformService.retrieveOne(request.savingsAccountId());
    validateDepositAccount(request, account);
    validatePaymentType(request.funding());
    final CashierData cashier = resolveCashier(user);
    final String receiptNumber = depositReceiptNumber(request.idempotencyKey());

    try {
      insertDepositOperation(request, user, cashier, receiptNumber, fingerprint);
    } catch (DuplicateKeyException duplicate) {
      final ExistingDepositOperation duplicateOperation =
          existingDepositOperation(request.idempotencyKey());
      if (duplicateOperation != null
          && fingerprint.equals(duplicateOperation.requestFingerprint())) {
        return readPlatformService.retrieveDepositReceipt(duplicateOperation.receiptNumber());
      }
      throw duplicate;
    }

    try {
      if (shouldWaitForCollection(request.funding())) {
        updateDepositStatus(
            request.idempotencyKey(), BaseTellerDepositStatus.PENDING_COLLECTION, null, null);
        return readPlatformService.retrieveDepositReceipt(receiptNumber);
      }
      final CommandProcessingResult deposit =
          execute(
              new CommandWrapperBuilder().savingsAccountDeposit(account.getId()),
              depositJson(request),
              request.idempotencyKey() + ":deposit");
      updateDepositTransaction(
          request.idempotencyKey(),
          parseLong(deposit.getTransactionId()),
          BaseTellerDepositStatus.DEPOSIT_POSTED);
      completeDepositOperation(request.idempotencyKey());
      return readPlatformService.retrieveDepositReceipt(receiptNumber);
    } catch (RuntimeException failure) {
      markDepositFailed(request.idempotencyKey(), failure.getMessage());
      return readPlatformService.retrieveDepositReceipt(receiptNumber);
    }
  }

  private void validateDepositAccount(
      final BaseTellerDepositRequest request, final SavingsAccountData account) {
    if (account == null || account.getId() == null) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.deposit.account.not.found",
          "Savings account was not found.");
    }
    if (!request.clientId().equals(account.getClientId())) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.deposit.account.client.mismatch",
          "Savings account does not belong to the selected client.");
    }
    if (account.getStatus() == null || !account.getStatus().isActive()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.deposit.account.not.active",
          "Savings account must be active to receive deposits.");
    }
    final String accountCurrency =
        account.getCurrency() == null ? null : account.getCurrency().getCode();
    if (!StringUtils.equalsIgnoreCase(accountCurrency, request.funding().currencyCode())) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.deposit.currency.mismatch",
          "Funding currency must match the savings account currency.");
    }
  }

  private void insertDepositOperation(
      final BaseTellerDepositRequest request,
      final AppUser user,
      final CashierData cashier,
      final String receiptNumber,
      final String fingerprint) {
    jdbcTemplate.update(
        "INSERT INTO m_base_teller_deposit"
            + " (idempotency_key, request_fingerprint, receipt_number, status, client_id,"
            + " savings_account_id, funding_type, amount, currency_code, payment_type_id,"
            + " operator_id, office_id, teller_id, cashier_id)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        request.idempotencyKey(),
        fingerprint,
        receiptNumber,
        BaseTellerDepositStatus.IN_PROGRESS.name(),
        request.clientId(),
        request.savingsAccountId(),
        request.funding().type().name(),
        request.funding().amount(),
        request.funding().currencyCode(),
        request.funding().paymentTypeId(),
        user.getId(),
        user.getOffice() == null ? null : user.getOffice().getId(),
        cashier == null ? null : cashier.getTellerId(),
        cashier == null ? null : cashier.getId());
    if (request.funding().type() == BaseTellerFundingType.CASH) {
      insertDepositDenominations(request.idempotencyKey(), request.funding().denominations());
    } else {
      insertDepositChecks(request.idempotencyKey(), request.funding().checks(), user);
    }
  }

  private void insertDepositDenominations(
      final String idempotencyKey, final List<BaseTellerDenominationData> denominations) {
    final Long depositId = existingDepositOperation(idempotencyKey).id();
    for (BaseTellerDenominationData denomination : denominations) {
      jdbcTemplate.update(
          "INSERT INTO m_base_teller_deposit_cash_detail"
              + " (deposit_id, denomination_identifier, denomination_value, quantity)"
              + " VALUES (?, ?, ?, ?)",
          depositId,
          denomination.denominationId(),
          denomination.value(),
          denomination.quantity());
    }
  }

  private void insertDepositChecks(
      final String idempotencyKey,
      final List<BaseTellerDepositCheckRequest> checks,
      final AppUser user) {
    final Long depositId = existingDepositOperation(idempotencyKey).id();
    for (BaseTellerDepositCheckRequest check : checks) {
      final BaseTellerCheckClearingStatus clearingStatus = clearingStatus(check);
      final boolean cleared = clearingStatus == BaseTellerCheckClearingStatus.CLEARED;
      jdbcTemplate.update(
          "INSERT INTO m_base_teller_deposit_check_detail"
              + " (deposit_id, check_type, check_bank, check_number, amount, clearing_status,"
              + " check_account_number, check_routing_code, clearing_authorized_by,"
              + " clearing_authorized_on_utc)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
              + " CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)",
          depositId,
          check.checkType(),
          check.bank(),
          check.checkNumber(),
          check.amount(),
          clearingStatus.name(),
          check.accountNumber(),
          check.routingCode(),
          cleared ? user.getId() : null,
          cleared);
    }
  }

  private void completeDepositOperation(final String idempotencyKey) {
    jdbcTemplate.update(
        "UPDATE m_base_teller_deposit SET status = ?, completed_on_utc = CURRENT_TIMESTAMP"
            + " WHERE idempotency_key = ?",
        BaseTellerDepositStatus.COMPLETED.name(),
        idempotencyKey);
  }

  private void updateDepositStatus(
      final String idempotencyKey,
      final BaseTellerDepositStatus status,
      final Long transactionId,
      final String failureMessage) {
    jdbcTemplate.update(
        "UPDATE m_base_teller_deposit SET status = ?,"
            + " savings_transaction_id = COALESCE(?, savings_transaction_id),"
            + " failure_message = ? WHERE idempotency_key = ?",
        status.name(),
        transactionId,
        failureMessage,
        idempotencyKey);
  }

  private void updateDepositTransaction(
      final String idempotencyKey,
      final Long transactionId,
      final BaseTellerDepositStatus status) {
    updateDepositStatus(idempotencyKey, status, transactionId, null);
  }

  private void markDepositFailed(final String idempotencyKey, final String message) {
    updateDepositStatus(
        idempotencyKey,
        BaseTellerDepositStatus.FAILED,
        null,
        StringUtils.abbreviate(message, 1000));
  }

  private CommandProcessingResult execute(
      final CommandWrapperBuilder builder, final String json, final String idempotencyKey) {
    final CommandWrapper command = builder.withJson(json).build(idempotencyKey);
    return commandsSourceWritePlatformService.logCommandSource(command);
  }

  private String depositJson(final BaseTellerDepositRequest request) {
    final BaseTellerDepositFundingData funding = request.funding();
    final JsonObject json = new JsonObject();
    addLocale(json, request.locale(), request.dateFormat());
    json.addProperty("transactionDate", transactionDate(request));
    json.addProperty("transactionAmount", funding.amount());
    json.addProperty("paymentTypeId", funding.paymentTypeId());
    if (funding.type() == BaseTellerFundingType.CHECK && !funding.checks().isEmpty()) {
      final BaseTellerDepositCheckRequest check = funding.checks().get(0);
      json.addProperty("checkNumber", check.checkNumber());
      json.addProperty("bankNumber", check.bank());
      if (StringUtils.isNotBlank(check.accountNumber())) {
        json.addProperty("accountNumber", check.accountNumber());
      }
      if (StringUtils.isNotBlank(check.routingCode())) {
        json.addProperty("routingCode", check.routingCode());
      }
    }
    return GSON.toJson(json);
  }

  private void addLocale(final JsonObject json, final String locale, final String dateFormat) {
    json.addProperty("locale", StringUtils.defaultIfBlank(locale, "en"));
    json.addProperty("dateFormat", StringUtils.defaultIfBlank(dateFormat, "yyyy-MM-dd"));
  }

  private void validatePaymentType(final BaseTellerDepositFundingData funding) {
    final PaymentTypeData paymentType = paymentTypeReadService.retrieveOne(funding.paymentTypeId());
    if (funding.type() == BaseTellerFundingType.CASH
        && !Boolean.TRUE.equals(paymentType.getIsCashPayment())) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.cash.payment.type.invalid",
          "Cash funding requires a cash payment type.");
    }
  }

  private void authorizeClearedChecksIfNeeded(
      final BaseTellerDepositFundingData funding, final AppUser user) {
    if (funding.type() != BaseTellerFundingType.CHECK) {
      return;
    }
    if (funding.checks().stream()
        .map(BaseTellerWritePlatformServiceImpl::clearingStatus)
        .anyMatch(BaseTellerCheckClearingStatus.CLEARED::equals)) {
      user.validateHasPermissionTo(CHECK_CLEARING_PERMISSION);
    }
  }

  private CashierData resolveCashier(final AppUser user) {
    if (user.getStaffId() == null || user.getOffice() == null) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.cashier.context.required",
          "Authenticated user must be linked to staff and office for base teller operations.");
    }
    final List<CashierData> cashiers =
        tellerManagementReadPlatformService.getCashierData(
            user.getOffice().getId(), null, user.getStaffId(), DateUtils.getBusinessLocalDate())
            .stream()
            .toList();
    if (cashiers.isEmpty()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.cashier.not.allocated",
          "Authenticated user is not allocated to an active cashier for this office.");
    }
    return cashiers.get(0);
  }

  private ExistingDepositOperation existingDepositOperation(final String idempotencyKey) {
    final List<ExistingDepositOperation> operations =
        namedParameterJdbcTemplate.query(
            "SELECT id, request_fingerprint, receipt_number FROM m_base_teller_deposit"
                + " WHERE idempotency_key = :key",
            Map.of("key", idempotencyKey),
            (rs, row) ->
                new ExistingDepositOperation(
                    rs.getLong("id"),
                    rs.getString("request_fingerprint"),
                    rs.getString("receipt_number")));
    return operations.isEmpty() ? null : operations.get(0);
  }

  private static String depositReceiptNumber(final String idempotencyKey) {
    final String sanitized = idempotencyKey.replaceAll("[^A-Za-z0-9-]", "-");
    return "BTD-" + StringUtils.abbreviate(sanitized, 96);
  }

  private static String transactionDate(final BaseTellerDepositRequest request) {
    return StringUtils.defaultIfBlank(
        request.transactionDate(), DateUtils.getBusinessLocalDate().toString());
  }

  private static Long parseLong(final String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static boolean shouldWaitForCollection(final BaseTellerDepositFundingData funding) {
    return funding.type() == BaseTellerFundingType.CHECK
        && funding.checks().stream()
            .map(BaseTellerWritePlatformServiceImpl::clearingStatus)
            .anyMatch(BaseTellerCheckClearingStatus.SUBJECT_TO_COLLECTION::equals);
  }

  private static BaseTellerCheckClearingStatus clearingStatus(
      final BaseTellerDepositCheckRequest check) {
    return check.clearingStatus() == null
        ? BaseTellerCheckClearingStatus.SUBJECT_TO_COLLECTION
        : check.clearingStatus();
  }

  private static String requestFingerprint(final BaseTellerDepositRequest request) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] hash = digest.digest(GSON.toJson(request).getBytes(StandardCharsets.UTF_8));
      final StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte value : hash) {
        builder.append(String.format("%02x", value));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available.", exception);
    }
  }

  private record ExistingDepositOperation(
      Long id, String requestFingerprint, String receiptNumber) {}
}
