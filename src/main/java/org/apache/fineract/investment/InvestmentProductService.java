/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentProductData.*;
import static org.apache.fineract.investment.InvestmentProductJson.JSON;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versioned draft configuration. Policy and append-only audit writes share one tenant transaction.
 */
@Service
@RequiredArgsConstructor
public class InvestmentProductService {
  public static final String READ = "READ_INVESTMENT_PRODUCT_POLICY";
  public static final String MANAGE = "UPDATE_INVESTMENT_PRODUCT_POLICY";
  private final PlatformSecurityContext security;
  private final JdbcTemplate jdbc;
  private final InvestmentProductAccess access;
  private final InvestmentProductValidation validation;

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_INVESTMENT_PRODUCT_POLICY')")
  @Transactional(readOnly = true)
  public Template template() {
    authorize(READ);
    return new Template(
        capabilities(),
        List.of(InvestmentType.values()),
        List.of(InterestPayoutMode.values()),
        List.of(PrincipalSettlementMode.values()),
        List.of(PenaltyMode.values()),
        List.of(RenewalRatePolicy.values()),
        1,
        1825,
        60,
        30,
        READ,
        MANAGE,
        List.of(
            "DRAFT_CONFIGURATION_ONLY",
            "APPROVAL_AND_ACTIVATION_UNAVAILABLE",
            "NO_FINANCIAL_EXECUTION",
            "NATIVE_DAY_AND_WEEK_TERMS_ONLY",
            "VERSIONED_PENALTY_REFERENCES_UNRESOLVED_UNTIL_PR13",
            "MONTHLY_POSTING_IS_NOT_MONTHLY_PAYOUT",
            "RENEWAL_COUNTS_HAVE_NO_VERIFIED_NATIVE_PRODUCT_LIMIT"));
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_INVESTMENT_PRODUCT_POLICY')")
  @Transactional(readOnly = true)
  public Configuration get(long productId) {
    authorize(READ);
    var nativeProduct = access.product(productId, false);
    var stored = stored(productId);
    return configuration(productId, stored, nativeProduct);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','UPDATE_INVESTMENT_PRODUCT_POLICY')")
  @Transactional(readOnly = true)
  public ValidationResult validate(long productId, String json) {
    authorize(MANAGE);
    var request = validation.parse(json);
    var nativeProduct = access.product(productId, false);
    var stored = stored(productId);
    revision(stored, request.expectedRevision());
    validation.validateAgainstNative(request.policy(), nativeProduct);
    return new ValidationResult(
        productId,
        stored == null ? 0 : stored.revision(),
        request.policy(),
        nativeProduct,
        assessment(request.policy(), nativeProduct, null),
        capabilities());
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','UPDATE_INVESTMENT_PRODUCT_POLICY')")
  @Transactional
  public Configuration save(long productId, String json) {
    long actor = authorize(MANAGE);
    var request = validation.parse(json);
    // Serialize policy writers on the native product and lock referenced compatibility rows.
    // The revision also protects callers who began editing earlier. No native fields are updated.
    var nativeProduct = access.product(productId, true);
    var previous = stored(productId, true);
    revision(previous, request.expectedRevision());
    validation.validateAgainstNative(request.policy(), nativeProduct);
    long current = previous == null ? 0 : previous.revision();
    String policy = JSON.toJson(request.policy()), snapshot = JSON.toJson(nativeProduct);
    var timestamp =
        Timestamp.valueOf(java.time.LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
    if (previous == null) {
      try {
        jdbc.update(
            "INSERT INTO m_investment_product_policy(product_id,revision,status,investment_type,policy_json,native_snapshot_json,"
                + "created_by,updated_by,created_at,updated_at) VALUES (?,1,'DRAFT',?,?,?,?,?,?,?)",
            productId,
            request.policy().investmentType().name(),
            policy,
            snapshot,
            actor,
            actor,
            timestamp,
            timestamp);
      } catch (org.springframework.dao.DuplicateKeyException e) {
        throw new Conflict();
      }
    } else {
      int updated =
          jdbc.update(
              "UPDATE m_investment_product_policy SET revision=revision+1,investment_type=?,policy_json=?,"
                  + "native_snapshot_json=?,updated_by=?,updated_at=? WHERE product_id=? AND revision=? AND status='DRAFT'",
              request.policy().investmentType().name(),
              policy,
              snapshot,
              actor,
              timestamp,
              productId,
              current);
      if (updated != 1) throw new Conflict();
    }
    jdbc.update(
        "INSERT INTO m_investment_product_policy_audit(product_id,previous_revision,revision,operation,actor_id,"
            + "recorded_at_utc,business_date,changed_fields_json,previous_json,new_json,native_snapshot_json) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        productId,
        current,
        current + 1,
        previous == null ? "CREATE_DRAFT" : "UPDATE_DRAFT",
        actor,
        timestamp,
        ThreadLocalContextUtil.getBusinessDate(),
        JSON.toJson(changedFields(previous, request.policy())),
        previous == null ? null : JSON.toJson(previous.policy()),
        policy,
        snapshot);
    return configuration(
        productId, new Stored(current + 1, request.policy(), snapshot), nativeProduct);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_INVESTMENT_PRODUCT_POLICY')")
  @Transactional(readOnly = true)
  public List<Audit> history(long productId, int offset, int limit) {
    authorize(READ);
    if (offset < 0 || limit < 1 || limit > 200)
      throw InvestmentProductValidation.invalid(
          "pagination", "Offset must be non-negative and limit must be 1–200.");
    access.product(productId, false);
    return jdbc.query(
        "SELECT * FROM m_investment_product_policy_audit WHERE product_id=? ORDER BY revision DESC LIMIT ? OFFSET ?",
        (rs, row) ->
            new Audit(
                productId,
                rs.getLong("previous_revision"),
                rs.getLong("revision"),
                rs.getString("operation"),
                rs.getLong("actor_id"),
                rs.getTimestamp("recorded_at_utc")
                    .toLocalDateTime()
                    .atOffset(ZoneOffset.UTC)
                    .toString(),
                rs.getString("business_date"),
                List.of(JSON.fromJson(rs.getString("changed_fields_json"), String[].class)),
                JSON.fromJson(rs.getString("previous_json"), Policy.class),
                JSON.fromJson(rs.getString("new_json"), Policy.class),
                JSON.fromJson(rs.getString("native_snapshot_json"), NativeProduct.class)),
        productId,
        limit,
        offset);
  }

  private long authorize(String permission) {
    var actor = security.authenticatedUser();
    actor.validateHasPermissionTo(permission);
    if (ThreadLocalContextUtil.getTenant() == null)
      throw new AccessDeniedException("Authenticated tenant required.");
    return actor.getId();
  }

  private Stored stored(long product) {
    return stored(product, false);
  }

  private Stored stored(long product, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT revision,policy_json,native_snapshot_json FROM m_investment_product_policy WHERE product_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, row) ->
                new Stored(
                    rs.getLong("revision"),
                    JSON.fromJson(rs.getString("policy_json"), Policy.class),
                    rs.getString("native_snapshot_json")),
            product);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static void revision(Stored stored, long expected) {
    if ((stored == null ? 0 : stored.revision()) != expected) throw new Conflict();
  }

  private Configuration configuration(long product, Stored stored, NativeProduct nativeProduct) {
    return new Configuration(
        product,
        stored == null ? 0 : stored.revision(),
        stored == null ? Status.UNCONFIGURED : Status.DRAFT,
        stored == null ? null : stored.policy(),
        nativeProduct,
        assessment(
            stored == null ? null : stored.policy(),
            nativeProduct,
            stored == null ? null : stored.nativeSnapshot()),
        capabilities());
  }

  private Assessment assessment(Policy policy, NativeProduct nativeProduct, String savedSnapshot) {
    var accounting = InvestmentProductAccess.accountingBlockers(nativeProduct);
    List<String> blockers = new ArrayList<>(accounting);
    boolean valid = false;
    if (policy == null) blockers.add("POLICY_NOT_CONFIGURED");
    else {
      try {
        validation.validateAgainstNative(policy, nativeProduct);
        valid = true;
      } catch (PlatformApiDataValidationException e) {
        blockers.add("NATIVE_POLICY_INCOMPATIBLE:" + e.getMessage());
      }
      if (policy.penaltyMode() == PenaltyMode.VERSIONED_REFERENCE)
        blockers.add("CANCELLATION_POLICY_REFERENCE_UNRESOLVED");
    }
    boolean changed =
        savedSnapshot != null
            && !com.google.gson.JsonParser.parseString(savedSnapshot)
                .equals(JSON.toJsonTree(nativeProduct));
    if (changed) blockers.add("NATIVE_CONFIGURATION_CHANGED_SINCE_SAVE");
    blockers.add("APPROVAL_AND_ACTIVATION_UNAVAILABLE");
    blockers.add("FINANCIAL_EXECUTION_UNAVAILABLE");
    return new Assessment(valid, accounting.isEmpty(), changed, List.copyOf(blockers));
  }

  private static List<String> changedFields(Stored previous, Policy next) {
    var after = JSON.toJsonTree(next).getAsJsonObject();
    var before =
        previous == null
            ? new com.google.gson.JsonObject()
            : JSON.toJsonTree(previous.policy()).getAsJsonObject();
    return after.keySet().stream()
        .filter(key -> !java.util.Objects.equals(before.get(key), after.get(key)))
        .sorted()
        .toList();
  }

  private record Stored(long revision, Policy policy, String nativeSnapshot) {}

  public static class Conflict extends RuntimeException {}

  public static class NotFound extends AbstractPlatformResourceNotFoundException {
    public NotFound() {
      super(
          "error.msg.investment.product.not.found",
          "Eligible native fixed-deposit product was not found in this tenant.");
    }
  }
}
