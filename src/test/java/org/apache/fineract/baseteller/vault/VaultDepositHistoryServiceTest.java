/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.*;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.fineract.baseteller.data.*;
import org.apache.fineract.baseteller.service.CashInventoryReadPlatformService;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformInternalServerException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionSystemException;

class VaultDepositHistoryServiceTest {
  private static final String REF = "VDA-12345678-1234-1234-1234-123456789abc";
  // Synthetic BLOCKED evidence for mapping tests, never a claimed financial posting.
  private static final String SNAPSHOT =
      """
      {"attemptReference":"VDA-12345678-1234-1234-1234-123456789abc","status":"BLOCKED",
       "operationType":"VAULT_CASH_DEPOSIT","branch":{"id":8,"name":"Original branch"},
       "vault":{"key":"VAULT:8","officeId":8,"name":"Original vault","status":"UNKNOWN"},
       "businessDate":"2026-10-29","initiatedBy":7,"createdAt":"2026-10-29T10:00:00.123456789Z",
       "currencies":[{"currencyCode":"USD","decimalPlaces":2,
         "banknotes":[{"denominationId":"note","value":100.00,"quantity":2,"subtotal":200.00}],
         "coins":[{"denominationId":"coin","value":0.01,"quantity":3,"subtotal":0.03}],
         "banknotesTotal":200.00,"coinsTotal":0.03,"totalCash":200.03}],
       "accountingStatus":"NOT_POSTED","executionAllowed":false,"blockers":[]}
      """;
  private final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
  private final AppUser actor = mock(AppUser.class);
  private final Office office = mock(Office.class);
  private final CashInventoryReadPlatformService inventory =
      mock(CashInventoryReadPlatformService.class);
  private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
  private final VaultDepositHistoryService service =
      new VaultDepositHistoryService(security, inventory, jdbc);
  private JsonObject snapshot;
  private boolean exists;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() throws Exception {
    snapshot = JsonParser.parseString(SNAPSHOT).getAsJsonObject();
    exists = true;
    ThreadLocalContextUtil.setTenant(mock(FineractPlatformTenant.class));
    when(security.authenticatedUser()).thenReturn(actor);
    when(actor.getOffice()).thenReturn(office);
    when(office.getHierarchy()).thenReturn(".1.8.");
    when(inventory.context())
        .thenReturn(
            new CashInventoryContextData(
                List.of(
                    new CashInventoryCustodianData(
                        "VAULT:8", CashInventoryCustodianType.VAULT, 8L, "8", "Current name")),
                List.of(),
                List.of()));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(1L);
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenAnswer(
            i -> {
              if (!exists) return List.of();
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("snapshot")).thenReturn(snapshot.toString());
              when(rs.getString("status")).thenReturn("BLOCKED");
              when(rs.getString("attempt_reference")).thenReturn(REF);
              when(rs.getLong("office_id")).thenReturn(8L);
              when(rs.getLong("initiated_by")).thenReturn(7L);
              when(rs.getDate("business_date")).thenReturn(Date.valueOf("2026-10-29"));
              // PostgreSQL rounds timestamps to microseconds; the immutable JSON retains
              // nanoseconds.
              when(rs.getTimestamp("created_at"))
                  .thenReturn(Timestamp.from(Instant.parse("2026-10-29T10:00:00.123457Z")));
              return List.of(((RowMapper<?>) i.getArgument(2)).mapRow(rs, 0));
            });
  }

  @AfterEach
  void clear() {
    ThreadLocalContextUtil.reset();
  }

  @Test
  void historicalAmountsNamesAndPrecisionComeFromSnapshotWithoutCurrentCatalog() {
    var detail = service.detail(REF);
    var cash = detail.attempt().currencies().getFirst();
    assertEquals(new BigDecimal("200.03"), cash.totalCash());
    assertEquals(new BigDecimal("100.00"), cash.banknotes().getFirst().value());
    assertEquals(3, cash.coins().getFirst().quantity());
    assertEquals("Original branch", detail.attempt().branch().name());
    assertEquals("Original vault", detail.attempt().vault().name());
    assertEquals(detail, service.detail(REF));
    assertFalse(detail.receiptEligibility().eligible());
    assertNull(detail.attempt().transactionId());
    assertEquals(List.of("VIEW_DETAILS", "VIEW_AUDIT"), detail.permittedActions());
    verify(jdbc, never()).update(anyString(), anyMap());
  }

  @Test
  void auditProjectsOnlyPersistedAttemptWithoutApprovalOrPostingEvents() {
    var audit = service.audit(REF);
    assertEquals(1, audit.events().size());
    var event = audit.events().getFirst();
    assertEquals(REF, event.eventId());
    assertEquals("BLOCKED_ATTEMPT_RECORDED", event.eventType());
    assertEquals(7L, event.actorId());
    assertEquals(VaultDepositExecutionData.Status.BLOCKED, event.newState());
    assertNull(event.previousState());
    assertEquals(audit, service.audit(REF));
    verify(jdbc, never()).update(anyString(), anyMap());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {VaultDepositHistoryService.PERMISSION, VaultReadPlatformService.PERMISSION})
  void bothReadPermissionsAreRequiredBeforeSql(String permission) {
    doThrow(new AccessDeniedException("Denied")).when(actor).validateHasPermissionTo(permission);
    assertThrows(AccessDeniedException.class, () -> service.detail(REF));
    assertThrows(AccessDeniedException.class, () -> service.audit(REF));
    assertThrows(AccessDeniedException.class, () -> service.history(query()));
    verifyNoInteractions(jdbc, inventory);
  }

  @Test
  void absentTenantCannotFallBackToDefaultDatasource() {
    ThreadLocalContextUtil.clearTenant();
    assertThrows(PlatformInternalServerException.class, () -> service.detail(REF));
    verifyNoInteractions(jdbc, inventory);
  }

  @Test
  void assignedCashierWithoutVaultAndMissingOfficeAreDenied() {
    when(inventory.context())
        .thenReturn(new CashInventoryContextData(List.of(), List.of(), List.of()));
    assertThrows(AccessDeniedException.class, () -> service.detail(REF));
    when(actor.getOffice()).thenReturn(null);
    assertThrows(AccessDeniedException.class, () -> service.history(query()));
    verifyNoInteractions(jdbc);
  }

  @Test
  void unauthorizedVaultFilterCannotWidenScope() {
    assertThrows(
        AccessDeniedException.class,
        () ->
            service.history(
                new VaultDepositHistoryQuery("9", null, null, null, null, null, null, null, null)));
    verifyNoInteractions(jdbc);
  }

  @Test
  void unknownWrongTypeAndUnauthorizedRecordsShareNotFoundBehavior() {
    exists = false;
    assertThrows(
        VaultDepositHistoryService.AttemptNotFoundException.class, () -> service.detail(REF));
    assertThrows(
        VaultDepositHistoryService.AttemptNotFoundException.class, () -> service.audit(REF));
    assertThrows(
        VaultDepositHistoryService.AttemptNotFoundException.class,
        () -> service.detail("SAVINGS_DEPOSIT:1"));
    var page = service.history(query());
    assertTrue(page.items().isEmpty());
  }

  @Test
  void historyReturnsBlockedProposalsWithStableDatabasePagination() {
    var page = service.history(query());
    assertEquals(25, page.limit());
    assertEquals(0, page.offset());
    assertEquals(1, page.totalFilteredRecords());
    assertEquals("DEPOSIT_ATTEMPT", page.items().getFirst().recordKind());
    assertEquals(
        new BigDecimal("200.03"), page.items().getFirst().currencies().getFirst().proposedCash());
    verify(jdbc)
        .query(
            contains("ORDER BY a.created_at DESC,a.id DESC LIMIT :limit OFFSET :offset"),
            anyMap(),
            any(RowMapper.class));
    verify(jdbc)
        .queryForObject(
            contains("o.hierarchy LIKE :hierarchy AND a.office_id IN (:offices)"),
            anyMap(),
            eq(Long.class));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"POSTED", "PENDING_APPROVAL", "REJECTED", "FAILED", "REVERSED", "UNKNOWN"})
  void unsupportedStatesNeverBecomePostedReceipts(String status) {
    snapshot.addProperty("status", status);
    assertThrows(PlatformInternalServerException.class, () -> service.detail(REF));
    assertThrows(
        PlatformInternalServerException.class,
        () -> new VaultDepositHistoryApiResource(service).receipt(REF));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "amount",
        "subtotal",
        "quantity",
        "office",
        "actor",
        "reference",
        "date",
        "timestamp",
        "currency",
        "accounting",
        "transaction",
        "execution",
        "duplicate"
      })
  void inconsistentEvidenceFailsWithoutRepair(String defect) {
    var currency = snapshot.getAsJsonArray("currencies").get(0).getAsJsonObject();
    switch (defect) {
      case "amount" -> currency.addProperty("totalCash", 999);
      case "subtotal" ->
          currency.getAsJsonArray("coins").get(0).getAsJsonObject().addProperty("subtotal", 5);
      case "quantity" ->
          currency.getAsJsonArray("coins").get(0).getAsJsonObject().addProperty("quantity", -1);
      case "office" -> snapshot.getAsJsonObject("branch").addProperty("id", 9);
      case "actor" -> snapshot.addProperty("initiatedBy", 99);
      case "reference" -> snapshot.addProperty("attemptReference", "wrong");
      case "date" -> snapshot.addProperty("businessDate", "2026-10-28");
      case "timestamp" -> snapshot.addProperty("createdAt", "2026-10-28T10:00:00Z");
      case "currency" -> currency.addProperty("currencyCode", "invalid");
      case "accounting" -> snapshot.addProperty("accountingStatus", "POSTED");
      case "transaction" -> snapshot.addProperty("transactionId", "fabricated");
      case "execution" -> snapshot.addProperty("executionAllowed", true);
      case "duplicate" -> snapshot.getAsJsonArray("currencies").add(currency.deepCopy());
    }
    assertThrows(PlatformInternalServerException.class, () -> service.detail(REF));
    verify(jdbc, never()).update(anyString(), anyMap());
  }

  @Test
  void receiptDenialIsStructuredAndSqlFailuresDoNotLeak() {
    var response = new VaultDepositHistoryApiResource(service).receipt(REF);
    assertEquals(409, response.getStatus());
    assertTrue(new Gson().toJson(response.getEntity()).contains("receipt.not_posted"));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class)))
        .thenThrow(new DataAccessResourceFailureException("SELECT secret FROM private"));
    var error = assertThrows(PlatformInternalServerException.class, () -> service.history(query()));
    assertFalse(error.getMessage().contains("secret"));
  }

  @Test
  void interceptorFailuresAreSanitizedForAllReadRoutes() {
    var proxy = mock(VaultDepositHistoryService.class);
    when(proxy.history(any())).thenThrow(new TransactionSystemException("private SQL"));
    when(proxy.detail(any())).thenThrow(new TransactionSystemException("private SQL"));
    when(proxy.audit(any())).thenThrow(new TransactionSystemException("private SQL"));
    var api = new VaultDepositHistoryApiResource(proxy);
    for (Runnable request :
        List.<Runnable>of(
            () -> api.history(null, null, null, null, null, null, null, null, null),
            () -> api.detail(REF),
            () -> api.audit(REF),
            () -> api.receipt(REF))) {
      var error = assertThrows(PlatformInternalServerException.class, request::run);
      assertFalse(error.getMessage().contains("SQL"));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "offset=-1",
        "offset=2147483648",
        "offset=1e2",
        "limit=0",
        "limit=101",
        "limit=abc",
        "currencyCode=US",
        "currencyCode=USD'",
        "status=POSTED",
        "fromDate=2026-02-30",
        "toDate=",
        "officeId=0",
        "vaultKey=VAULT:0",
        "reference=%"
      })
  void invalidQueriesAreStructured(String parameter) {
    var parts = parameter.split("=", -1);
    var values = Map.of(parts[0], parts[1]);
    var query =
        new VaultDepositHistoryQuery(
            values.get("officeId"),
            values.get("vaultKey"),
            values.get("currencyCode"),
            values.get("status"),
            values.get("fromDate"),
            values.get("toDate"),
            values.get("reference"),
            values.get("offset"),
            values.get("limit"));
    assertThrows(PlatformApiDataValidationException.class, () -> service.history(query));
    verifyNoInteractions(jdbc);
  }

  @Test
  void validHistoricCurrencyDoesNotNeedCurrentEnabledCurrencyAndInvalidRangeIsRejected() {
    var resolved =
        new VaultDepositHistoryQuery(
                "8", "VAULT:8", "jpy", "BLOCKED", "2026-10-01", "2026-10-29", REF, "0", "100")
            .resolve();
    assertEquals("JPY", resolved.currencyCode());
    assertEquals(100, resolved.limit());
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            new VaultDepositHistoryQuery("8", "VAULT:9", null, null, null, null, null, null, null)
                .resolve());
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            new VaultDepositHistoryQuery(
                    null, null, null, null, "2026-10-29", "2026-10-01", null, null, null)
                .resolve());
  }

  private static VaultDepositHistoryQuery query() {
    return new VaultDepositHistoryQuery(null, null, null, null, null, null, null, null, null);
  }
}
