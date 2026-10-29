/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.PlatformInternalServerException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fail-closed execution boundary. No posting delegate exists until source, vault, accounting and
 * approval policies have been approved. Recording an attempt grants no future right to post it.
 */
@Service
@RequiredArgsConstructor
public class VaultDepositExecutionService {
  // This permission only permits blocked requests; it cannot authorize future financial execution.
  public static final String PERMISSION = "REQUEST_BASE_TELLER_VAULT_DEPOSIT";
  private static final Gson GSON = new Gson();
  private final PlatformSecurityContext security;
  private final VaultReadPlatformService vaults;
  private final VaultDepositPreviewService previews;
  private final VaultDepositExecutionValidation validation;
  private final JdbcTemplate jdbc;

  public record Outcome(VaultDepositExecutionData data, boolean idempotencyConflict) {}

  @Transactional
  public Outcome execute(String json) {
    var actor = security.authenticatedUser();
    actor.validateHasPermissionTo(PERMISSION);
    if (ThreadLocalContextUtil.getTenant() == null) throw unavailable();
    var request = validation.parse(json);
    try {
      // Match the custody services' shared JDBC/JPA transaction and locking convention.
      // Establish isolation before any DB read so a waiting retry sees the committed first result.
      jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
      // Scope is checked even for replay, but an old business date/catalog is not re-executed.
      var scope = vaults.context(request.deposit().officeId().toString());
      if (!scope.vault().key().equals(request.vaultKey()))
        throw new AccessDeniedException("Vault is outside the authorized custody scope.");
      jdbc.queryForObject("SELECT id FROM m_cash_exchange_mutex WHERE id=1 FOR UPDATE", Long.class);
      String fingerprint = fingerprint(request);
      var existing =
          jdbc.query(
              "SELECT request_fingerprint,initiated_by,snapshot FROM m_vault_deposit_attempt WHERE idempotency_key=?",
              (rs, row) -> new Existing(rs.getString(1), rs.getLong(2), rs.getString(3)),
              request.idempotencyKey());
      if (!existing.isEmpty()) {
        var previous = existing.getFirst();
        if (!actor.getId().equals(previous.actor()) || !fingerprint.equals(previous.fingerprint()))
          return new Outcome(null, true);
        return new Outcome(
            GSON.fromJson(previous.snapshot(), VaultDepositExecutionData.class), false);
      }
      // Reuse Phase 2 branch/currency/catalog validation and the exact calculator at execution
      // time.
      var preview = previews.preview(GSON.toJson(request.deposit()));
      var blockers = new ArrayList<>(preview.executionReadiness().blockers());
      blockers.removeIf(b -> b.code().equals("EXECUTION_NOT_IMPLEMENTED"));
      blockers.add(
          new VaultDepositPreviewData.Blocker(
              "APPROVAL_POLICY_UNAVAILABLE",
              "Vault deposit permission and maker-checker policy are not approved."));
      blockers.add(
          new VaultDepositPreviewData.Blocker(
              "POSTING_MODEL_UNAVAILABLE",
              "No approved atomic source, denomination inventory and accounting operation is configured."));
      // Unconditional block: a valid preview, submitted source or role cannot enable this boundary.
      var result =
          new VaultDepositExecutionData(
              "VDA-" + UUID.randomUUID(),
              VaultDepositExecutionData.Status.BLOCKED,
              null,
              preview.operationType(),
              preview.branch(),
              preview.vault(),
              preview.businessDate().toString(),
              request.source(),
              actor.getId(),
              OffsetDateTime.now(ZoneOffset.UTC).toString(),
              preview.correlationReference(),
              preview.currencies(),
              VaultDepositExecutionData.AccountingStatus.NOT_POSTED,
              false,
              List.copyOf(blockers));
      jdbc.update(
          "INSERT INTO m_vault_deposit_attempt"
              + " (idempotency_key,request_fingerprint,attempt_reference,status,office_id,"
              + "initiated_by,business_date,created_at,snapshot) VALUES (?,?,?,?,?,?,?,?,?)",
          request.idempotencyKey(),
          fingerprint,
          result.attemptReference(),
          result.status().name(),
          preview.branch().id(),
          actor.getId(),
          preview.businessDate(),
          OffsetDateTime.parse(result.createdAt()),
          GSON.toJson(result));
      return new Outcome(result, false);
    } catch (DataAccessException e) {
      // Never expose SQL or return an outcome whose mandatory evidence did not persist.
      throw unavailable();
    }
  }

  static String fingerprint(VaultDepositExecutionRequest request) {
    var currencies =
        request.deposit().currencies().stream()
            .map(
                c ->
                    new VaultDepositPreviewRequest.Currency(
                        c.currencyCode().toUpperCase(Locale.ROOT),
                        c.denominations().stream()
                            .map(
                                d ->
                                    new VaultDepositPreviewRequest.Quantity(
                                        d.denominationId().trim().toLowerCase(Locale.ROOT),
                                        d.quantity()))
                            .sorted(
                                Comparator.comparing(
                                    VaultDepositPreviewRequest.Quantity::denominationId))
                            .toList()))
            .sorted(Comparator.comparing(VaultDepositPreviewRequest.Currency::currencyCode))
            .toList();
    var canonical =
        new VaultDepositExecutionRequest(
            new VaultDepositPreviewRequest(
                request.deposit().officeId(),
                request.deposit().businessDate(),
                currencies,
                request.deposit().correlationReference()),
            request.vaultKey(),
            null,
            request.source());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(GSON.toJson(canonical).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  static PlatformInternalServerException unavailable() {
    return new PlatformInternalServerException(
        "error.msg.base.teller.vault.deposit.infrastructure.unavailable",
        "Vault deposit execution or mandatory audit persistence is unavailable; no cash was posted.");
  }

  private record Existing(String fingerprint, Long actor, String snapshot) {}
}
