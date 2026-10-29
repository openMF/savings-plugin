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
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformInternalServerException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionSystemException;

class VaultDepositExecutionServiceTest {
  private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
  private static final Gson GSON = new Gson();
  private static final String REQUEST =
      """
      {"vaultKey":"VAULT:8","idempotencyKey":"execution-test",
       "deposit":{"officeId":8,"businessDate":"2026-10-29","currencies":[
        {"currencyCode":"USD","denominations":[{"denominationId":"note","quantity":2},
         {"denominationId":"coin","quantity":3}]}]}}
      """;
  private final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
  private final AppUser actor = mock(AppUser.class);
  private final VaultReadPlatformService vaults = mock(VaultReadPlatformService.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final VaultDepositPreviewValidation quantities =
      new VaultDepositPreviewValidation(VALIDATORS.getValidator());
  private final VaultDepositExecutionValidation validation =
      new VaultDepositExecutionValidation(quantities);
  private VaultDepositExecutionService service;
  private String snapshot, fingerprint;
  private Long storedActor;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() throws Exception {
    ThreadLocalContextUtil.setTenant(mock(FineractPlatformTenant.class));
    when(security.authenticatedUser()).thenReturn(actor);
    when(actor.getId()).thenReturn(7L);
    when(vaults.context("8"))
        .thenReturn(
            new VaultData.Context(
                new VaultData.Branch(8L, "Branch"),
                new VaultData.Vault("VAULT:8", 8L, "Vault", VaultData.Status.UNKNOWN),
                LocalDate.of(2026, 10, 29),
                List.of(new VaultData.Currency("USD", "Dollar", 2)),
                new VaultData.Capabilities(true, true, true, true, true, false),
                new VaultData.Readiness(false, List.of())));
    when(vaults.denominations("8", "USD"))
        .thenReturn(
            new VaultData.Denominations(
                "VAULT:8",
                "USD",
                List.of(
                    new VaultData.Denomination(
                        1L,
                        "note",
                        "USD",
                        new BigDecimal("100"),
                        VaultData.DenominationType.BANKNOTE,
                        true),
                    new VaultData.Denomination(
                        2L,
                        "coin",
                        "USD",
                        new BigDecimal("0.01"),
                        VaultData.DenominationType.COIN,
                        true)),
                VaultData.Status.AVAILABLE));
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            i -> {
              if (snapshot == null) return List.of();
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString(1)).thenReturn(fingerprint);
              when(rs.getLong(2)).thenReturn(storedActor);
              when(rs.getString(3)).thenReturn(snapshot);
              return List.of(((RowMapper<?>) i.getArgument(1)).mapRow(rs, 0));
            });
    when(jdbc.update(anyString(), any(Object[].class)))
        .thenAnswer(
            i -> {
              fingerprint = i.getArgument(2);
              storedActor = i.getArgument(6);
              snapshot = i.getArgument(9);
              return 1;
            });
    service =
        new VaultDepositExecutionService(
            security,
            vaults,
            new VaultDepositPreviewService(security, vaults, quantities),
            validation,
            jdbc);
  }

  @AfterEach
  void clear() {
    ThreadLocalContextUtil.reset();
  }

  @AfterAll
  static void close() {
    VALIDATORS.close();
  }

  @Test
  void validMathIsBlockedWithExactProposedAmountsAndPersistedEvidence() {
    var result = service.execute(REQUEST).data();
    assertEquals(VaultDepositExecutionData.Status.BLOCKED, result.status());
    assertEquals(new BigDecimal("200.03"), result.currencies().getFirst().totalCash());
    assertFalse(result.executionAllowed());
    assertNull(result.transactionId());
    assertNull(result.proposedSource());
    assertEquals(VaultDepositExecutionData.AccountingStatus.NOT_POSTED, result.accountingStatus());
    assertEquals(5, result.blockers().size());
    assertTrue(result.attemptReference().startsWith("VDA-"));
    assertNotNull(snapshot);
    // The only permitted write is append-only attempt evidence.
    verify(jdbc).update(startsWith("INSERT INTO m_vault_deposit_attempt"), any(Object[].class));
  }

  @Test
  void identicalAndCanonicalReplaysReturnSamePersistedOutcome() {
    var first = service.execute(REQUEST).data();
    assertEquals(first, service.execute(REQUEST).data());
    assertEquals(
        first, service.execute(REQUEST.replace("USD", "usd").replace("note", " NOTE ")).data());
    verify(jdbc, times(1)).update(anyString(), any(Object[].class));
  }

  @Test
  void conflictingPayloadAndDifferentActorCannotReplayAnotherAttempt() {
    service.execute(REQUEST);
    assertTrue(
        service.execute(REQUEST.replace("\"quantity\":2", "\"quantity\":3")).idempotencyConflict());
    when(actor.getId()).thenReturn(9L);
    var conflict = service.execute(REQUEST);
    assertTrue(conflict.idempotencyConflict());
    assertNull(conflict.data());
    verify(jdbc, times(1)).update(anyString(), any(Object[].class));
  }

  @Test
  void replayIsAuthorizedAgainAndDoesNotRecalculateHistoricalEvidence() {
    var first = service.execute(REQUEST).data();
    when(vaults.denominations(any(), any()))
        .thenThrow(new AssertionError("Replay must not recalculate"));
    assertEquals(first, service.execute(REQUEST).data());
    when(vaults.context(any())).thenThrow(new AccessDeniedException("Access revoked"));
    assertThrows(AccessDeniedException.class, () -> service.execute(REQUEST));
    verify(jdbc, times(1)).update(anyString(), any(Object[].class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"INTERNAL_CUSTODY_TRANSFER", "EXTERNAL_CASH_RECEIPT"})
  void neitherSubmittedSourceTypeAuthorizesExecution(String type) {
    var request = JsonParser.parseString(REQUEST).getAsJsonObject();
    request.add(
        "source",
        JsonParser.parseString(
            "{\"type\":\"" + type + "\",\"reference\":\"unverified-proposal\"}"));
    assertFalse(service.execute(GSON.toJson(request)).data().executionAllowed());
  }

  @Test
  void permissionAndTenantChecksPrecedeDatabaseAccess() {
    doThrow(new AccessDeniedException("Forbidden"))
        .when(actor)
        .validateHasPermissionTo(VaultDepositExecutionService.PERMISSION);
    assertThrows(AccessDeniedException.class, () -> service.execute("invalid"));
    verifyNoInteractions(jdbc, vaults);
    doNothing().when(actor).validateHasPermissionTo(VaultDepositExecutionService.PERMISSION);
    ThreadLocalContextUtil.clearTenant();
    assertThrows(PlatformInternalServerException.class, () -> service.execute(REQUEST));
    verifyNoInteractions(jdbc, vaults);
  }

  @Test
  void auditFailureCannotReturnAnAuthoritativeOutcomeOrLeakSql() {
    when(jdbc.update(anyString(), any(Object[].class)))
        .thenThrow(new DataAccessResourceFailureException("SQL secret"));
    var error = assertThrows(PlatformInternalServerException.class, () -> service.execute(REQUEST));
    assertFalse(error.getMessage().contains("secret"));
    assertNull(snapshot);
  }

  @Test
  void commitFailureOutsideServiceMethodIsMaskedAtApiBoundary() {
    var boundary = mock(VaultDepositExecutionService.class);
    when(boundary.execute(anyString())).thenThrow(new TransactionSystemException("SQL secret"));
    var api = new VaultDepositsApiResource(boundary);
    var error = assertThrows(PlatformInternalServerException.class, () -> api.execute(REQUEST));
    assertFalse(error.getMessage().contains("secret"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "0.5", "1e2", "1000000001", "9223372036854775808", "0"})
  void invalidQuantitiesCannotCreateEvidenceOrFinancialWrites(String quantity) {
    String request =
        REQUEST
            .replace("\"quantity\":2", "\"quantity\":" + quantity)
            .replace("\"quantity\":3", "\"quantity\":0");
    assertThrows(PlatformApiDataValidationException.class, () -> service.execute(request));
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"bad-date", "2026-10-28"})
  void invalidOrStaleBusinessDateCannotCreateEvidence(String date) {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.execute(REQUEST.replace("2026-10-29", date)));
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"status\":\"POSTED\"}",
        "{\"approved\":true}",
        "{\"glAccountId\":1}",
        "{\"transactionId\":\"fake\"}",
        "{\"amount\":100}",
        "{\"permissions\":[\"ALL_FUNCTIONS\"]}"
      })
  void serverOwnedFieldsCannotBeInjected(String injected) {
    var body = JsonParser.parseString(REQUEST).getAsJsonObject();
    JsonParser.parseString(injected).getAsJsonObject().asMap().forEach(body::add);
    assertThrows(
        PlatformApiDataValidationException.class, () -> service.execute(GSON.toJson(body)));
    verifyNoInteractions(jdbc);
  }

  @Test
  void missingKeyMismatchedVaultInvalidSourceAndDuplicateDenominationsAreRejected() {
    for (String request :
        List.of(
            REQUEST.replace("execution-test", ""),
            REQUEST.replace("VAULT:8", "VAULT:9"),
            REQUEST.replace("\"officeId\":8,", ""),
            REQUEST.replace("\"coin\"", "\"note\""),
            REQUEST.replace("\"coin\"", "\"unknown\""),
            REQUEST.replace("\"USD\"", "\"EUR\""))) {
      assertThrows(PlatformApiDataValidationException.class, () -> service.execute(request));
    }
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }
}
