package org.apache.fineract.baseteller.transfer;

import static org.apache.fineract.baseteller.transfer.TransferValidation.invalid;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.teller.domain.TellerStatus;
import org.apache.fineract.portfolio.account.data.AccountTransferData;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.domain.ClientStatus;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountBlockedException;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountCreditsBlockedException;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountDebitsBlockedException;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates discovery and native CREATE_ACCOUNTTRANSFER; never posts a financial leg itself. */
@Service
@RequiredArgsConstructor
@Transactional
public class AccountTransferPlatformService {
  private static final Gson GSON = new Gson();
  private final PlatformSecurityContext security;
  private final JdbcTemplate jdbc;
  private final ClientReadPlatformService clients;
  private final SavingsAccountAssembler savings;
  private final LoanAssembler loans;
  private final EntityManager entityManager;
  private final PortfolioCommandSourceWritePlatformService commands;
  private final AccountTransfersReadPlatformService transfers;

  public TransferData.Context context() {
    AppUser actor = user(false);
    List<String> permissions = new ArrayList<>(List.of("READ_ACCOUNTTRANSFER"));
    if (actor.hasAnyPermission("CREATE_ACCOUNTTRANSFER")) permissions.add("CREATE_ACCOUNTTRANSFER");
    return new TransferData.Context(
        DateUtils.getBusinessLocalDate().toString(),
        actor.getOffice().getId(),
        actor.getOffice().getName(),
        cashier(actor),
        List.of(TransferOperation.values()),
        List.of(TransferAccountType.SAVINGS),
        List.of(TransferAccountType.values()),
        jdbc.queryForList("SELECT code FROM m_organisation_currency ORDER BY code", String.class),
        permissions,
        true,
        false);
  }

  public Page<TransferData.Client> searchClients(
      String query, String clientNumber, String name, Integer offset, Integer limit) {
    user(false).validateHasReadPermission("CLIENT");
    int start = offset == null ? 0 : offset;
    int size = limit == null ? 25 : limit;
    if (start < 0 || size < 1 || size > 100)
      throw invalid("pagination", "Use offset >= 0 and limit 1-100.");
    if (clientNumber != null && !clientNumber.isBlank()) {
      // Native name search has no account-number predicate. Resolve the exact native identifier,
      // then use the same native client reader and safe projection as name discovery.
      List<Long> ids =
          jdbc.queryForList(
              "SELECT c.id FROM m_client c JOIN m_office o ON o.id=c.office_id"
                  + " WHERE c.account_no=? AND o.hierarchy LIKE ?",
              Long.class,
              clientNumber.trim(),
              hierarchy());
      List<TransferData.Client> matches = ids.stream().map(this::client).toList();
      return new Page<>(start == 0 ? matches : List.of(), matches.size());
    }
    String search = name == null || name.isBlank() ? query : name;
    if (search != null && search.length() > 100)
      throw invalid("search", "Search is limited to 100 characters.");
    Page<ClientData> page =
        clients.retrieveAll(
            SearchParameters.builder()
                .name(search)
                .offset(start)
                .limit(size)
                .orderBy("id")
                .sortOrder("ASC")
                .build());
    return new Page<>(
        page.getPageItems().stream().map(this::safeClient).toList(),
        page.getTotalFilteredRecords());
  }

  /** Returns participating accounts with server-derived debit/credit flags. */
  public List<TransferData.Account> accounts(Long clientId) {
    user(false);
    client(clientId);
    List<TransferData.Account> result = new ArrayList<>();
    for (Long id :
        jdbc.queryForList(
            "SELECT id FROM m_savings_account WHERE client_id=? ORDER BY id",
            Long.class,
            clientId)) {
      TransferData.Account account = savingsAccount(id, false);
      if (account.canDebit() || account.canCredit()) result.add(account);
    }
    for (Long id :
        jdbc.queryForList(
            "SELECT id FROM m_loan WHERE client_id=? ORDER BY id", Long.class, clientId)) {
      TransferData.Account account = loanAccount(id, false);
      if (account.canCredit()) result.add(account);
    }
    return result;
  }

