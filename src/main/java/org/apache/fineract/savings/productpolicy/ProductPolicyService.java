/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ProductPolicyData.*;

import com.google.gson.Gson;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;
import org.apache.fineract.infrastructure.core.exception.PlatformInternalServerException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped draft aggregate. Core products and charges are read, never modified. */
@Service
@RequiredArgsConstructor
public class ProductPolicyService {
  public static final String READ = "READ_SAVINGS_PRODUCT_POLICY";
  public static final String WRITE = "UPDATE_SAVINGS_PRODUCT_POLICY";
  private static final Gson GSON = new Gson();
  private final PlatformSecurityContext security;
  private final JdbcTemplate jdbc;
  private final ProductPolicyValidation validation;

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS', 'READ_SAVINGS_PRODUCT_POLICY')")
  @Transactional(readOnly = true)
  public Capabilities template() {
    authorize(READ);
    return capabilities();
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS', 'READ_SAVINGS_PRODUCT_POLICY')")
  @Transactional(readOnly = true)
  public Configuration get(long productId) {
    authorize(READ);
    try {
      product(productId, false);
      List<Configuration> rows =
          jdbc.query(
              "SELECT revision,policy_json FROM m_savings_product_policy WHERE savings_product_id=?",
              (rs, row) ->
                  new Configuration(
                      productId,
                      rs.getLong(1),
                      Status.DRAFT,
                      false,
                      GSON.fromJson(rs.getString(2), Policy.class),
                      capabilities()),
              productId);
      return rows.isEmpty()
          ? new Configuration(productId, null, Status.UNCONFIGURED, false, null, capabilities())
          : rows.getFirst();
    } catch (DataAccessException e) {
      throw unavailable();
    }
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS', 'UPDATE_SAVINGS_PRODUCT_POLICY')")
  @Transactional
  public Configuration save(long productId, String json) {
    long actorId = authorize(WRITE);
    WriteRequest request = validation.parse(json);
    try {
      // Lock the authoritative product before reading its currency/rate/opening balance. Every
      // writer of this aggregate takes the same lock; revision is still mandatory for lost updates.
      product(productId, true);
      var core =
          jdbc.queryForMap(
              "SELECT currency_code,currency_digits,min_required_opening_balance,nominal_annual_interest_rate FROM m_savings_product WHERE id=?",
              productId);
      validation.validateProduct(
          request.policy(),
          (String) core.get("currency_code"),
          ((Number) core.get("currency_digits")).intValue(),
          (java.math.BigDecimal) core.get("min_required_opening_balance"),
          (java.math.BigDecimal) core.get("nominal_annual_interest_rate"));
      Long chargeId = request.policy().feeChargeId();
      if (chargeId != null) {
        // Only an active, non-penalty, flat specified-due-date savings charge is a supported
        // reference. Automatic monthly-average posting is deliberately absent.
        var charges =
            jdbc.queryForList(
                "SELECT id FROM m_charge WHERE id=? AND is_active=true AND is_deleted=false AND currency_code='MXN' AND charge_applies_to_enum=2 AND charge_time_enum=2 AND charge_calculation_enum=1 AND is_penalty=false FOR UPDATE",
                Long.class,
                chargeId);
        if (charges.isEmpty())
          throw ProductPolicyValidation.invalid(
              "feeChargeId", "Charge must be an active MXN flat specified-due-date savings fee.");
      }
      var revisions =
          jdbc.queryForList(
              "SELECT revision FROM m_savings_product_policy WHERE savings_product_id=?",
              Long.class,
              productId);
      long current = revisions.isEmpty() ? 0 : revisions.getFirst();
      if (current != request.expectedRevision()) throw new Conflict();
      String policy = GSON.toJson(request.policy());
      if (current == 0) {
        jdbc.update(
            "INSERT INTO m_savings_product_policy(savings_product_id,revision,status,policy_json,fee_charge_id,created_by,updated_by,created_at,updated_at) VALUES (?,1,'DRAFT',?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            productId,
            policy,
            chargeId,
            actorId,
            actorId);
      } else {
        int changed =
            jdbc.update(
                "UPDATE m_savings_product_policy SET revision=revision+1,policy_json=?,fee_charge_id=?,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE savings_product_id=? AND revision=? AND status='DRAFT'",
                policy,
                chargeId,
                actorId,
                productId,
                current);
        if (changed != 1) throw new Conflict();
      }
      return new Configuration(
          productId, current + 1, Status.DRAFT, false, request.policy(), capabilities());
    } catch (DataAccessException e) {
      throw unavailable();
    }
  }

  private long authorize(String permission) {
    var actor = security.authenticatedUser();
    actor.validateHasPermissionTo(permission);
    if (ThreadLocalContextUtil.getTenant() == null)
      throw new AccessDeniedException("An authenticated tenant is required.");
    return actor.getId();
  }

  private void product(long productId, boolean lock) {
    if (productId <= 0)
      throw ProductPolicyValidation.invalid(
          "savingsProductId", "A positive product ID is required.");
    var found =
        jdbc.queryForList(
            "SELECT id FROM m_savings_product WHERE id=? AND deposit_type_enum=100"
                + (lock ? " FOR UPDATE" : ""),
            Long.class,
            productId);
    if (found.isEmpty()) throw new NotFound();
  }

  private static PlatformInternalServerException unavailable() {
    return new PlatformInternalServerException(
        "error.msg.savings.product.policy.unavailable",
        "Product policy storage is unavailable; no core product operation was performed. Retrieve the draft before retrying.");
  }

  public static class Conflict extends RuntimeException {}

  public static class NotFound extends AbstractPlatformResourceNotFoundException {
    public NotFound() {
      super(
          "error.msg.savings.product.policy.product.not.found",
          "Eligible savings product was not found in this tenant.");
    }
  }
}
