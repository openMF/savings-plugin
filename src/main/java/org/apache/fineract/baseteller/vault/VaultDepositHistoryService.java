/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.vault;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.CashInventoryCustodianType;
import org.apache.fineract.baseteller.service.CashInventoryReadPlatformService;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;
import org.apache.fineract.infrastructure.core.exception.PlatformInternalServerException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VaultDepositHistoryService {
  public static final String PERMISSION = "READ_BASE_TELLER_TRANSACTION_HISTORY";
  private static final Gson GSON = new Gson();
  private static final List<String> ACTIONS = List.of("VIEW_DETAILS", "VIEW_AUDIT");
  private static final VaultDepositHistoryData.ReceiptEligibility INELIGIBLE =
      new VaultDepositHistoryData.ReceiptEligibility(false, "NOT_POSTED");
  private static final String FROM =
      " FROM m_vault_deposit_attempt a JOIN m_office o ON o.id=a.office_id"
          + " WHERE o.hierarchy LIKE :hierarchy AND a.office_id IN (:offices)";

  private final PlatformSecurityContext security;
  private final CashInventoryReadPlatformService inventory;
  private final NamedParameterJdbcTemplate jdbc;

  public VaultDepositHistoryData.Page history(VaultDepositHistoryQuery query) {
    return configured(
        () -> {
          var params = scope();
          var filter = query.resolve();
          String where = FROM;
          if (filter.officeId() != null) {
            if (!((Set<?>) params.get("offices")).contains(filter.officeId()))
              throw new AccessDeniedException("Vault is outside the authorized custody scope.");
            where += " AND a.office_id=:office";
            params.put("office", filter.officeId());
          }
          if (filter.currencyCode() != null) {
            // Phase 3 writes canonical compact Gson JSON with uppercase currencyCode values.
            // Match the exact property, never current configuration or a substring of a code.
            where += " AND a.snapshot LIKE :currency";
            params.put("currency", "%\"currencyCode\":\"" + filter.currencyCode() + "\"%");
          }
          if (filter.status() != null) {
            where += " AND a.status=:status";
            params.put("status", filter.status());
          }
          if (filter.fromDate() != null) {
            where += " AND a.business_date>=:fromDate";
            params.put("fromDate", filter.fromDate());
          }
          if (filter.toDate() != null) {
            where += " AND a.business_date<=:toDate";
            params.put("toDate", filter.toDate());
          }
          if (filter.reference() != null) {
            where += " AND a.attempt_reference=:reference";
            params.put("reference", filter.reference());
          }
          long count = jdbc.queryForObject("SELECT COUNT(*)" + where, params, Long.class);
          params.put("limit", filter.limit());
          params.put("offset", filter.offset());
          var items =
              jdbc.query(
                  "SELECT a.*"
                      + where
                      + " ORDER BY a.created_at DESC,a.id DESC LIMIT :limit OFFSET :offset",
                  params,
                  (rs, row) -> item(snapshot(rs)));
          return new VaultDepositHistoryData.Page(items, count, filter.offset(), filter.limit());
        });
  }

  public VaultDepositHistoryData.Detail detail(String reference) {
    return configured(
        () ->
            new VaultDepositHistoryData.Detail(
                "DEPOSIT_ATTEMPT", read(reference), INELIGIBLE, ACTIONS));
  }

  public VaultDepositHistoryData.Audit audit(String reference) {
    return configured(
        () -> {
          var attempt = read(reference);
          // This is a projection of the immutable attempt row, not a reconstructed lifecycle.
          var event =
              new VaultDepositHistoryData.Event(
                  attempt.attemptReference(),
                  "BLOCKED_ATTEMPT_RECORDED",
                  attempt.initiatedBy(),
                  attempt.createdAt(),
                  null,
                  attempt.status());
          return new VaultDepositHistoryData.Audit(attempt.attemptReference(), List.of(event));
        });
  }

  private VaultDepositExecutionData read(String reference) {
    var params = scope();
    VaultDepositHistoryQuery.validateReference(reference);
    params.put("reference", reference);
    var rows =
        jdbc.query(
            "SELECT a.*" + FROM + " AND a.attempt_reference=:reference",
            params,
            (rs, row) -> snapshot(rs));
    if (rows.isEmpty()) throw new AttemptNotFoundException();
    return rows.getFirst();
  }

  private Map<String, Object> scope() {
    var user = security.authenticatedUser();
    user.validateHasPermissionTo(PERMISSION);
    user.validateHasPermissionTo(VaultReadPlatformService.PERMISSION);
    if (ThreadLocalContextUtil.getTenant() == null) throw unavailable();
    if (user.getOffice() == null || user.getOffice().getHierarchy() == null)
      throw new AccessDeniedException("An authenticated office is required.");
    // Same custody rules as Phase 1, including assigned-cashier and global settlement restrictions.
    var offices = new HashSet<Long>();
    for (var custodian : inventory.context().custodians())
      if (custodian.type() == CashInventoryCustodianType.VAULT
          && custodian.key().equals("VAULT:" + custodian.resourceId()))
        offices.add(custodian.resourceId());
    if (offices.isEmpty()) throw new AccessDeniedException("No authorized vault custody scope.");
    var params = new HashMap<String, Object>();
    params.put("offices", offices);
    params.put("hierarchy", user.getOffice().getHierarchy() + "%");
    return params;
  }

  private static VaultDepositHistoryData.Item item(VaultDepositExecutionData attempt) {
    return new VaultDepositHistoryData.Item(
        attempt.attemptReference(),
        "DEPOSIT_ATTEMPT",
        attempt.status(),
        attempt.operationType(),
        attempt.branch(),
        attempt.vault(),
        attempt.businessDate(),
        attempt.createdAt(),
        attempt.currencies().stream()
            .map(
                c ->
                    new VaultDepositHistoryData.CurrencyAmount(
                        c.currencyCode(), c.decimalPlaces(), c.totalCash()))
            .toList(),
        INELIGIBLE,
        ACTIONS);
  }

  private static VaultDepositExecutionData snapshot(ResultSet rs) throws SQLException {
    try {
      var data = GSON.fromJson(rs.getString("snapshot"), VaultDepositExecutionData.class);
      if (data.status() != VaultDepositExecutionData.Status.BLOCKED
          || !"BLOCKED".equals(rs.getString("status"))
          || data.transactionId() != null
          || data.executionAllowed()
          || data.accountingStatus() != VaultDepositExecutionData.AccountingStatus.NOT_POSTED
          || data.operationType() != VaultDepositPreviewData.OperationType.VAULT_CASH_DEPOSIT
          || !Objects.equals(data.attemptReference(), rs.getString("attempt_reference"))
          || !Objects.equals(data.branch().id(), rs.getLong("office_id"))
          || !Objects.equals(data.vault().officeId(), data.branch().id())
          || !("VAULT:" + data.branch().id()).equals(data.vault().key())
          || !Objects.equals(data.initiatedBy(), rs.getLong("initiated_by"))
          || !LocalDate.parse(data.businessDate()).equals(rs.getDate("business_date").toLocalDate())
          || java.time.Duration.between(
                      OffsetDateTime.parse(data.createdAt()).toInstant(),
                      rs.getTimestamp("created_at").toInstant())
                  .abs()
                  .compareTo(java.time.Duration.ofNanos(1000))
              > 0
          || data.currencies() == null
          || data.currencies().isEmpty()) throw new IllegalArgumentException();
      var codes = new HashSet<String>();
      for (var currency : data.currencies()) {
        if (!currency.currencyCode().matches("[A-Z]{3}")
            || !codes.add(currency.currencyCode())
            || currency.decimalPlaces() < 0
            || currency.decimalPlaces() > 18
            || currency.totalCash().signum() <= 0
            || currency.totalCash().compareTo(currency.banknotesTotal().add(currency.coinsTotal()))
                != 0) throw new IllegalArgumentException();
        currency.totalCash().setScale(currency.decimalPlaces(), RoundingMode.UNNECESSARY);
        var denominations = new HashSet<String>();
        if (sum(currency.banknotes(), denominations).compareTo(currency.banknotesTotal()) != 0
            || sum(currency.coins(), denominations).compareTo(currency.coinsTotal()) != 0)
          throw new IllegalArgumentException();
      }
      return data;
    } catch (RuntimeException e) {
      throw new PlatformInternalServerException(
          "error.msg.base.teller.vault.history.record.inconsistent",
          "Persisted vault deposit evidence is incomplete or inconsistent; no financial data was"
              + " changed.");
    }
  }

  private static BigDecimal sum(List<VaultDepositPreviewData.Line> lines, Set<String> identifiers) {
    BigDecimal total = BigDecimal.ZERO;
    for (var line : lines) {
      if (line.denominationId() == null
          || !identifiers.add(line.denominationId())
          || line.quantity() < 0
          || line.value().signum() <= 0
          || line.value().multiply(BigDecimal.valueOf(line.quantity())).compareTo(line.subtotal())
              != 0) throw new IllegalArgumentException();
      total = total.add(line.subtotal());
    }
    return total;
  }

  private static <T> T configured(Supplier<T> read) {
    try {
      return read.get();
    } catch (DataAccessException e) {
      throw unavailable();
    }
  }

  static PlatformInternalServerException unavailable() {
    return new PlatformInternalServerException(
        "error.msg.base.teller.vault.history.unavailable",
        "Authenticated vault deposit history is unavailable.");
  }

  public static class AttemptNotFoundException extends AbstractPlatformResourceNotFoundException {
    public AttemptNotFoundException() {
      super(
          "error.msg.base.teller.vault.deposit.attempt.not.found",
          "Vault deposit attempt was not found.");
    }
  }
}