  public List<TransferData.Account> sources(Long clientId) {
    user(false);
    client(clientId);
    return jdbc
        .queryForList(
            "SELECT id FROM m_savings_account WHERE client_id=? ORDER BY id", Long.class, clientId)
        .stream()
        .map(id -> savingsAccount(id, false))
        .filter(TransferData.Account::canDebit)
        .toList();
  }

  public List<TransferData.Account> destinations(
      Long orderingClientId,
      Long sourceAccountId,
      TransferOperation operation,
      Long beneficiaryClientId) {
    user(false);
    if (operation == null) throw invalid("operation", "operationType is required.");
    TransferData.Account source = savingsAccount(sourceAccountId, false);
    if (!source.canDebit() || !source.owner().clientId().equals(orderingClientId)) {
      throw invalid("source.owner", "Source must be an eligible account of the ordering client.");
    }
    Long targetClient =
        operation == TransferOperation.BETWEEN_MY_ACCOUNTS ? orderingClientId : beneficiaryClientId;
    if (targetClient == null
        || ((operation == TransferOperation.BETWEEN_MY_ACCOUNTS)
            != orderingClientId.equals(targetClient)))
      throw invalid("ownership", "Select a beneficiary consistent with operationType.");
    return accounts(targetClient).stream()
        .filter(TransferData.Account::canCredit)
        .filter(
            a ->
                a.accountType() != TransferAccountType.SAVINGS
                    || !a.accountId().equals(sourceAccountId))
        .filter(a -> a.currencyCode().equals(source.currencyCode()))
        .toList();
  }

  public TransferData.Account lookup(
      String accountNo, Long orderingClientId, Long sourceAccountId, TransferOperation operation) {
    user(false);
    if (accountNo == null || accountNo.isBlank() || accountNo.length() > 100) {
      throw invalid("account.number", "An exact accountNumber is required.");
    }
    List<AccountKey> matches = new ArrayList<>();
    for (TransferAccountType type : TransferAccountType.values()) {
      matches.addAll(
          jdbc.query(
              "SELECT a.id FROM "
                  + table(type)
                  + " a JOIN m_client c ON c.id=a.client_id JOIN m_office o ON o.id=c.office_id"
                  + " WHERE a.account_no=? AND o.hierarchy LIKE ?",
              (rs, i) -> new AccountKey(type, rs.getLong(1)),
              accountNo.trim(),
              hierarchy()));
    }
    // Numbers need not be unique across savings and loan tables. Never guess the account type.
    if (matches.size() != 1)
      throw invalid("account.lookup", "No unique visible destination matches that account number.");
    AccountKey key = matches.get(0);
    TransferData.Account target = account(key, false);
    if (!destinations(orderingClientId, sourceAccountId, operation, target.owner().clientId())
        .stream()
        .anyMatch(a -> a.accountType() == key.type() && a.accountId().equals(key.id()))) {
      throw invalid(
          "target.ineligible", "Account is not an eligible destination for this transfer.");
    }
    return target;
  }

  public TransferData.Preview preview(TransferRequest request) {
    user(true);
    return prepare(request, false);
  }

