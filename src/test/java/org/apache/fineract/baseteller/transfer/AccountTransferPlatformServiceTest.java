package org.apache.fineract.baseteller.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountDebitsBlockedException;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountTransferPlatformServiceTest {
  private JdbcTemplate jdbc;
  private ClientReadPlatformService clients;
  private SavingsAccountAssembler assembler;
  private PortfolioCommandSourceWritePlatformService commands;
  private AppUser actor;
  private AccountTransfersReadPlatformService nativeTransfers;
  private AccountTransferPlatformService service;
  private final LocalDate date = LocalDate.of(2026, 10, 10);

  @BeforeEach
  void setup() {
    jdbc = mock(JdbcTemplate.class);
    clients = mock(ClientReadPlatformService.class);
    assembler = mock(SavingsAccountAssembler.class);
    commands = mock(PortfolioCommandSourceWritePlatformService.class);
    nativeTransfers = mock(AccountTransfersReadPlatformService.class);
    var security = mock(PlatformSecurityContext.class);
    actor = mock(AppUser.class);
    Office office = mock(Office.class);
    when(office.getHierarchy()).thenReturn(".1.");
    when(actor.getOffice()).thenReturn(office);
    when(office.getId()).thenReturn(1L);
    when(actor.getId()).thenReturn(7L);
    when(actor.getUsername()).thenReturn("unit-teller");
    when(security.authenticatedUser()).thenReturn(actor);
    service =
        new AccountTransferPlatformService(
            security,
            jdbc,
            clients,
            assembler,
            mock(LoanAssembler.class),
            mock(EntityManager.class),
            commands,
            nativeTransfers);
    ThreadLocalContextUtil.setBusinessDates(
        new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, date)));
  }

  @AfterEach
  void clearDate() {
    ThreadLocalContextUtil.reset();
  }

  private static ClientData clientData(long id) {
    ClientData c = ClientData.lookup(id, "Name", 1L, "Office");
    org.springframework.test.util.ReflectionTestUtils.setField(
        c, "status", new EnumOptionData(300L, "active", "Active"));
    org.springframework.test.util.ReflectionTestUtils.setField(c, "accountNo", "client-" + id);
    return c;
  }

  private void client(long id) {
    ClientData c = clientData(id);
    when(clients.retrieveOne(id)).thenReturn(c);
    when(jdbc.queryForObject(contains("m_client c"), eq(Integer.class), eq(id), eq(".1.%")))
        .thenReturn(1);
  }

  private SavingsAccount account(long id, long owner, String currency) {
    client(owner);
    var a = mock(SavingsAccount.class, RETURNS_DEEP_STUBS);
    when(assembler.assembleFrom(id, false)).thenReturn(a);
    when(jdbc.queryForObject(
            contains("m_savings_account a"), eq(Integer.class), eq(id), eq(".1.%")))
        .thenReturn(1);
    when(a.getClient().getId()).thenReturn(owner);
    when(a.isActive()).thenReturn(true);
    when(a.depositAccountType()).thenReturn(DepositAccountType.SAVINGS_DEPOSIT);
    when(a.allowDeposit()).thenReturn(true);
    when(a.allowWithdrawal()).thenReturn(true);
    when(a.getActivationDate()).thenReturn(date);
    when(a.getWithdrawableBalance()).thenReturn(new BigDecimal("1000"));
    when(a.getCurrency()).thenReturn(new MonetaryCurrency(currency, 2, null));
    when(a.calculateWithdrawalFee(any())).thenReturn(BigDecimal.ZERO);
    when(a.getStatus()).thenReturn(SavingsAccountStatusType.ACTIVE);
    return a;
  }

  @Test
  void usesNativePaginatedNameSearchAndProjectsSafeFields() {
    ClientData c = clientData(1);
    when(clients.retrieveAll(any())).thenReturn(new Page<>(List.of(c), 1));
    assertEquals(1, service.searchClients(null, null, "Company", 5, 10).getTotalFilteredRecords());
    var captured = ArgumentCaptor.forClass(SearchParameters.class);
    verify(clients).retrieveAll(captured.capture());
    assertEquals("Company", captured.getValue().getName());
    assertEquals(5, captured.getValue().getOffset());
    verify(actor).validateHasReadPermission("CLIENT");
  }

  @Test
  void clientNumberUsesExactScopedResolutionThenNativeReader() {
    client(2);
    when(jdbc.queryForList(contains("c.account_no=?"), eq(Long.class), eq("client-2"), eq(".1.%")))
        .thenReturn(List.of(2L));
    assertEquals(
        2L, service.searchClients(null, "client-2", null, 0, 10).getPageItems().get(0).clientId());
    verify(clients).retrieveOne(2L);
    verify(clients, never()).retrieveAll(any());
  }

  @Test
  void previewUsesNativeWithdrawableBalanceAndDoesNotPost() {
    account(10, 1, "AAA");
    account(11, 1, "AAA");
    var result =
        service.preview(
            TransferValidationTest.request(
                TransferOperation.BETWEEN_MY_ACCOUNTS, TransferAccountType.SAVINGS, 11L, "25"));
    assertEquals("2026-10-10", result.businessDate());
    assertTrue(result.balanced());
    assertEquals(result.inflowsByCurrency(), result.outflowsByCurrency());
    verifyNoInteractions(commands);
    verify(jdbc, never()).update(anyString(), any(Object[].class));
    var nativeJson =
        AccountTransferPlatformService.nativeRequest(
            TransferValidationTest.request(
                TransferOperation.BETWEEN_MY_ACCOUNTS, TransferAccountType.SAVINGS, 11L, "25"),
            result);
    assertEquals("2026-10-10", nativeJson.get("transferDate").getAsString());
    assertFalse(nativeJson.has("paymentTypeId"));
  }

  @Test
  void insufficientFundsIncludingFeesFailsBeforePosting() {
    var source = account(10, 1, "AAA");
    account(11, 1, "AAA");
    when(source.calculateWithdrawalFee(any())).thenReturn(BigDecimal.ONE);
    assertThrows(
        RuntimeException.class,
        () ->
            service.preview(
                TransferValidationTest.request(
                    TransferOperation.BETWEEN_MY_ACCOUNTS,
                    TransferAccountType.SAVINGS,
                    11L,
                    "1000")));
    verifyNoInteractions(commands);
  }

  @Test
  void rejectsInvisibleTargetBeforeNativeLoading() {
    account(10, 1, "AAA");
    assertThrows(
        RuntimeException.class,
        () ->
            service.preview(
                TransferValidationTest.request(
                    TransferOperation.BETWEEN_MY_ACCOUNTS,
                    TransferAccountType.SAVINGS,
                    11L,
                    "10")));
    verify(assembler, never()).assembleFrom(11L, false);
  }

  @Test
  void ownTargetsExcludeSourceAndIncompatibleCurrency() {
    account(10, 1, "AAA");
    account(11, 1, "AAA");
    account(12, 1, "BBB");
    when(jdbc.queryForList(contains("FROM m_savings_account WHERE"), eq(Long.class), eq(1L)))
        .thenReturn(List.of(10L, 11L, 12L));
    var result = service.destinations(1L, 10L, TransferOperation.BETWEEN_MY_ACCOUNTS, null);
    assertEquals(List.of(11L), result.stream().map(TransferData.Account::accountId).toList());
  }

  @Test
  void blockedAndClosedSavingsCannotBeSources() {
    var blocked = account(10, 1, "AAA");
    var closed = account(11, 1, "AAA");
    doThrow(new SavingsAccountDebitsBlockedException(10L)).when(blocked).validateForDebitBlock();
    when(closed.isActive()).thenReturn(false);
    when(jdbc.queryForList(contains("FROM m_savings_account WHERE"), eq(Long.class), eq(1L)))
        .thenReturn(List.of(10L, 11L));
    assertTrue(service.sources(1L).isEmpty());
  }

  @Test
  void permissionFailureStopsDiscoveryAndPosting() {
    doThrow(new SecurityException("denied"))
        .when(actor)
        .validateHasReadPermission("ACCOUNTTRANSFER");
    assertThrows(SecurityException.class, () -> service.accounts(1L));
    assertThrows(
        SecurityException.class,
        () ->
            service.create(
                TransferValidationTest.request(
                    TransferOperation.BETWEEN_MY_ACCOUNTS, TransferAccountType.SAVINGS, 11L, "1")));
    verifyNoInteractions(commands);
  }

  @Test
  void invalidPaginationDoesNotReachNativeSearch() {
    assertThrows(RuntimeException.class, () -> service.searchClients(null, null, "A", -1, 10));
    assertThrows(RuntimeException.class, () -> service.searchClients(null, null, "A", 0, 101));
    verifyNoInteractions(clients);
  }

  private TransferData.Receipt storedReceipt() {
    var source = TransferValidationTest.account(10, 1, "AAA", true, true);
    var target = TransferValidationTest.account(11, 1, "AAA", true, true);
    return new TransferData.Receipt(
        21L,
        20L,
        "21",
        date.toString(),
        "2026-10-10 12:00:00",
        "COMPLETED",
        TransferOperation.BETWEEN_MY_ACCOUNTS,
        source.owner(),
        target.owner(),
        source,
        target,
        new BigDecimal("25"),
        "AAA",
        31L,
        32L,
        7L,
        "unit-teller",
        null,
        null);
  }

  @SuppressWarnings("unchecked")
  private void persistedReceipt(boolean reversed) {
    doAnswer(
            invocation -> {
              java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
              when(rs.getString(1)).thenReturn(new com.google.gson.Gson().toJson(storedReceipt()));
              when(rs.getBoolean(2)).thenReturn(reversed);
              org.springframework.jdbc.core.RowMapper<?> mapper = invocation.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            })
        .when(jdbc)
        .query(
            contains("SELECT w.receipt_json"),
            any(org.springframework.jdbc.core.RowMapper.class),
            eq(21L),
            eq(".1.%"),
            eq(".1.%"),
            eq(".1.%"));
  }

  @Test
  void receiptPreservesHistoricalNativeLegsAndUsesNativeReversalState() {
    persistedReceipt(true);
    var receipt = service.retrieve(21L);
    assertEquals("REVERSED", receipt.status());
    assertEquals(31L, receipt.sourceTransactionId());
    assertEquals(32L, receipt.destinationTransactionId());
    assertEquals(new BigDecimal("25"), receipt.amount());
    verifyNoInteractions(assembler, commands, nativeTransfers);
  }

  @SuppressWarnings("unchecked")
  private void existingRequest(TransferRequest request, long operator) {
    doAnswer(
            invocation -> {
              java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
              when(rs.getLong(1)).thenReturn(21L);
              when(rs.getLong(3)).thenReturn(operator);
              when(rs.getString(2)).thenReturn(AccountTransferPlatformService.fingerprint(request));
              org.springframework.jdbc.core.RowMapper<?> mapper = invocation.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            })
        .when(jdbc)
        .query(
            contains("request_fingerprint,operator_id"),
            any(org.springframework.jdbc.core.RowMapper.class),
            eq("test-key"));
  }

  @Test
  void identicalRetryReturnsReceiptWithoutRecheckingChangedBalanceOrPosting() {
    var source = account(10, 1, "AAA");
    account(11, 1, "AAA");
    when(source.getWithdrawableBalance()).thenReturn(BigDecimal.ZERO);
    var request =
        TransferValidationTest.request(
            TransferOperation.BETWEEN_MY_ACCOUNTS, TransferAccountType.SAVINGS, 11L, "25");
    existingRequest(request, 7L);
    persistedReceipt(false);
    assertEquals(21L, service.create(request).transferId());
    verifyNoInteractions(commands);
    verify(assembler, never()).assembleFrom(10L, false);
  }

  @Test
  void differentPayloadOrOperatorCannotReplayIdempotencyKey() {
    account(10, 1, "AAA");
    account(11, 1, "AAA");
    var request =
        TransferValidationTest.request(
            TransferOperation.BETWEEN_MY_ACCOUNTS, TransferAccountType.SAVINGS, 11L, "25");
    existingRequest(request, 99L);
    assertThrows(RuntimeException.class, () -> service.create(request));
    existingRequest(request, 7L);
    assertThrows(
        RuntimeException.class,
        () ->
            service.create(
                TransferValidationTest.request(
                    TransferOperation.BETWEEN_MY_ACCOUNTS,
                    TransferAccountType.SAVINGS,
                    11L,
                    "26")));
    verifyNoInteractions(commands);
  }

  @SuppressWarnings("unchecked")
  @Test
  void finalCreateUsesNativeCommandAndLinkedNativeTransactionIdentity() {
    account(10, 1, "AAA");
    account(11, 1, "AAA");
    when(commands.logCommandSource(any()))
        .thenReturn(
            new org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder()
                .withEntityId(20L)
                .build());
    when(jdbc.queryForList(contains("account_transfer_details_id=?"), eq(Long.class), eq(20L)))
        .thenReturn(List.of(21L));
    when(nativeTransfers.retrieveOne(21L))
        .thenReturn(
            org.apache.fineract.portfolio.account.data.AccountTransferData.transferBasicDetails(
                21L,
                new org.apache.fineract.organisation.monetary.data.CurrencyData("AAA", 2, null),
                new BigDecimal("25"),
                date,
                "transfer",
                false));
    when(jdbc.query(
            contains("COALESCE(to_savings_transaction_id"),
            any(org.springframework.jdbc.core.RowMapper.class),
            eq(21L)))
        .thenReturn(java.util.Collections.singletonList(new Long[] {31L, 32L}));
    var receipt =
        service.create(
            TransferValidationTest.request(
                TransferOperation.BETWEEN_MY_ACCOUNTS, TransferAccountType.SAVINGS, 11L, "25"));
    assertEquals(21L, receipt.transferId());
    assertEquals(20L, receipt.transferDetailId());
    assertEquals(31L, receipt.sourceTransactionId());
    assertEquals(32L, receipt.destinationTransactionId());
    assertNull(receipt.source().withdrawableBalance());
    var command = ArgumentCaptor.forClass(org.apache.fineract.commands.domain.CommandWrapper.class);
    verify(commands).logCommandSource(command.capture());
    assertEquals("CREATE_ACCOUNTTRANSFER", command.getValue().getTaskPermissionName());
    var json =
        com.google.gson.JsonParser.parseString(command.getValue().getJson()).getAsJsonObject();
    assertEquals(10L, json.get("fromAccountId").getAsLong());
    assertEquals(11L, json.get("toAccountId").getAsLong());
    assertEquals(new BigDecimal("25"), json.get("transferAmount").getAsBigDecimal());
    assertFalse(json.has("paymentTypeId"));
  }
}
