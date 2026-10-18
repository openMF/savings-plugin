package org.apache.fineract.baseteller.withdrawal;

import static org.apache.fineract.baseteller.withdrawal.WithdrawalValidation.invalid;
import static org.apache.fineract.baseteller.withdrawal.WithdrawalValidation.quantity;

import com.google.gson.Gson;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.Cashier;
import org.apache.fineract.baseteller.service.CashExchangeInventory;
import org.apache.fineract.baseteller.withdrawal.WithdrawalData.*;
import org.apache.fineract.baseteller.withdrawal.WithdrawalRequest.Method;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.domain.ClientIdentifierStatus;
import org.apache.fineract.portfolio.client.domain.ClientStatus;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountBlockedException;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountDebitsBlockedException;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Metadata coordinates native accounting and physical custody within Fineract's transaction. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WithdrawalPlatformService {
  public static final String RESOURCE = "BASE_TELLER_WITHDRAWAL";
  public static final String CHECK_BLOCKER =
      "Outgoing check issuance is unavailable: an issuer/check stock catalog, issuer settlement"
          + " account mapping, and an authorized issue/settle/void lifecycle are not defined."
          + " Incoming check catalogs do not authorize issuance.";
  private static final Gson GSON = new Gson();
  private final PlatformSecurityContext security;
  private final JdbcTemplate jdbc;
  private final ClientReadPlatformService clients;
  private final SavingsAccountAssembler savings;
  private final EntityManager entityManager;
  private final org.apache.fineract.infrastructure.core.domain.FineractRequestContextHolder
      requestContext;
  private final org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDataValidator
      nativeValidator;
  private final PortfolioCommandSourceWritePlatformService commands;
  private final ConfigurationDomainService configuration;
  private final CashExchangeInventory inventory;

  public Context context() {
    AppUser actor = user("READ");
    LocalDate date = DateUtils.getBusinessLocalDate();
    Cashier cashier = source(actor, date);
    var permissions = new ArrayList<>(List.of("READ_" + RESOURCE));
    for (String action : List.of("CREATE", "REPRINT"))
      if (actor.hasAnyPermission(action + "_" + RESOURCE)) permissions.add(action + "_" + RESOURCE);
    if (actor.hasAnyPermission("WITHDRAWAL_SAVINGSACCOUNT"))
      permissions.add("WITHDRAWAL_SAVINGSACCOUNT");
    var currencies =
        jdbc.queryForList("SELECT code FROM m_organisation_currency ORDER BY code", String.class);
    var denominations = new ArrayList<AvailableDenomination>();
    for (String code : currencies) denominations.addAll(available(cashier, code, date));
    return new Context(
        date.toString(),
        cashier,
        List.of(Method.CASH),
        currencies,
        List.copyOf(permissions),
        jdbc.query(
            "SELECT cv.id,cv.code_value FROM m_code_value cv JOIN m_code c ON c.id=cv.code_id WHERE"
                + " c.code_name='Customer Identifier' AND cv.is_active=true ORDER BY"
                + " cv.order_position,cv.id",
            (rs, i) -> new Option(rs.getLong(1), rs.getString(2))),
        jdbc.query(
            "SELECT id,value FROM m_payment_type WHERE is_cash_payment=true ORDER BY"
                + " order_position,id",
            (rs, i) -> new Option(rs.getLong(1), rs.getString(2))),
        new CheckCapability(false, CHECK_BLOCKER),
        List.copyOf(denominations),
        true);
  }

  /** Outgoing check catalogs remain empty until eligible issuer configuration exists. */
  public List<Option> banks() {
    user("READ");
    return List.of();
  }

  public List<Option> checkTypes() {
    user("READ");
    return List.of();
  }

  public Page<Customer> customers(String number, String name, Integer offset, Integer limit) {
    AppUser actor = user("READ");
    actor.validateHasReadPermission("CLIENT");
    int start = offset == null ? 0 : offset, size = limit == null ? 25 : limit;
    if (start < 0
        || size < 1
        || size > 100
        || (number != null && number.length() > 100)
        || (name != null && name.length() > 100))
      throw invalid(
          "search.invalid", "Use offset >= 0, limit 1-100 and search text up to 100 characters.");
    if (number != null && !number.isBlank()) {
      var matches =
          jdbc
              .queryForList(
                  "SELECT c.id FROM m_client c JOIN m_office o ON o.id=c.office_id"
                      + " WHERE c.account_no=? AND o.hierarchy LIKE ?",
                  Long.class,
                  number.trim(),
                  hierarchy(actor))
              .stream()
              .map(id -> customer(id, actor))
              .toList();
      return new Page<>(start == 0 ? matches : List.of(), matches.size());
    }
    var page =
        clients.retrieveAll(
            SearchParameters.builder()
                .name(name)
                .offset(start)
                .limit(size)
                .orderBy("id")
                .sortOrder("ASC")
                .build());
    return new Page<>(
        page.getPageItems().stream().map(this::safeCustomer).toList(),
        page.getTotalFilteredRecords());
  }

  @Transactional
  public List<Account> accounts(Long clientId) {
    AppUser actor = user("READ");
    customer(clientId, actor);
    return jdbc
        .queryForList(
            "SELECT id FROM m_savings_account WHERE client_id=? AND deposit_type_enum=? ORDER BY"
                + " id",
            Long.class,
            clientId,
            DepositAccountType.SAVINGS_DEPOSIT.getValue())
        .stream()
        .map(this::account)
        .filter(Account::withdrawalEligible)
        .toList();
  }

  @Transactional
  public Account account(Long id) {
    AppUser actor = user("READ");
    SavingsAccount a = domain(id, actor, false);
    return accountData(a, customer(a.getClient().getId(), actor));
  }

  @Transactional
  public WithdrawalData preview(WithdrawalRequest request) {
    return prepare(request, user("CREATE"), false);
  }

  @Transactional
  public WithdrawalData create(WithdrawalRequest request) {
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    AppUser actor = user("CREATE");
    WithdrawalValidation.validate(request, true);
    jdbc.queryForObject("SELECT id FROM m_cash_exchange_mutex WHERE id=1 FOR UPDATE", Long.class);
    String fingerprint = WithdrawalValidation.fingerprint(request, actor.getId());
    var previous =
        jdbc.query(
            "SELECT id,request_fingerprint,created_by FROM m_base_teller_withdrawal WHERE"
                + " idempotency_key=?",
            (rs, i) -> new Existing(rs.getLong(1), rs.getString(2), rs.getLong(3)),
            request.idempotencyKey());
    if (!previous.isEmpty()) {
      var old = previous.getFirst();
      if (!old.actor().equals(actor.getId()) || !old.fingerprint().equals(fingerprint))
        throw invalid(
            "idempotency.conflict",
            "Idempotency key belongs to a different withdrawal or operator.");
      return stored(old.id(), actor);
    }
    lockInventory();
    WithdrawalData prepared = prepare(request, actor, true);
    String reference = "WD-" + UUID.randomUUID();
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO m_base_teller_withdrawal(idempotency_key,request_fingerprint,reference,status,business_date,
              client_id,account_id,office_id,teller_id,cashier_id,created_by,method,amount,currency_code,
              identifier_id,identification_type_id,identification_mask,created_at,snapshot)
            VALUES (?,?,?,'PROCESSING',?,?,?,?,?,?,?,'CASH',?,?,?,?,?,CURRENT_TIMESTAMP,'{}') RETURNING id
            """,
            Long.class,
            request.idempotencyKey(),
            fingerprint,
            reference,
            LocalDate.parse(prepared.businessDate()),
            prepared.customer().clientId(),
            request.accountId(),
            prepared.cashier().officeId(),
            prepared.cashier().tellerId(),
            prepared.cashier().cashierId(),
            actor.getId(),
            prepared.amount(),
            prepared.currencyCode(),
            prepared.recipient().identifierId(),
            prepared.recipient().identificationTypeId(),
            prepared.recipient().maskedReference());
    for (var d : prepared.denominations())
      jdbc.update(
          "INSERT INTO"
              + " m_base_teller_withdrawal_cash_detail(withdrawal_id,denomination_identifier,denomination_type,denomination_value,quantity,line_total)"
              + " VALUES (?,?,?,?,?,?)",
          id,
          d.denominationId(),
          d.denominationType(),
          d.denominationValue(),
          d.quantity(),
          d.lineAmount());
    Map<String, Object> body =
        Map.of(
            "locale",
            "en",
            "dateFormat",
            "yyyy-MM-dd",
            "transactionDate",
            prepared.businessDate(),
            "transactionAmount",
            prepared.amount(),
            "paymentTypeId",
            request.paymentTypeId(),
            "note",
            reference);
    var result = postNative(request, body);
    if (result == null || result.getResourceId() == null || result.isRollbackTransaction())
      throw invalid("posting.incomplete", "Native withdrawal must complete synchronously.");
    entityManager.flush();
    Long nativeId = result.getResourceId();
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT COUNT(*)=1 FROM m_savings_account_transaction WHERE id=? AND"
                + " savings_account_id=? AND transaction_type_enum=2 AND amount=? AND"
                + " transaction_date=? AND is_reversed=false",
            Boolean.class,
            nativeId,
            request.accountId(),
            prepared.amount(),
            LocalDate.parse(prepared.businessDate()))))
      throw invalid("posting.invalid", "Native withdrawal evidence does not match this operation.");
    // Fineract teller summaries already project this savings withdrawal as native cash out.
    // Do not settle to the vault or insert a second cashier cash-out transaction.
    String completedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
    Account a = prepared.account();
    Account historical =
        new Account(
            a.accountId(),
            a.accountNumber(),
            a.productId(),
            a.productName(),
            a.currencyCode(),
            a.decimalPlaces(),
            a.status(),
            null,
            false,
            List.of());
    WithdrawalData completed =
        new WithdrawalData(
            id,
            reference,
            "COMPLETED",
            prepared.businessDate(),
            completedAt,
            prepared.customer(),
            historical,
            prepared.cashier(),
            actor.getId(),
            actor.getUsername(),
            Method.CASH,
            prepared.amount(),
            prepared.currencyCode(),
            prepared.denominations(),
            prepared.recipient(),
            prepared.amount(),
            prepared.amount(),
            Map.of(),
            prepared.outflowsByCurrency(),
            nativeId,
            null,
            false);
    jdbc.update(
        "UPDATE m_base_teller_withdrawal SET"
            + " status='COMPLETED',completed_at=?,savings_transaction_id=?,snapshot=?"
            + " WHERE id=?",
        OffsetDateTime.parse(completedAt),
        nativeId,
        GSON.toJson(completed),
        id);
    // The combined native cash total and recorded denomination custody must still reconcile.
    inventory.quantities(
        prepared.cashier().cashierId(),
        prepared.currencyCode(),
        LocalDate.parse(prepared.businessDate()));
    return completed;
  }

  public WithdrawalData retrieve(Long id) {
    return stored(id, user("READ"));
  }

  public WithdrawalData receipt(Long id) {
    return stored(id, user("REPRINT"));
  }

  private WithdrawalData prepare(WithdrawalRequest r, AppUser actor, boolean refresh) {
    WithdrawalValidation.validate(r, false);
    if (configuration.isMakerCheckerEnabledForTask("WITHDRAWAL_SAVINGSACCOUNT"))
      throw invalid(
          "maker.checker.unsupported",
          "Cash delivery requires synchronous posting; native maker-checker withdrawal is"
              + " enabled.");
    LocalDate date = DateUtils.getBusinessLocalDate();
    Cashier cashier = source(actor, date);
    Customer customer = customer(r.clientId(), actor);
    SavingsAccount a = domain(r.accountId(), actor, refresh);
    if (!Objects.equals(a.getClient().getId(), r.clientId()))
      throw invalid("account.owner", "Source account must belong to the selected customer.");
    nativeValidator.validateTransactionWithPivotDate(date, a);
    Account account = accountData(a, customer);
    if (!account.withdrawalEligible())
      throw invalid("account.ineligible", "Source account is ineligible for cash withdrawal.");
    if (r.amount().stripTrailingZeros().scale() > account.decimalPlaces())
      throw invalid("amount.precision", "Amount exceeds the account currency precision.");
    if (a.getCurrency().getInMultiplesOf() != null
        && a.getCurrency().getInMultiplesOf() > 0
        && r.amount()
                .remainder(
                    BigDecimal.valueOf(a.getCurrency().getInMultiplesOf())
                        .movePointLeft(account.decimalPlaces()))
                .signum()
            != 0)
      throw invalid("amount.multiple", "Amount must match the account currency increment.");
    if (account.availableAmount().compareTo(r.amount()) < 0)
      throw invalid("funds.insufficient", "Amount exceeds the native withdrawable balance.");
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT COUNT(*)=1 FROM m_payment_type WHERE id=? AND is_cash_payment=true",
            Boolean.class,
            r.paymentTypeId())))
      throw invalid("payment.type", "A configured cash payment type is required.");
    Identification identity = identification(r, date);
    var catalog = available(cashier, account.currencyCode(), date);
    var lines = new ArrayList<Denomination>();
    for (var selection : r.denominations()) {
      var d =
          catalog.stream()
              .filter(item -> item.denominationId().equals(selection.denominationId()))
              .findFirst()
              .orElseThrow(
                  () ->
                      invalid(
                          "denomination.invalid",
                          "Denomination is disabled, unknown or belongs to another currency."));
      long q = quantity(selection.quantity());
      if (q > d.availableQuantity())
        throw invalid(
            "inventory.insufficient", "The requested denomination quantity is unavailable.");
      if (q > 0)
        lines.add(
            new Denomination(
                d.denominationId(),
                d.currencyCode(),
                d.denominationType(),
                d.denominationValue(),
                q,
                d.denominationValue().multiply(BigDecimal.valueOf(q))));
    }
    BigDecimal total =
        lines.stream().map(Denomination::lineAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.compareTo(r.amount()) != 0)
      throw invalid(
          "denomination.total",
          "Cash denomination total must exactly equal the withdrawal amount.");
    return new WithdrawalData(
        null,
        null,
        "PREVIEW",
        date.toString(),
        null,
        customer,
        account,
        cashier,
        actor.getId(),
        actor.getUsername(),
        Method.CASH,
        r.amount(),
        account.currencyCode(),
        List.copyOf(lines),
        identity,
        r.amount(),
        r.amount(),
        Map.of(),
        Map.of(account.currencyCode(), r.amount()),
        null,
        null,
        false);
  }

  private SavingsAccount domain(Long id, AppUser actor, boolean refresh) {
    actor.validateHasReadPermission("savingsaccount");
    if (id == null
        || id <= 0
        || !Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT COUNT(*)=1 FROM m_savings_account a JOIN m_client c ON c.id=a.client_id"
                    + " JOIN m_office o ON o.id=c.office_id WHERE a.id=? AND o.hierarchy LIKE ?",
                Boolean.class,
                id,
                hierarchy(actor))))
      throw invalid("account.not.found", "Account not found in the current office scope.");
    // The plugin also holds the native account table lock during final execution. Refresh after
    // locking so EclipseLink cannot reuse a pre-lock account snapshot.
    if (refresh)
      jdbc.queryForObject("SELECT id FROM m_savings_account WHERE id=? FOR UPDATE", Long.class, id);
    SavingsAccount account = savings.assembleFrom(id, false);
    if (refresh) entityManager.refresh(account);
    return account;
  }

  private Account accountData(SavingsAccount a, Customer owner) {
    var restrictions = new ArrayList<String>();
    if (!owner.eligible()) restrictions.add("ACTIVE_INDIVIDUAL_CUSTOMER_REQUIRED");
    if (!a.isActive()) restrictions.add("ACCOUNT_INACTIVE");
    if (a.depositAccountType() != DepositAccountType.SAVINGS_DEPOSIT || !a.allowWithdrawal())
      restrictions.add("WITHDRAWAL_NOT_ALLOWED");
    try {
      a.validateForAccountBlock();
      a.validateForDebitBlock();
    } catch (SavingsAccountBlockedException | SavingsAccountDebitsBlockedException e) {
      restrictions.add("DEBITS_BLOCKED");
    }
    LocalDate date = DateUtils.getBusinessLocalDate();
    if (a.getActivationDate() == null
        || date.isBefore(a.getActivationDate())
        || (a.getLockedInUntilDate() != null && !date.isAfter(a.getLockedInUntilDate())))
      restrictions.add("ACCOUNT_DATE_RESTRICTED");
    if (a.charges().stream().anyMatch(c -> c.isActive() && c.isWithdrawalFee()))
      restrictions.add("WITHDRAWAL_FEE_UNSUPPORTED");
    if (a.getGsim() != null || a.groupId() != null) restrictions.add("GROUP_ACCOUNT_UNSUPPORTED");
    if (a.getCurrency().getDigitsAfterDecimal() > 6
        || !Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT COUNT(*)=1 FROM m_organisation_currency WHERE code=?",
                Boolean.class,
                a.getCurrency().getCode()))) restrictions.add("CURRENCY_UNSUPPORTED");
    BigDecimal available = a.getWithdrawableBalance();
    if (available.signum() <= 0) restrictions.add("NO_WITHDRAWABLE_FUNDS");
    return new Account(
        a.getId(),
        a.getAccountNumber(),
        a.getSavingsProductId(),
        a.savingsProduct().getName(),
        a.getCurrency().getCode(),
        a.getCurrency().getDigitsAfterDecimal(),
        a.getStatus().name(),
        available,
        restrictions.isEmpty(),
        List.copyOf(restrictions));
  }

  private Customer customer(Long id, AppUser actor) {
    actor.validateHasReadPermission("CLIENT");
    if (id == null
        || id <= 0
        || !Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT COUNT(*)=1 FROM m_client c"
                    + " JOIN m_office o ON o.id=c.office_id WHERE c.id=? AND o.hierarchy LIKE ?",
                Boolean.class,
                id,
                hierarchy(actor))))
      throw invalid("customer.not.found", "Customer not found in the current office scope.");
    return safeCustomer(clients.retrieveOne(id));
  }

  private Customer safeCustomer(ClientData c) {
    return new Customer(
        c.getId(),
        c.getAccountNo(),
        c.getDisplayName(),
        c.getLegalForm() == null ? null : c.getLegalForm().getValue(),
        c.getStatus().getValue(),
        c.getOfficeId(),
        c.getOfficeName(),
        c.getStatus().getId().equals(ClientStatus.ACTIVE.getValue().longValue())
            && c.getLegalForm() != null
            && c.getLegalForm()
                .getId()
                .equals(
                    org.apache.fineract.portfolio.client.domain.LegalForm.PERSON
                        .getValue()
                        .longValue()));
  }

  private Identification identification(WithdrawalRequest r, LocalDate date) {
    var rows =
        jdbc.query(
            "SELECT i.id,cv.code_value FROM m_client_identifier i JOIN m_code_value cv ON"
                + " cv.id=i.document_type_id JOIN m_code c ON c.id=cv.code_id WHERE i.client_id=?"
                + " AND i.document_type_id=? AND i.document_key=? AND i.status=? AND i.active=? AND"
                + " cv.is_active=true AND c.code_name='Customer Identifier' AND (i.issuance_date IS"
                + " NULL OR i.issuance_date<=?) AND (i.expiry_date IS NULL OR i.expiry_date>=?)",
            (rs, i) ->
                new Identification(
                    rs.getLong(1),
                    r.identificationTypeId(),
                    rs.getString(2),
                    WithdrawalValidation.mask(r.identificationReference())),
            r.clientId(),
            r.identificationTypeId(),
            r.identificationReference(),
            ClientIdentifierStatus.ACTIVE.getValue(),
            ClientIdentifierStatus.ACTIVE.getValue(),
            date,
            date);
    if (rows.size() != 1)
      throw invalid(
          "identification.invalid",
          "Identification must match the customer's active, unexpired registered identifier.");
    return rows.getFirst();
  }

  private List<AvailableDenomination> available(Cashier cashier, String code, LocalDate date) {
    var quantities = inventory.quantities(cashier.cashierId(), code, date);
    return jdbc.query(
        "SELECT d.identifier,d.denomination_type,d.value,c.decimal_places FROM"
            + " m_service_payment_denomination d JOIN m_organisation_currency c ON"
            + " c.code=d.currency_code WHERE d.currency_code=? AND d.active=true ORDER BY d.value"
            + " DESC,d.identifier",
        (rs, i) -> {
          BigDecimal value = rs.getBigDecimal(3);
          String type = rs.getString(2);
          if (value.signum() <= 0
              || value.stripTrailingZeros().scale() > rs.getInt(4)
              || !List.of("BANKNOTE", "BILL", "NOTE", "COIN").contains(type))
            throw invalid("denomination.configuration", "Invalid configured cash denomination.");
          return new AvailableDenomination(
              rs.getString(1),
              code,
              "COIN".equals(type) ? "COIN" : "BANKNOTE",
              value,
              quantities.getOrDefault(rs.getString(1), 0L));
        },
        code);
  }

  private Cashier source(AppUser actor, LocalDate date) {
    if (actor.getStaffId() == null)
      throw invalid("source.forbidden", "Authenticated staff cashier assignment is required.");
    var rows =
        jdbc.query(
            """
            SELECT c.id,c.staff_id,s.display_name,t.id,t.name,o.id,o.name FROM m_cashiers c
            JOIN m_tellers t ON t.id=c.teller_id JOIN m_staff s ON s.id=c.staff_id JOIN m_office o ON o.id=t.office_id
            WHERE c.staff_id=? AND o.hierarchy LIKE ? AND s.is_active=true AND t.state=300
            AND (t.valid_from IS NULL OR t.valid_from<=?) AND (t.valid_to IS NULL OR t.valid_to>=?)
            AND (c.start_date IS NULL OR c.start_date<=?) AND (c.end_date IS NULL OR c.end_date>=?)
            AND (c.full_day=true OR (c.full_day=false AND CAST(? AS time) BETWEEN CAST(c.start_time AS time) AND CAST(c.end_time AS time)))
            AND NOT EXISTS(SELECT 1 FROM m_cashier_reconciliation r WHERE r.cashier_id=c.id AND r.business_date=?)
            AND EXISTS(SELECT 1 FROM m_cash_allocation a WHERE a.destination_cashier_id=c.id AND a.business_date=? AND a.status='COMPLETED' AND a.amount>0)
            ORDER BY c.id
            """,
            (rs, i) ->
                new Cashier(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getString(3),
                    rs.getLong(4),
                    rs.getString(5),
                    rs.getLong(6),
                    rs.getString(7)),
            actor.getStaffId(),
            hierarchy(actor),
            date,
            date,
            date,
            date,
            DateUtils.getOffsetDateTimeOfTenant().toLocalTime(),
            date,
            date);
    if (rows.size() != 1)
      throw invalid(
          "source.assignment",
          "Exactly one active open funded drawer is required for the authenticated staff member.");
    return rows.getFirst();
  }

  private WithdrawalData stored(Long id, AppUser actor) {
    var rows =
        jdbc.query(
            "SELECT w.snapshot,st.is_reversed FROM m_base_teller_withdrawal w JOIN m_office o ON"
                + " o.id=w.office_id JOIN m_cashiers c ON c.id=w.cashier_id JOIN"
                + " m_savings_account_transaction st ON st.id=w.savings_transaction_id WHERE w.id=?"
                + " AND w.status='COMPLETED' AND o.hierarchy LIKE ? AND (w.created_by=? OR"
                + " c.staff_id=? OR ?)",
            (rs, i) -> {
              if (rs.getBoolean(2))
                throw invalid(
                    "reconciliation.required",
                    "Native withdrawal has been reversed outside the custody workflow; reconcile"
                        + " custody before reprinting.");
              return GSON.fromJson(rs.getString(1), WithdrawalData.class);
            },
            id,
            hierarchy(actor),
            actor.getId(),
            actor.getStaffId(),
            actor.hasAnyPermission("READ_GLOBAL_SETTLEMENT"));
    if (rows.isEmpty())
      throw invalid("not.found", "Withdrawal not found in the authenticated user's scope.");
    return rows.getFirst();
  }

  private AppUser user(String action) {
    AppUser actor = security.authenticatedUser();
    actor.validateHasPermissionTo(action + "_" + RESOURCE);
    actor.validateHasReadPermission("CLIENT");
    actor.validateHasReadPermission("savingsaccount");
    if ("CREATE".equals(action)) actor.validateHasPermissionTo("WITHDRAWAL_SAVINGSACCOUNT");
    if (actor.getOffice() == null || actor.getOffice().getHierarchy() == null)
      throw invalid("office.forbidden", "An authorized office is required.");
    return actor;
  }

  private static String hierarchy(AppUser actor) {
    return actor.getOffice().getHierarchy() + "%";
  }

  private void lockInventory() {
    // Same global mutex and table order as exchange/pickup/transfers/closing. Existing writers do
    // not all use a per-drawer protocol; coarse PostgreSQL locks prevent unrecorded composition
    // races.
    jdbc.execute(
        "LOCK TABLE m_cash_allocation,m_cash_allocation_denomination,m_cashiers,m_tellers,"
            + "m_cashier_transactions,m_cashier_reconciliation,m_cashier_reconciliation_check,"
            + "m_cash_operation_transaction,m_cash_operation_denomination,m_base_teller_deposit,"
            + "m_base_teller_deposit_cash_detail,m_base_teller_savings_opening,m_base_teller_savings_opening_cash_detail,"
            + "m_base_teller_credit_payment,m_base_teller_credit_payment_cash_detail,m_base_teller_returned_check_payment,"
            + "m_base_teller_returned_check_payment_cash_detail,m_service_payment,m_service_payment_cash_detail,"
            + "m_savings_account_transaction,m_cash_exchange,m_cash_exchange_detail,"
            + "m_transaction_reversal,m_transaction_reversal_denomination,m_cash_drawer_pickup,"
            + "m_cash_drawer_pickup_currency,m_cash_drawer_pickup_cash,m_cash_teller_transfer,"
            + "m_cash_teller_transfer_currency,m_cash_teller_transfer_denomination,m_service_payment_denomination,m_organisation_currency,m_staff,"
            + "m_base_teller_withdrawal,m_base_teller_withdrawal_cash_detail,m_savings_account,m_client,m_client_identifier,m_code,m_code_value,m_payment_type"
            + " IN SHARE ROW EXCLUSIVE MODE");
  }

  private org.apache.fineract.infrastructure.core.data.CommandProcessingResult postNative(
      WithdrawalRequest request, Map<String, Object> body) {
    String attribute =
        org.apache.fineract.commands.service.SynchronousCommandProcessingService.COMMAND_SOURCE_ID;
    Object enclosingId = requestContext.getAttribute(attribute, null);
    boolean enclosing =
        org.apache.fineract.infrastructure.core.domain.BatchRequestContextHolder
            .isEnclosingTransaction();
    try {
      requestContext.setAttribute(attribute, null);
      org.apache.fineract.infrastructure.core.domain.BatchRequestContextHolder
          .setIsEnclosingTransaction(true);
      return commands.logCommandSource(
          new CommandWrapperBuilder()
              .savingsAccountWithdrawal(request.accountId())
              .withJson(GSON.toJson(body))
              .build(
                  UUID.nameUUIDFromBytes(
                          ("WEB-1263:" + request.idempotencyKey())
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                      .toString()));
    } finally {
      requestContext.setAttribute(attribute, enclosingId);
      org.apache.fineract.infrastructure.core.domain.BatchRequestContextHolder
          .setIsEnclosingTransaction(enclosing);
    }
  }

  private record Existing(Long id, String fingerprint, Long actor) {}
}