  @Transactional
  public TransferData.Receipt create(TransferRequest request) {
    // The native EclipseLink transaction manager rejects non-default annotation isolation.
    // Set it on its shared JDBC connection before the first data read (existing plugin convention).
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
    AppUser actor = user(true);
    TransferValidation.request(request, true);
    // Stable ordering prevents A->B / B->A deadlocks. Reads after these locks see committed funds.
    List<AccountKey> keys =
        new ArrayList<>(
            List.of(
                new AccountKey(TransferAccountType.SAVINGS, request.sourceAccountId()),
                new AccountKey(request.targetAccountType(), request.targetAccountId())));
    keys.stream()
        .distinct()
        .sorted(
            Comparator.comparing((AccountKey k) -> k.type().name()).thenComparing(AccountKey::id))
        .forEach(key -> lockAccount(key));
    String fingerprint = fingerprint(request);
    List<Existing> existing =
        jdbc.query(
            "SELECT native_transfer_id,request_fingerprint,operator_id FROM"
                + " m_base_teller_account_transfer WHERE idempotency_key=?",
            (rs, i) -> new Existing(rs.getLong(1), rs.getString(2), rs.getLong(3)),
            request.idempotencyKey());
    if (!existing.isEmpty()) {
      Existing old = existing.get(0);
      if (!old.fingerprint().equals(fingerprint) || !old.operatorId().equals(actor.getId())) {
        throw invalid(
            "idempotency.conflict", "Idempotency key belongs to a different request or operator.");
      }
      return stored(old.transferId(), actor);
    }
    TransferData.Preview prepared = prepare(request, true);
    TransferData.Cashier cashier = cashier(actor);
    JsonObject nativeRequest = nativeRequest(request, prepared);
    CommandProcessingResult posted =
        commands.logCommandSource(
            new CommandWrapperBuilder()
                .createAccountTransfer()
                .withJson(GSON.toJson(nativeRequest))
                .build(
                    UUID.nameUUIDFromBytes(
                            ("WEB-1261:" + request.idempotencyKey())
                                .getBytes(StandardCharsets.UTF_8))
                        .toString()));
    // Native create returns a details ID. Retrieval accepts the linked transaction ID.
    Long detailId = posted.getResourceId();
    if (detailId == null || Boolean.TRUE.equals(posted.getRollbackTransaction())) {
      throw invalid(
          "not.posted", "Native transfer was not posted; no completed receipt can be issued.");
    }
    List<Long> ids =
        jdbc.queryForList(
            "SELECT id FROM m_account_transfer_transaction WHERE account_transfer_details_id=?",
            Long.class,
            detailId);
    if (ids.size() != 1)
      throw invalid("native.link", "Native transfer must have exactly one linked transaction.");
    Long id = ids.get(0);
    AccountTransferData nativeTransfer = transfers.retrieveOne(id);
    List<Long[]> legs =
        jdbc.query(
            "SELECT"
                + " from_savings_transaction_id,COALESCE(to_savings_transaction_id,to_loan_transaction_id)"
                + " FROM m_account_transfer_transaction WHERE id=?",
            (rs, i) -> new Long[] {rs.getLong(1), rs.getLong(2)},
            id);
    String completed =
        jdbc.queryForObject("SELECT CAST(CURRENT_TIMESTAMP AS varchar)", String.class);
    TransferData.Receipt receipt =
        new TransferData.Receipt(
            id,
            detailId,
            id.toString(),
            nativeTransfer.getTransferDate().toString(),
            completed,
            "COMPLETED",
            request.operationType(),
            prepared.orderingClient(),
            prepared.beneficiaryClient(),
            snapshot(prepared.source()),
            snapshot(prepared.destination()),
            nativeTransfer.getTransferAmount(),
            nativeTransfer.getCurrency().getCode(),
            legs.get(0)[0],
            legs.get(0)[1],
            actor.getId(),
            actor.getUsername(),
            cashier == null ? null : cashier.tellerId(),
            cashier == null ? null : cashier.cashierId());
    jdbc.update(
        "INSERT INTO m_base_teller_account_transfer"
            + " (native_transfer_id,native_detail_id,idempotency_key,request_fingerprint,operation_type,operator_id,office_id,teller_id,cashier_id,receipt_json)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
        id,
        detailId,
        request.idempotencyKey(),
        fingerprint,
        request.operationType().name(),
        actor.getId(),
        actor.getOffice().getId(),
        receipt.tellerId(),
        receipt.cashierId(),
        GSON.toJson(receipt));
    return receipt;
  }

