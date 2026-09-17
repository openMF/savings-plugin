package org.apache.fineract.baseteller.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
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
import org.apache.fineract.baseteller.data.BaseTellerFundingData;
import org.apache.fineract.baseteller.data.BaseTellerFundingType;
import org.apache.fineract.baseteller.data.BaseTellerOpeningReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerOpeningStatus;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckPaymentRequest;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckStatus;
import org.apache.fineract.baseteller.data.BaseTellerSavingsOpeningRequest;
import org.apache.fineract.baseteller.validation.BaseTellerReturnedCheckPaymentValidator;
import org.apache.fineract.baseteller.validation.BaseTellerDepositValidator;
import org.apache.fineract.baseteller.validation.BaseTellerSavingsOpeningValidator;
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
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.paymenttype.data.PaymentTypeData;
import org.apache.fineract.portfolio.paymenttype.service.PaymentTypeReadService;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountStatusEnumData;
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

  private static final String OPENING_RESOURCE = "BASE_TELLER_SAVINGS_OPENING";
  private static final String DEPOSIT_RESOURCE = "BASE_TELLER_DEPOSIT";
  private static final String RETURNED_CHECK_RESOURCE = "BASE_TELLER_RETURNED_CHECK_PAYMENT";
  private static final String CHECK_CLEARING_PERMISSION = "AUTHORIZE_BASE_TELLER_CHECK_CLEARING";
  private static final int CASHIER_TXN_CASH_IN = 103;
  private static final Gson GSON = new Gson();

  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  private final PlatformSecurityContext context;
  private final BaseTellerSavingsOpeningValidator openingValidator;
  private final BaseTellerDepositValidator depositValidator;
  private final BaseTellerReturnedCheckPaymentValidator returnedCheckPaymentValidator;
  private final BaseTellerReadPlatformService readPlatformService;
  private final ClientRepositoryWrapper clientRepository;
  private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
  private final PaymentTypeReadService paymentTypeReadService;
  private final TellerManagementReadPlatformService tellerManagementReadPlatformService;
  private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;

  @Override
  @Transactional
  public BaseTellerOpeningReceiptData openSavingsAccount(
      final BaseTellerSavingsOpeningRequest request) {
    final AppUser user = context.authenticatedUser();
    user.validateHasCreatePermission(OPENING_RESOURCE);
    user.validateHasCreatePermission("savingsaccount");
    openingValidator.validate(request);
    validateApplicationIds(request);
    final String fingerprint = requestFingerprint(request);
    final ExistingOpeningOperation existing = existingOpeningOperation(request.idempotencyKey());
    if (existing != null) {
      if (!fingerprint.equals(existing.requestFingerprint())) {
        throw new GeneralPlatformDomainRuleException(
            "error.msg.base.teller.savings.opening.idempotency.conflict",
            "A different base teller savings opening already exists for this idempotencyKey.");
      }
      return readPlatformService.retrieveOpeningReceipt(existing.receiptNumber());
    }

    clientRepository.findOneWithNotFoundDetection(request.clientId());
    validatePaymentType(request.initialFunding());
    final CashierData cashier = resolveCashier(user);
    final String receiptNumber = openingReceiptNumber(request.idempotencyKey());

    try {
      insertOpeningOperation(request, user, cashier, receiptNumber, fingerprint);
    } catch (DuplicateKeyException duplicate) {
      final ExistingOpeningOperation duplicateOperation =
          existingOpeningOperation(request.idempotencyKey());
      if (duplicateOperation != null
          && fingerprint.equals(duplicateOperation.requestFingerprint())) {
        return readPlatformService.retrieveOpeningReceipt(duplicateOperation.receiptNumber());
      }
      throw duplicate;
    }

    Long savingsAccountId = null;
    try {
      final CommandProcessingResult created =
          execute(
              new CommandWrapperBuilder().createSavingsAccount(),
              accountJson(request),
              request.idempotencyKey() + ":create");
      savingsAccountId = firstNonNull(created.getSavingsId(), created.getResourceId());
      updateOpeningStatus(
          request.idempotencyKey(),
          BaseTellerOpeningStatus.ACCOUNT_CREATED,
          savingsAccountId,
          null);

      SavingsAccountData account = savingsAccountReadPlatformService.retrieveOne(savingsAccountId);
      account = approveIfNeeded(request, account);
      account = activateIfNeeded(request, account);
      depositIfNeeded(request, account);

      completeOpeningOperation(request.idempotencyKey());
      return readPlatformService.retrieveOpeningReceipt(receiptNumber);
    } catch (RuntimeException failure) {
      markOpeningFailed(request.idempotencyKey(), savingsAccountId, failure.getMessage());
      return readPlatformService.retrieveOpeningReceipt(receiptNumber);
    }
  }

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

  @Override
  @Transactional
  public BaseTellerReturnedCheckReceiptData settleReturnedCheck(
      final Long returnedCheckId, final BaseTellerReturnedCheckPaymentRequest request) {
    final AppUser user = context.authenticatedUser();
    user.validateHasCreatePermission(RETURNED_CHECK_RESOURCE);
    returnedCheckPaymentValidator.validate(request);
    final String fingerprint = requestFingerprint(request);
    final ExistingReturnedCheckSettlement existing =
        existingReturnedCheckSettlement(request.idempotencyKey());
    if (existing != null) {
      if (!fingerprint.equals(existing.requestFingerprint())) {
        throw new GeneralPlatformDomainRuleException(
            "error.msg.base.teller.returned.check.payment.idempotency.conflict",
            "A different returned check payment already exists for this idempotencyKey.");
      }
      return readPlatformService.retrieveReturnedCheckReceipt(existing.receiptNumber());
    }

    final ReturnedCheckForSettlement returnedCheck = lockReturnedCheck(returnedCheckId, user);
    if (returnedCheck.status() != BaseTellerReturnedCheckStatus.RETURNED) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.returned.check.not.payable",
          "Returned check is not payable.");
    }
    if (!StringUtils.equalsIgnoreCase(returnedCheck.currencyCode(), request.currencyCode())) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.returned.check.currency.mismatch",
          "Cash currency must match the returned check currency.");
    }
    if (request.cashReceived().compareTo(returnedCheck.amount()) < 0) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.returned.check.cash.insufficient",
          "Cash received must be greater than or equal to the returned check amount.");
    }
    validatePaymentType(request);
    final CashierData cashier = resolveCashier(user);
    if (!returnedCheck.officeId().equals(user.getOffice().getId())
        || !returnedCheck.officeId().equals(cashier.getOfficeId())) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.returned.check.office.mismatch",
          "Returned check office must match the active cashier office.");
    }

    final BigDecimal changeAmount = request.cashReceived().subtract(returnedCheck.amount());
    final String receiptNumber = returnedCheckReceiptNumber(request.idempotencyKey());
    final Long settlementId =
        insertReturnedCheckSettlement(
            request, returnedCheck, user, cashier, receiptNumber, fingerprint, changeAmount);
    insertReturnedCheckSettlementDenominations(settlementId, request.denominations());
    final Long cashierTransactionId =
        insertCashierCashIn(request, returnedCheck, user, cashier, settlementId);
    final int updated =
        jdbcTemplate.update(
            "UPDATE m_base_teller_returned_check SET status = ?, cashier_transaction_id = ?,"
                + " settled_on_utc = CURRENT_TIMESTAMP, settled_by = ?"
                + " WHERE id = ? AND status = ?",
            BaseTellerReturnedCheckStatus.SETTLED.name(),
            cashierTransactionId,
            user.getId(),
            returnedCheck.id(),
            BaseTellerReturnedCheckStatus.RETURNED.name());
    if (updated != 1) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.returned.check.already.settled",
          "Returned check has already been settled.");
    }
    jdbcTemplate.update(
        "UPDATE m_base_teller_returned_check_payment SET cashier_transaction_id = ?,"
            + " completed_on_utc = CURRENT_TIMESTAMP WHERE id = ?",
        cashierTransactionId,
        settlementId);
    return readPlatformService.retrieveReturnedCheckReceipt(receiptNumber);
  }

  private SavingsAccountData approveIfNeeded(
      final BaseTellerSavingsOpeningRequest request, final SavingsAccountData account) {
    final SavingsAccountStatusEnumData status = account.getStatus();
    if (Boolean.FALSE.equals(request.approve())
        || status == null
        || !status.isSubmittedAndPendingApproval()) {
      return account;
    }
    execute(
        new CommandWrapperBuilder().approveSavingsAccountApplication(account.getId()),
        lifecycleJson("approvedOnDate", request),
        request.idempotencyKey() + ":approve");
    updateOpeningStatus(
        request.idempotencyKey(), BaseTellerOpeningStatus.APPROVED, account.getId(), null);
    return savingsAccountReadPlatformService.retrieveOne(account.getId());
  }

  private SavingsAccountData activateIfNeeded(
      final BaseTellerSavingsOpeningRequest request, final SavingsAccountData account) {
    final SavingsAccountStatusEnumData status = account.getStatus();
    if (Boolean.FALSE.equals(request.activate()) || status == null || !status.isApproved()) {
      return account;
    }
    execute(
        new CommandWrapperBuilder().savingsAccountActivation(account.getId()),
        lifecycleJson("activatedOnDate", request),
        request.idempotencyKey() + ":activate");
    updateOpeningStatus(
        request.idempotencyKey(), BaseTellerOpeningStatus.ACTIVATED, account.getId(), null);
    return savingsAccountReadPlatformService.retrieveOne(account.getId());
  }

  private void depositIfNeeded(
      final BaseTellerSavingsOpeningRequest request, final SavingsAccountData account) {
    if (request.initialFunding() == null) {
      return;
    }
    if (account.getStatus() == null || !account.getStatus().isActive()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.savings.opening.account.not.active.after.lifecycle",
          "Savings account must be active before initial funding can be posted.");
    }
    final CommandProcessingResult deposit =
        execute(
            new CommandWrapperBuilder().savingsAccountDeposit(account.getId()),
            openingDepositJson(request),
            request.idempotencyKey() + ":deposit");
    updateOpeningDeposit(
        request.idempotencyKey(),
        parseLong(deposit.getTransactionId()),
        BaseTellerOpeningStatus.FUNDED);
  }

  private void insertOpeningOperation(
      final BaseTellerSavingsOpeningRequest request,
      final AppUser user,
      final CashierData cashier,
      final String receiptNumber,
      final String fingerprint) {
    jdbcTemplate.update(
        "INSERT INTO m_base_teller_savings_opening"
            + " (idempotency_key, request_fingerprint, receipt_number, status, client_id,"
            + " savings_product_id, funding_type, amount, currency_code, payment_type_id,"
            + " operator_id, office_id, teller_id, cashier_id, check_type, check_bank,"
            + " check_number, check_account_number, check_routing_code)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        request.idempotencyKey(),
        fingerprint,
        receiptNumber,
        BaseTellerOpeningStatus.IN_PROGRESS.name(),
        request.clientId(),
        request.productId(),
        request.initialFunding() == null ? null : request.initialFunding().type().name(),
        request.initialFunding() == null ? BigDecimal.ZERO : request.initialFunding().amount(),
        request.initialFunding() == null ? null : request.initialFunding().currencyCode(),
        request.initialFunding() == null ? null : request.initialFunding().paymentTypeId(),
        user.getId(),
        user.getOffice() == null ? null : user.getOffice().getId(),
        cashier == null ? null : cashier.getTellerId(),
        cashier == null ? null : cashier.getId(),
        check(request) == null ? null : check(request).checkType(),
        check(request) == null ? null : check(request).bank(),
        check(request) == null ? null : check(request).checkNumber(),
        check(request) == null ? null : check(request).accountNumber(),
        check(request) == null ? null : check(request).routingCode());
    if (request.initialFunding() != null
        && request.initialFunding().type() == BaseTellerFundingType.CASH) {
      insertOpeningDenominations(request.idempotencyKey(), request.initialFunding().denominations());
    }
  }

  private void insertOpeningDenominations(
      final String idempotencyKey, final List<BaseTellerDenominationData> denominations) {
    final Long openingId = existingOpeningOperation(idempotencyKey).id();
    for (BaseTellerDenominationData denomination : denominations) {
      jdbcTemplate.update(
          "INSERT INTO m_base_teller_savings_opening_cash_detail"
              + " (opening_id, denomination_identifier, denomination_value, quantity)"
              + " VALUES (?, ?, ?, ?)",
          openingId,
          denomination.denominationId(),
          denomination.value(),
          denomination.quantity());
    }
  }

  private void completeOpeningOperation(final String idempotencyKey) {
    jdbcTemplate.update(
        "UPDATE m_base_teller_savings_opening SET status = ?, completed_on_utc = CURRENT_TIMESTAMP"
            + " WHERE idempotency_key = ?",
        BaseTellerOpeningStatus.COMPLETED.name(),
        idempotencyKey);
  }

  private void updateOpeningStatus(
      final String idempotencyKey,
      final BaseTellerOpeningStatus status,
      final Long savingsAccountId,
      final String failureMessage) {
    jdbcTemplate.update(
        "UPDATE m_base_teller_savings_opening SET status = ?,"
            + " savings_account_id = COALESCE(?, savings_account_id),"
            + " failure_message = ? WHERE idempotency_key = ?",
        status.name(),
        savingsAccountId,
        failureMessage,
        idempotencyKey);
  }

  private void updateOpeningDeposit(
      final String idempotencyKey,
      final Long transactionId,
      final BaseTellerOpeningStatus status) {
    jdbcTemplate.update(
        "UPDATE m_base_teller_savings_opening SET status = ?,"
            + " initial_deposit_transaction_id = ? WHERE idempotency_key = ?",
        status.name(),
        transactionId,
        idempotencyKey);
  }

  private void markOpeningFailed(
      final String idempotencyKey, final Long savingsAccountId, final String message) {
    updateOpeningStatus(
        idempotencyKey,
        BaseTellerOpeningStatus.FAILED,
        savingsAccountId,
        StringUtils.abbreviate(message, 1000));
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

  private ReturnedCheckForSettlement lockReturnedCheck(
      final Long returnedCheckId, final AppUser user) {
    final List<ReturnedCheckForSettlement> checks =
        namedParameterJdbcTemplate.query(
            "SELECT rc.id, rc.amount, rc.currency_code, rc.status, rc.office_id"
                + " FROM m_base_teller_returned_check rc"
                + " JOIN m_office off ON off.id = rc.office_id"
                + " WHERE rc.id = :id AND off.hierarchy LIKE :officeHierarchy FOR UPDATE",
            Map.of("id", returnedCheckId, "officeHierarchy", user.getOffice().getHierarchy() + "%"),
            (rs, row) ->
                new ReturnedCheckForSettlement(
                    rs.getLong("id"),
                    rs.getBigDecimal("amount"),
                    rs.getString("currency_code"),
                    BaseTellerReturnedCheckStatus.valueOf(rs.getString("status")),
                    rs.getLong("office_id")));
    if (checks.isEmpty()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.returned.check.not.found", "Returned check not found.");
    }
    return checks.get(0);
  }

  private void validatePaymentType(final BaseTellerReturnedCheckPaymentRequest request) {
    final PaymentTypeData paymentType = paymentTypeReadService.retrieveOne(request.paymentTypeId());
    if (!Boolean.TRUE.equals(paymentType.getIsCashPayment())) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.returned.check.cash.payment.type.invalid",
          "Returned check payment requires a cash payment type.");
    }
  }

  private Long insertReturnedCheckSettlement(
      final BaseTellerReturnedCheckPaymentRequest request,
      final ReturnedCheckForSettlement returnedCheck,
      final AppUser user,
      final CashierData cashier,
      final String receiptNumber,
      final String fingerprint,
      final BigDecimal changeAmount) {
    jdbcTemplate.update(
        "INSERT INTO m_base_teller_returned_check_payment"
            + " (idempotency_key, request_fingerprint, receipt_number, returned_check_id,"
            + " cash_received, change_amount, currency_code, payment_type_id, status,"
            + " operator_id, office_id, teller_id, cashier_id, note)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        request.idempotencyKey(),
        fingerprint,
        receiptNumber,
        returnedCheck.id(),
        request.cashReceived(),
        changeAmount,
        request.currencyCode(),
        request.paymentTypeId(),
        BaseTellerReturnedCheckStatus.SETTLED.name(),
        user.getId(),
        user.getOffice().getId(),
        cashier.getTellerId(),
        cashier.getId(),
        request.note());
    return existingReturnedCheckSettlement(request.idempotencyKey()).id();
  }

  private void insertReturnedCheckSettlementDenominations(
      final Long settlementId, final List<BaseTellerDenominationData> denominations) {
    for (BaseTellerDenominationData denomination : denominations) {
      jdbcTemplate.update(
          "INSERT INTO m_base_teller_returned_check_payment_cash_detail"
              + " (settlement_id, denomination_identifier, denomination_value, quantity)"
              + " VALUES (?, ?, ?, ?)",
          settlementId,
          denomination.denominationId(),
          denomination.value(),
          denomination.quantity());
    }
  }

  private Long insertCashierCashIn(
      final BaseTellerReturnedCheckPaymentRequest request,
      final ReturnedCheckForSettlement returnedCheck,
      final AppUser user,
      final CashierData cashier,
      final Long settlementId) {
    jdbcTemplate.update(
        "INSERT INTO m_cashier_transactions"
            + " (cashier_id, txn_type, txn_date, txn_amount, txn_note, entity_type,"
            + " entity_id, currency_code)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        cashier.getId(),
        CASHIER_TXN_CASH_IN,
        StringUtils.defaultIfBlank(
            request.transactionDate(), DateUtils.getBusinessLocalDate().toString()),
        returnedCheck.amount(),
        StringUtils.defaultIfBlank(request.note(), "Returned check payment"),
        RETURNED_CHECK_RESOURCE,
        settlementId,
        request.currencyCode());
    return namedParameterJdbcTemplate.queryForObject(
        "SELECT MAX(id) FROM m_cashier_transactions"
            + " WHERE cashier_id = :cashierId AND txn_type = :txnType"
            + " AND entity_type = :entityType AND entity_id = :entityId",
        Map.of(
            "cashierId", cashier.getId(),
            "txnType", CASHIER_TXN_CASH_IN,
            "entityType", RETURNED_CHECK_RESOURCE,
            "entityId", settlementId),
        Long.class);
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

  private String accountJson(final BaseTellerSavingsOpeningRequest request) {
    final JsonObject json = request.savingsAccount().deepCopy();
    addOpeningDefaults(json, request);
    return GSON.toJson(json);
  }

  private String lifecycleJson(
      final String dateFieldName, final BaseTellerSavingsOpeningRequest request) {
    final JsonObject json = new JsonObject();
    addLocale(json, request.locale(), request.dateFormat());
    json.addProperty(dateFieldName, transactionDate(request));
    return GSON.toJson(json);
  }

  private String openingDepositJson(final BaseTellerSavingsOpeningRequest request) {
    final BaseTellerFundingData funding = request.initialFunding();
    final JsonObject json = new JsonObject();
    addLocale(json, request.locale(), request.dateFormat());
    json.addProperty("transactionDate", transactionDate(request));
    json.addProperty("transactionAmount", funding.amount());
    json.addProperty("paymentTypeId", funding.paymentTypeId());
    if (funding.type() == BaseTellerFundingType.CHECK) {
      json.addProperty("checkNumber", funding.check().checkNumber());
      json.addProperty("bankNumber", funding.check().bank());
      if (StringUtils.isNotBlank(funding.check().accountNumber())) {
        json.addProperty("accountNumber", funding.check().accountNumber());
      }
      if (StringUtils.isNotBlank(funding.check().routingCode())) {
        json.addProperty("routingCode", funding.check().routingCode());
      }
    }
    return GSON.toJson(json);
  }

  private void addOpeningDefaults(
      final JsonObject json, final BaseTellerSavingsOpeningRequest request) {
    addMatchingLong(json, "clientId", request.clientId());
    addMatchingLong(json, "productId", request.productId());
    addLocale(json, request.locale(), request.dateFormat());
  }

  private void addLocale(final JsonObject json, final String locale, final String dateFormat) {
    json.addProperty("locale", StringUtils.defaultIfBlank(locale, "en"));
    json.addProperty("dateFormat", StringUtils.defaultIfBlank(dateFormat, "yyyy-MM-dd"));
  }

  private void addMatchingLong(final JsonObject json, final String fieldName, final Long expected) {
    if (json.has(fieldName)
        && !json.get(fieldName).isJsonNull()
        && json.get(fieldName).getAsLong() != expected) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller." + fieldName + ".mismatch",
          fieldName + " must match the top-level value.");
    }
    json.addProperty(fieldName, expected);
  }

  private void validateApplicationIds(final BaseTellerSavingsOpeningRequest request) {
    addOpeningDefaults(request.savingsAccount().deepCopy(), request);
  }

  private void validatePaymentType(final BaseTellerFundingData funding) {
    if (funding == null) {
      return;
    }
    final PaymentTypeData paymentType = paymentTypeReadService.retrieveOne(funding.paymentTypeId());
    if (funding.type() == BaseTellerFundingType.CASH
        && !Boolean.TRUE.equals(paymentType.getIsCashPayment())) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.cash.payment.type.invalid",
          "Cash funding requires a cash payment type.");
    }
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

    final Long officeId = user.getOffice().getId();
    final Long staffId = user.getStaffId();
    final LocalDate today = DateUtils.getBusinessLocalDate();

    // Current Fineract API: no getCashierData(office, teller, staff, date).
    // Resolve via tellers for the office, then cashiers for each teller, filtered by staff.
    final Collection<TellerData> tellers = tellerManagementReadPlatformService.getTellers(officeId);
    final List<CashierData> matchingCashiers = new ArrayList<>();

    for (final TellerData teller : tellers) {
      final Collection<CashierData> cashiers =
          tellerManagementReadPlatformService.getCashiersForTeller(teller.getId(), today, today);
      for (final CashierData cashier : cashiers) {
        if (staffId.equals(cashier.getStaffId())) {
          matchingCashiers.add(cashier);
        }
      }
    }

    if (matchingCashiers.isEmpty()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.cashier.not.allocated",
          "Authenticated user is not allocated to an active cashier for this office.");
    }
    return matchingCashiers.get(0);
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

  private ExistingOpeningOperation existingOpeningOperation(final String idempotencyKey) {
    final List<ExistingOpeningOperation> operations =
        namedParameterJdbcTemplate.query(
            "SELECT id, request_fingerprint, receipt_number FROM m_base_teller_savings_opening"
                + " WHERE idempotency_key = :key",
            Map.of("key", idempotencyKey),
            (rs, row) ->
                new ExistingOpeningOperation(
                    rs.getLong("id"),
                    rs.getString("request_fingerprint"),
                    rs.getString("receipt_number")));
    return operations.isEmpty() ? null : operations.get(0);
  }

  private ExistingReturnedCheckSettlement existingReturnedCheckSettlement(
      final String idempotencyKey) {
    final List<ExistingReturnedCheckSettlement> operations =
        namedParameterJdbcTemplate.query(
            "SELECT id, request_fingerprint, receipt_number"
                + " FROM m_base_teller_returned_check_payment WHERE idempotency_key = :key",
            Map.of("key", idempotencyKey),
            (rs, row) ->
                new ExistingReturnedCheckSettlement(
                    rs.getLong("id"),
                    rs.getString("request_fingerprint"),
                    rs.getString("receipt_number")));
    return operations.isEmpty() ? null : operations.get(0);
  }

  private static String depositReceiptNumber(final String idempotencyKey) {
    final String sanitized = idempotencyKey.replaceAll("[^A-Za-z0-9-]", "-");
    return "BTD-" + StringUtils.abbreviate(sanitized, 96);
  }

  private static String openingReceiptNumber(final String idempotencyKey) {
    final String sanitized = idempotencyKey.replaceAll("[^A-Za-z0-9-]", "-");
    return "BTSA-" + StringUtils.abbreviate(sanitized, 95);
  }

  private static String returnedCheckReceiptNumber(final String idempotencyKey) {
    final String sanitized = idempotencyKey.replaceAll("[^A-Za-z0-9-]", "-");
    return "BTRC-" + StringUtils.abbreviate(sanitized, 95);
  }

  private static String transactionDate(final BaseTellerDepositRequest request) {
    return StringUtils.defaultIfBlank(
        request.transactionDate(), DateUtils.getBusinessLocalDate().toString());
  }

  private static String transactionDate(final BaseTellerSavingsOpeningRequest request) {
    return StringUtils.defaultIfBlank(
        request.transactionDate(), DateUtils.getBusinessLocalDate().toString());
  }

  private static org.apache.fineract.baseteller.data.BaseTellerCheckData check(
      final BaseTellerSavingsOpeningRequest request) {
    return request.initialFunding() == null ? null : request.initialFunding().check();
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
    return requestFingerprint((Object) request);
  }

  private static String requestFingerprint(final BaseTellerSavingsOpeningRequest request) {
    return requestFingerprint((Object) request);
  }

  private static String requestFingerprint(final BaseTellerReturnedCheckPaymentRequest request) {
    return requestFingerprint((Object) request);
  }

  private static String requestFingerprint(final Object request) {
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

  private static Long firstNonNull(final Long first, final Long second) {
    return first == null ? second : first;
  }

  private record ExistingOpeningOperation(
      Long id, String requestFingerprint, String receiptNumber) {}

  private record ExistingDepositOperation(
      Long id, String requestFingerprint, String receiptNumber) {}

  private record ExistingReturnedCheckSettlement(
      Long id, String requestFingerprint, String receiptNumber) {}

  private record ReturnedCheckForSettlement(
      Long id,
      BigDecimal amount,
      String currencyCode,
      BaseTellerReturnedCheckStatus status,
      Long officeId) {}
}
