/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.service;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.CatalogUpdateCategory;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Validates the live tenant catalog tables used by Base Teller. These queries intentionally bypass
 * application caches. WEB-1254 separately evicts only cache entries with safe, known tenant keys;
 * it never clears all caches or toggles Fineract's global cache mode.
 */
@Component
@RequiredArgsConstructor
public class JdbcCatalogSourceValidator implements CatalogSourceValidator {

  private static final List<String> GENERAL_SOURCES =
      List.of(
          "SELECT COUNT(*) FROM m_savings_product",
          "SELECT COUNT(*) FROM m_payment_type",
          "SELECT COUNT(*) FROM m_organisation_currency",
          "SELECT COUNT(*) FROM m_code c LEFT JOIN m_code_value cv ON cv.code_id=c.id",
          "SELECT COUNT(*) FROM m_service_payment_denomination",
          "SELECT COUNT(*) FROM m_service_payment_service");
  private static final List<String> ACCOUNTING_SOURCES =
      List.of(
          "SELECT COUNT(*) FROM acc_gl_account",
          "SELECT COUNT(*) FROM acc_gl_financial_activity_account",
          "SELECT COUNT(*) FROM m_savings_product",
          "SELECT COUNT(*) FROM m_product_loan",
          "SELECT COUNT(*) FROM m_service_payment_service"
              + " WHERE cash_gl_account_id IS NOT NULL"
              + " OR settlement_gl_account_id IS NOT NULL"
              + " OR commission_gl_account_id IS NOT NULL"
              + " OR vat_gl_account_id IS NOT NULL");
  private static final List<String> USER_SOURCES =
      List.of(
          "SELECT COUNT(*) FROM m_role",
          "SELECT COUNT(*) FROM m_permission",
          "SELECT COUNT(*) FROM m_role_permission");

  private final NamedParameterJdbcTemplate jdbcTemplate;

  @Override
  public void validate(
      final CatalogUpdateCategory category,
      final Long officeId,
      final String officeHierarchy,
      final Long userId) {
    final Map<String, Object> scope =
        Map.of(
            "officeId", officeId,
            "officeHierarchy", officeHierarchy + "%",
            "userId", userId);
    switch (category) {
      case GENERAL -> {
        validateAll(GENERAL_SOURCES, scope);
        query("SELECT COUNT(*) FROM m_office WHERE hierarchy LIKE :officeHierarchy", scope);
        query(
            "SELECT COUNT(*) FROM m_tellers t JOIN m_office o ON o.id=t.office_id"
                + " WHERE o.hierarchy LIKE :officeHierarchy",
            scope);
        query(
            "SELECT COUNT(*) FROM m_cashiers c JOIN m_tellers t ON t.id=c.teller_id"
                + " JOIN m_office o ON o.id=t.office_id"
                + " WHERE o.hierarchy LIKE :officeHierarchy",
            scope);
      }
      case ACCOUNTING -> validateAll(ACCOUNTING_SOURCES, scope);
      case USERS -> {
        validateAll(USER_SOURCES, scope);
        final long authenticatedUserCount =
            query(
                "SELECT COUNT(*) FROM m_appuser u JOIN m_office o ON o.id=u.office_id"
                    + " WHERE u.id=:userId AND o.hierarchy LIKE :officeHierarchy",
                scope);
        if (authenticatedUserCount != 1) {
          throw new PlatformDataIntegrityException(
              "error.msg.base.teller.catalog.update.user.scope.invalid",
              "The authenticated user is not available in the catalog update office scope.");
        }
        query(
            "SELECT COUNT(*) FROM m_staff s JOIN m_office o ON o.id=s.office_id"
                + " WHERE o.hierarchy LIKE :officeHierarchy",
            scope);
        query(
            "SELECT COUNT(*) FROM m_cashiers c JOIN m_tellers t ON t.id=c.teller_id"
                + " JOIN m_office o ON o.id=t.office_id"
                + " WHERE o.hierarchy LIKE :officeHierarchy",
            scope);
      }
    }
  }

  private void validateAll(final List<String> sources, final Map<String, Object> parameters) {
    sources.forEach(sql -> query(sql, parameters));
  }

  private long query(final String sql, final Map<String, Object> parameters) {
    final Long count = jdbcTemplate.queryForObject(sql, parameters, Long.class);
    if (count == null) {
      throw new PlatformDataIntegrityException(
          "error.msg.base.teller.catalog.update.source.unavailable",
          "A required catalog source could not be validated.");
    }
    return count;
  }
}