  @Transactional(readOnly = true)
  public TransferData.Receipt retrieve(Long id) {
    return stored(id, user(false));
  }

  private TransferData.Receipt stored(Long id, AppUser actor) {
    List<TransferData.Receipt> rows =
        jdbc.query(
            "SELECT w.receipt_json,t.is_reversed,d.from_office_id,d.to_office_id FROM"
                + " m_base_teller_account_transfer w JOIN m_account_transfer_transaction t ON"
                + " t.id=w.native_transfer_id JOIN m_account_transfer_details d ON"
                + " d.id=t.account_transfer_details_id JOIN m_office o ON o.id=w.office_id JOIN"
                + " m_office f ON f.id=d.from_office_id JOIN m_office b ON b.id=d.to_office_id"
                + " WHERE w.native_transfer_id=? AND o.hierarchy LIKE ? AND f.hierarchy LIKE ? AND"
                + " b.hierarchy LIKE ?",
            (rs, i) -> {
              TransferData.Receipt r = GSON.fromJson(rs.getString(1), TransferData.Receipt.class);
              return new TransferData.Receipt(
                  r.transferId(),
                  r.transferDetailId(),
                  r.reference(),
                  r.businessDate(),
                  r.completedAt(),
                  rs.getBoolean(2) ? "REVERSED" : "COMPLETED",
                  r.operationType(),
                  r.orderingClient(),
                  r.beneficiaryClient(),
                  r.source(),
                  r.destination(),
                  r.amount(),
                  r.currencyCode(),
                  r.sourceTransactionId(),
                  r.destinationTransactionId(),
                  r.operatorId(),
                  r.operatorName(),
                  r.tellerId(),
                  r.cashierId());
            },
            id,
            actor.getOffice().getHierarchy() + "%",
            hierarchy(),
            hierarchy());
    if (rows.isEmpty())
      throw invalid("not.found", "Transfer not found in the current office scope.");
    return rows.get(0);
  }

  private TransferData.Preview prepare(TransferRequest request, boolean refresh) {
    TransferValidation.request(request, false);
    TransferData.Account source = savingsAccount(request.sourceAccountId(), refresh);
    TransferData.Account target =
        account(new AccountKey(request.targetAccountType(), request.targetAccountId()), refresh);
    TransferValidation.relationship(request, source, target);
    SavingsAccount domain = savings.assembleFrom(request.sourceAccountId(), false);
    BigDecimal fee = domain.calculateWithdrawalFee(request.amount());
    TransferValidation.funds(request.amount(), fee, domain.getWithdrawableBalance());
    if (domain.getCurrency().getInMultiplesOf() != null
        && domain.getCurrency().getInMultiplesOf() > 0) {
      BigDecimal multiple =
          BigDecimal.valueOf(domain.getCurrency().getInMultiplesOf())
              .movePointLeft(source.decimalPlaces());
      if (request.amount().remainder(multiple).signum() != 0)
        throw invalid("amount.multiple", "Amount must match the currency increment.");
    }
    Map<String, BigDecimal> flow = Map.of(source.currencyCode(), request.amount());
    return new TransferData.Preview(
        request.operationType(),
        DateUtils.getBusinessLocalDate().toString(),
        source.owner(),
        target.owner(),
        source,
        target,
        request.amount(),
        fee,
        flow,
        flow,
        true,
        fee.signum() > 0
            ? List.of("Native withdrawal fee is charged separately to the source account.")
            : List.of());
  }

