/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentProductData.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Uses Fineract's tenant-routed JdbcTemplate; never caches tenant product state. */
@Component
@RequiredArgsConstructor
public class InvestmentProductAccess {
  private final JdbcTemplate jdbc;

  NativeProduct product(long id, boolean lock) {
    if (id <= 0)
      throw InvestmentProductValidation.invalid(
          "productId", "A positive native product ID is required.");
    var ids =
        jdbc.queryForList(
            "SELECT id FROM m_savings_product WHERE id=? AND deposit_type_enum=200"
                + (lock ? " FOR UPDATE" : ""),
            Long.class,
            id);
    if (ids.isEmpty()) throw new InvestmentProductService.NotFound();
    // Native updates can dirty the term child without updating the parent product row.
    if (lock) {
      jdbc.queryForList(
          "SELECT id FROM m_deposit_product_term_and_preclosure WHERE savings_product_id=? FOR UPDATE",
          Long.class,
          id);
      jdbc.queryForList(
          "SELECT id FROM acc_product_mapping WHERE product_id=? AND product_type=2 ORDER BY id FOR UPDATE",
          Long.class,
          id);
      jdbc.queryForList(
          "SELECT id FROM acc_gl_account WHERE id IN (SELECT gl_account_id FROM acc_product_mapping WHERE product_id=? AND product_type=2) ORDER BY id FOR UPDATE",
          Long.class,
          id);
    }
    var products =
        jdbc.query(
            "SELECT sp.*, t.id AS native_term_id, t.min_deposit_term, t.max_deposit_term,"
                + " t.min_deposit_term_type_enum,t.max_deposit_term_type_enum,t.in_multiples_of_deposit_term,"
                + " t.in_multiples_of_deposit_term_type_enum,t.min_deposit_amount,t.max_deposit_amount,t.deposit_amount,"
                + " t.pre_closure_penal_applicable,t.pre_closure_penal_interest,t.pre_closure_penal_interest_on_enum"
                + " FROM m_savings_product sp LEFT JOIN m_deposit_product_term_and_preclosure t ON t.savings_product_id=sp.id"
                + " WHERE sp.id=? AND sp.deposit_type_enum=200",
            (rs, row) -> {
              if (rs.getObject("native_term_id") == null)
                throw InvestmentProductValidation.invalid(
                    "nativeProduct", "Native fixed-deposit term configuration is missing.");
              return new NativeProduct(
                  id,
                  rs.getString("name"),
                  rs.getString("currency_code"),
                  rs.getInt("currency_digits"),
                  integer(rs, "currency_multiplesof"),
                  new NativeTerms(
                      integer(rs, "min_deposit_term"),
                      integer(rs, "min_deposit_term_type_enum"),
                      integer(rs, "max_deposit_term"),
                      integer(rs, "max_deposit_term_type_enum"),
                      integer(rs, "in_multiples_of_deposit_term"),
                      integer(rs, "in_multiples_of_deposit_term_type_enum")),
                  rs.getBigDecimal("min_deposit_amount"),
                  rs.getBigDecimal("max_deposit_amount"),
                  rs.getBigDecimal("deposit_amount"),
                  new NativePenalty(
                      rs.getBoolean("pre_closure_penal_applicable"),
                      rs.getBigDecimal("pre_closure_penal_interest"),
                      integer(rs, "pre_closure_penal_interest_on_enum")),
                  rs.getInt("accounting_type"),
                  rs.getBoolean("is_dormancy_tracking_active"),
                  integer(rs, "interest_posting_period_enum"),
                  integer(rs, "interest_compounding_period_enum"),
                  rs.getBoolean("withhold_tax"),
                  number(rs, "tax_group_id"),
                  mappings(id));
            },
            id);
    if (products.isEmpty()) throw new InvestmentProductService.NotFound();
    if (products.size() != 1)
      throw InvestmentProductValidation.invalid(
          "nativeProduct", "Native term configuration must be unique.");
    return products.getFirst();
  }

  private List<AccountingMapping> mappings(long id) {
    return jdbc.query(
        "SELECT m.id,m.financial_account_type,m.gl_account_id,g.disabled,g.classification_enum,g.account_usage,"
            + "m.payment_type,m.charge_id FROM acc_product_mapping m LEFT JOIN acc_gl_account g ON g.id=m.gl_account_id"
            + " WHERE m.product_id=? AND m.product_type=2 ORDER BY m.id",
        (rs, row) ->
            new AccountingMapping(
                rs.getLong("id"),
                integer(rs, "financial_account_type"),
                number(rs, "gl_account_id"),
                rs.getObject("disabled") == null || rs.getBoolean("disabled"),
                integer(rs, "classification_enum"),
                integer(rs, "account_usage"),
                number(rs, "payment_type"),
                number(rs, "charge_id")),
        id);
  }

  /** Mirrors verified native fixed-deposit GL placeholders; overdraft roles are not required. */
  static List<String> accountingBlockers(NativeProduct product) {
    List<String> blockers = new ArrayList<>();
    if (product.accountingRule() != 2 && product.accountingRule() != 3) {
      blockers.add("NATIVE_ACCOUNTING_DISABLED_OR_UNSUPPORTED");
      return blockers;
    }
    Map<Integer, Integer> required =
        new LinkedHashMap<>(Map.of(1, 1, 2, 2, 3, 5, 4, 4, 5, 4, 10, 2));
    if (product.dormancyTracking()) required.put(14, 2);
    if (product.accountingRule() == 3) {
      required.put(15, 1);
      required.put(16, 1);
      required.put(17, 2);
    }
    for (var role : required.entrySet()) {
      var rows =
          product.accountingMappings().stream()
              .filter(
                  m ->
                      role.getKey().equals(m.financialAccountType())
                          && m.paymentType() == null
                          && m.chargeId() == null)
              .toList();
      if (rows.size() != 1) blockers.add("NATIVE_GL_MAPPING_MISSING_OR_DUPLICATE:" + role.getKey());
    }
    Map<Integer, Integer> types =
        Map.ofEntries(
            Map.entry(1, 1),
            Map.entry(2, 2),
            Map.entry(3, 5),
            Map.entry(4, 4),
            Map.entry(5, 4),
            Map.entry(10, 2),
            Map.entry(11, 1),
            Map.entry(12, 4),
            Map.entry(13, 5),
            Map.entry(14, 2),
            Map.entry(15, 1),
            Map.entry(16, 1),
            Map.entry(17, 2),
            Map.entry(18, 1));
    for (var mapping : product.accountingMappings()) {
      Integer type =
          mapping.financialAccountType() == null ? null : types.get(mapping.financialAccountType());
      if (type == null
          || mapping.glAccountId() == null
          || mapping.disabled()
          || !Integer.valueOf(1).equals(mapping.usage())
          || !type.equals(mapping.classification()))
        blockers.add("NATIVE_GL_MAPPING_INCOMPATIBLE:" + mapping.mappingId());
    }
    return List.copyOf(blockers);
  }

  private static Integer integer(ResultSet rs, String column) throws SQLException {
    var value = (Number) rs.getObject(column);
    return value == null ? null : value.intValue();
  }

  private static Long number(ResultSet rs, String column) throws SQLException {
    var value = (Number) rs.getObject(column);
    return value == null ? null : value.longValue();
  }
}
