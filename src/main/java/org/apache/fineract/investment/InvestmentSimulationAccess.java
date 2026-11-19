/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentProductJson.JSON;

import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tenant-routed configuration reads only. No account, customer, balance or execution dependencies.
 */
@Component
@RequiredArgsConstructor
public class InvestmentSimulationAccess {
  private final JdbcTemplate jdbc;
  private final InvestmentProductAccess products;

  record PolicySnapshot(long revision, InvestmentProductData.Policy policy) {}

  PolicySnapshot policy(long product) {
    var rows =
        jdbc.query(
            "SELECT revision,policy_json FROM m_investment_product_policy WHERE product_id=?",
            (rs, row) ->
                new PolicySnapshot(
                    rs.getLong(1),
                    JSON.fromJson(rs.getString(2), InvestmentProductData.Policy.class)),
            product);
    if (rows.isEmpty()) throw new InvestmentProductService.NotFound();
    return rows.getFirst();
  }

  InvestmentProductData.NativeProduct product(long product) {
    return products.product(product, false);
  }

  InvestmentSimulationData.NativeSettings settings(long product) {
    var nativeRow =
        jdbc.queryForMap(
            "SELECT interest_calculation_type_enum,interest_calculation_days_in_year_type_enum,"
                + "interest_compounding_period_enum,interest_posting_period_enum,min_balance_for_interest_calculation"
                + " FROM m_savings_product WHERE id=? AND deposit_type_enum=200",
            product);
    var configs =
        jdbc.queryForList(
            "SELECT name,enabled,value FROM c_configuration WHERE name IN"
                + " ('financial-year-beginning-month','savings-interest-posting-current-period-end')");
    Integer fiscalMonth = null;
    Boolean currentEnd = null;
    for (var config : configs) {
      if ("financial-year-beginning-month".equals(config.get("name")))
        fiscalMonth = Boolean.TRUE.equals(config.get("enabled")) ? number(config, "value") : 1;
      else currentEnd = (Boolean) config.get("enabled");
    }
    String rounding = null;
    Integer precision = null;
    try {
      var context = MoneyHelper.getMathContext();
      rounding = context.getRoundingMode().name();
      precision = context.getPrecision();
    } catch (IllegalStateException e) {
      // Missing tenant initialization is a prerequisite blocker, never a rounding default.
    }
    var minBalance = (BigDecimal) nativeRow.get("min_balance_for_interest_calculation");
    return new InvestmentSimulationData.NativeSettings(
        number(nativeRow, "interest_calculation_type_enum"),
        number(nativeRow, "interest_calculation_days_in_year_type_enum"),
        number(nativeRow, "interest_compounding_period_enum"),
        number(nativeRow, "interest_posting_period_enum"),
        minBalance == null ? BigDecimal.ZERO : minBalance,
        fiscalMonth,
        currentEnd,
        rounding,
        precision);
  }

  private static Integer number(Map<String, Object> row, String column) {
    var value = (Number) row.get(column);
    return value == null
            || value.longValue() < Integer.MIN_VALUE
            || value.longValue() > Integer.MAX_VALUE
        ? null
        : value.intValue();
  }
}