  private TransferData.Account savingsAccount(Long id, boolean refresh) {
    user(false).validateHasReadPermission("savingsaccount");
    visible(new AccountKey(TransferAccountType.SAVINGS, id));
    SavingsAccount a = savings.assembleFrom(id, false);
    if (refresh) entityManager.refresh(a);
    TransferData.Client owner = client(a.getClient().getId());
    boolean eligible =
        a.isActive()
            && a.depositAccountType() == DepositAccountType.SAVINGS_DEPOSIT
            && owner.active();
    boolean debit = eligible && a.allowWithdrawal();
    boolean credit = eligible && a.allowDeposit();
    try {
      a.validateForAccountBlock();
    } catch (SavingsAccountBlockedException e) {
      debit = false;
      credit = false;
    }
    if (debit)
      try {
        a.validateForDebitBlock();
      } catch (SavingsAccountDebitsBlockedException e) {
        debit = false;
      }
    if (credit)
      try {
        a.validateForCreditBlock();
      } catch (SavingsAccountCreditsBlockedException e) {
        credit = false;
      }
    LocalDate date = DateUtils.getBusinessLocalDate();
    if (a.getLockedInUntilDate() != null && date.isBefore(a.getLockedInUntilDate())) debit = false;
    if (a.getActivationDate() == null || date.isBefore(a.getActivationDate())) {
      debit = false;
      credit = false;
    }
    BigDecimal available = a.getWithdrawableBalance();
    debit = debit && available.signum() > 0;
    return new TransferData.Account(
        id,
        a.getAccountNumber(),
        TransferAccountType.SAVINGS,
        owner,
        a.getSavingsProductId(),
        a.savingsProduct().getName(),
        a.getCurrency().getCode(),
        a.getCurrency().getDigitsAfterDecimal(),
        a.getStatus().name(),
        available,
        debit,
        credit);
  }

  private TransferData.Account loanAccount(Long id, boolean refresh) {
    user(false).validateHasReadPermission("loan");
    visible(new AccountKey(TransferAccountType.LOAN, id));
    Loan a = loans.assembleFrom(id);
    if (refresh) entityManager.refresh(a);
    TransferData.Client owner = client(a.getClientId());
    boolean eligible =
        a.getStatus().isActive()
            && !a.isChargedOff()
            && owner.active()
            && a.getDisbursementDate() != null
            && !DateUtils.getBusinessLocalDate().isBefore(a.getDisbursementDate());
    return new TransferData.Account(
        id,
        a.getAccountNumber(),
        TransferAccountType.LOAN,
        owner,
        a.productId(),
        a.loanProduct().getName(),
        a.getCurrencyCode(),
        a.getCurrency().getDigitsAfterDecimal(),
        a.getStatus().name(),
        null,
        false,
        eligible);
  }

  private TransferData.Account account(AccountKey key, boolean refresh) {
    return key.type() == TransferAccountType.SAVINGS
        ? savingsAccount(key.id(), refresh)
        : loanAccount(key.id(), refresh);
  }

