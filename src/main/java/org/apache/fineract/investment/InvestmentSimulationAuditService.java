/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentProductJson.JSON;
import static org.apache.fineract.investment.InvestmentSimulationData.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Append-only evidence storage, called only after authorization by the simulation service. */
@Service
@RequiredArgsConstructor
public class InvestmentSimulationAuditService {
  private final JdbcTemplate jdbc;
  private final PlatformSecurityContext security;

  // A fresh read transaction sees the concurrent winner even when the caller's projection used
  // repeatable-read. Never catch a uniqueness error inside an aborted PostgreSQL transaction.
  @Transactional(
      transactionManager = "jdbcTransactionManager",
      propagation = Propagation.REQUIRES_NEW,
      readOnly = true)
  public Result byKey(long actor, String tenant, Request request) {
    authorize(InvestmentSimulationService.CREATE, actor, tenant);
    if (request.requestKey() == null) return null;
    var rows =
        jdbc.query(
            "SELECT request_hash,result_snapshot_json,result_hash FROM m_investment_simulation WHERE actor_id=? AND request_key=?",
            (rs, row) -> {
              if (!hash(JSON.toJson(request)).equals(rs.getString(1)))
                throw new InvestmentSimulationService.Conflict();
              return checked(rs.getString(2), rs.getString(3), actor, tenant);
            },
            actor,
            request.requestKey());
    return rows.isEmpty() ? null : rows.getFirst();
  }

  @Transactional(
      transactionManager = "jdbcTransactionManager",
      propagation = Propagation.REQUIRES_NEW)
  public Result store(Result result) {
    authorize(InvestmentSimulationService.CREATE, result.actorId(), result.tenantIdentifier());
    String request = JSON.toJson(result.request()), snapshot = JSON.toJson(result);
    Long version =
        result.cycles().isEmpty() || result.cycles().getFirst().rateSelection() == null
            ? null
            : result.cycles().getFirst().rateSelection().version();
    jdbc.update(
        "INSERT INTO m_investment_simulation(id,request_key,product_id,actor_id,created_at_utc,created_micros,business_date,status,"
            + "rate_version,policy_revision,calculation_version,request_hash,request_snapshot_json,result_hash,result_snapshot_json)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        result.simulationId(),
        result.request().requestKey(),
        result.request().productId(),
        result.actorId(),
        result.createdAtUtc(),
        InvestmentRateTime.micros(Instant.parse(result.createdAtUtc())),
        result.businessDate() == null ? null : java.sql.Date.valueOf(result.businessDate()),
        result.status().name(),
        version,
        result.policyRevision(),
        ENGINE_VERSION,
        hash(request),
        request,
        hash(snapshot),
        snapshot);
    return result;
  }

  @Transactional(
      transactionManager = "jdbcTransactionManager",
      propagation = Propagation.REQUIRES_NEW,
      readOnly = true)
  public Result get(String id, long actor, String tenant) {
    authorize(InvestmentSimulationService.READ, actor, tenant);
    var rows =
        jdbc.query(
            "SELECT result_snapshot_json,result_hash FROM m_investment_simulation WHERE id=? AND actor_id=?",
            (rs, row) -> checked(rs.getString(1), rs.getString(2), actor, tenant),
            id,
            actor);
    if (rows.isEmpty()) throw new InvestmentSimulationService.NotFound();
    return rows.getFirst();
  }

  @Transactional(
      transactionManager = "jdbcTransactionManager",
      propagation = Propagation.REQUIRES_NEW,
      readOnly = true)
  public List<HistoryEntry> history(long actor, Long product, int offset, int limit) {
    authorize(InvestmentSimulationService.AUDIT, actor, null);
    String sql =
        "SELECT id,product_id,status,created_at_utc,calculation_version FROM m_investment_simulation WHERE actor_id=?"
            + (product == null ? "" : " AND product_id=?")
            + " ORDER BY created_micros DESC,id DESC LIMIT ? OFFSET ?";
    return jdbc.query(
        sql,
        (rs, row) ->
            new HistoryEntry(
                rs.getString(1),
                rs.getLong(2),
                Status.valueOf(rs.getString(3)),
                rs.getString(4),
                rs.getString(5)),
        product == null
            ? new Object[] {actor, limit, offset}
            : new Object[] {actor, product, limit, offset});
  }

  private Result checked(String snapshot, String digest, long actor, String tenant) {
    if (!hash(snapshot).equals(digest))
      throw new IllegalStateException("Simulation snapshot integrity failure");
    Result result = JSON.fromJson(snapshot, Result.class);
    if (result.actorId() != actor || !tenant.equals(result.tenantIdentifier()))
      throw new IllegalStateException("Simulation snapshot attribution mismatch");
    return result;
  }

  private void authorize(String permission, long actor, String tenant) {
    var user = security.authenticatedUser();
    user.validateHasPermissionTo(permission);
    var context = ThreadLocalContextUtil.getTenant();
    if (user.getId() == null
        || user.getId() != actor
        || context == null
        || context.getTenantIdentifier() == null
        || context.getTenantIdentifier().isBlank()
        || (tenant != null && !tenant.equals(context.getTenantIdentifier())))
      throw new AccessDeniedException(
          "Simulation evidence requires the authenticated actor and tenant.");
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