  private TransferData.Client client(Long id) {
    user(false).validateHasReadPermission("CLIENT");
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_client c JOIN m_office o ON o.id=c.office_id"
                + " WHERE c.id=? AND o.hierarchy LIKE ?",
            Integer.class,
            id,
            hierarchy());
    if (count == null || count != 1)
      throw invalid("client.not.found", "Client not found in the current office scope.");
    return safeClient(clients.retrieveOne(id));
  }

  private TransferData.Client safeClient(ClientData c) {
    return new TransferData.Client(
        c.getId(),
        c.getAccountNo(),
        c.getDisplayName(),
        c.getLegalForm() == null ? null : c.getLegalForm().getValue(),
        c.getStatus().getValue(),
        c.getOfficeId(),
        c.getOfficeName(),
        c.getStatus().getId().equals(ClientStatus.ACTIVE.getValue().longValue()));
  }

  private void visible(AccountKey key) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM "
                + table(key.type())
                + " a JOIN m_client c ON c.id=a.client_id JOIN m_office o ON o.id=c.office_id"
                + " WHERE a.id=? AND o.hierarchy LIKE ?",
            Integer.class,
            key.id(),
            hierarchy());
    if (count == null || count != 1)
      throw invalid("account.not.found", "Account not found in the current office scope.");
  }

  private void lockAccount(AccountKey key) {
    visible(key);
    jdbc.queryForObject(
        "SELECT id FROM " + table(key.type()) + " WHERE id=? FOR UPDATE", Long.class, key.id());
  }

  private TransferData.Cashier cashier(AppUser actor) {
    if (actor.getStaffId() == null) return null;
    List<TransferData.Cashier> rows =
        jdbc.query(
            "SELECT t.id,c.id FROM m_cashiers c JOIN m_tellers t ON t.id=c.teller_id WHERE"
                + " c.staff_id=? AND t.office_id=? AND (c.start_date IS NULL OR c.start_date<=?)"
                + " AND (c.end_date IS NULL OR c.end_date>=?) AND t.state=? AND (t.valid_from IS"
                + " NULL OR t.valid_from<=?) AND (t.valid_to IS NULL OR t.valid_to>=?) ORDER BY"
                + " c.id",
            (rs, i) -> new TransferData.Cashier(rs.getLong(1), rs.getLong(2)),
            actor.getStaffId(),
            actor.getOffice().getId(),
            DateUtils.getBusinessLocalDate(),
            DateUtils.getBusinessLocalDate(),
            TellerStatus.ACTIVE.getValue(),
            DateUtils.getBusinessLocalDate(),
            DateUtils.getBusinessLocalDate());
    if (rows.size() > 1)
      throw invalid(
          "cashier.ambiguous",
          "Multiple active cashier allocations; resolve teller configuration first.");
    return rows.isEmpty() ? null : rows.get(0);
  }

  private AppUser user(boolean posting) {
    AppUser actor = security.authenticatedUser();
    actor.validateHasReadPermission("ACCOUNTTRANSFER");
    if (posting) actor.validateHasPermissionTo("CREATE_ACCOUNTTRANSFER");
    return actor;
  }

  private String hierarchy() {
    return security.authenticatedUser().getOffice().getHierarchy() + "%";
  }

  private static String table(TransferAccountType type) {
    return type == TransferAccountType.SAVINGS ? "m_savings_account" : "m_loan";
  }

  static JsonObject nativeRequest(TransferRequest request, TransferData.Preview preview) {
    JsonObject json = new JsonObject();
    json.addProperty("locale", "en");
    json.addProperty("dateFormat", "yyyy-MM-dd");
    json.addProperty("transferDate", preview.businessDate());
    json.addProperty("transferAmount", request.amount());
    json.addProperty("transferDescription", "WEB-1261 " + request.operationType().name());
    json.addProperty("fromAccountType", TransferAccountType.SAVINGS.nativeValue());
    json.addProperty("toAccountType", preview.destination().accountType().nativeValue());
    json.addProperty("fromAccountId", preview.source().accountId());
    json.addProperty("toAccountId", preview.destination().accountId());
    json.addProperty("fromClientId", preview.orderingClient().clientId());
    json.addProperty("toClientId", preview.beneficiaryClient().clientId());
    json.addProperty("fromOfficeId", preview.orderingClient().officeId());
    json.addProperty("toOfficeId", preview.beneficiaryClient().officeId());
    return json;
  }

  private static TransferData.Account snapshot(TransferData.Account a) {
    return new TransferData.Account(
        a.accountId(),
        a.accountNo(),
        a.accountType(),
        a.owner(),
        a.productId(),
        a.productName(),
        a.currencyCode(),
        a.decimalPlaces(),
        a.status(),
        null,
        false,
        false);
  }

  static String fingerprint(TransferRequest r) {
    String value =
        r.operationType()
            + ":"
            + r.orderingClientId()
            + ":"
            + r.sourceAccountId()
            + ":"
            + r.targetAccountType()
            + ":"
            + r.targetAccountId()
            + ":"
            + r.amount().stripTrailingZeros().toPlainString();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private record AccountKey(TransferAccountType type, Long id) {}

  private record Existing(Long transferId, String fingerprint, Long operatorId) {}
}
