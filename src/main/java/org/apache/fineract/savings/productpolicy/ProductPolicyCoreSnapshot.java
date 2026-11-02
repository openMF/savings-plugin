/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Captures core defaults with the proposed policy; drift fails closed without changing core. */
@Component
@RequiredArgsConstructor
public class ProductPolicyCoreSnapshot {
  private final JdbcTemplate jdbc;
  static final String COLUMNS =
      "currency_code,currency_digits,currency_multiplesof,nominal_annual_interest_rate,interest_compounding_period_enum,interest_posting_period_enum,interest_calculation_type_enum,interest_calculation_days_in_year_type_enum,min_required_opening_balance,lockin_period_frequency,lockin_period_frequency_enum,withdrawal_fee_for_transfer,allow_overdraft,overdraft_limit,nominal_annual_interest_rate_overdraft,min_overdraft_for_interest_calculation,enforce_min_required_balance,min_required_balance,is_lien_allowed,max_allowed_lien_limit,min_balance_for_interest_calculation,withhold_tax,tax_group_id,accounting_type";

  JsonObject capture(long product) {
    var object =
        ProductPolicyWorkflow.JSON
            .toJsonTree(
                jdbc.queryForMap(
                    "SELECT " + COLUMNS + " FROM m_savings_product WHERE id=?", product))
            .getAsJsonObject();
    object.add(
        "charges",
        ProductPolicyWorkflow.JSON.toJsonTree(
            jdbc.queryForList(
                "SELECT"
                    + " c.*"
                    + " FROM m_savings_product_charge pc JOIN m_charge c ON c.id=pc.charge_id WHERE"
                    + " pc.savings_product_id=? ORDER BY c.id",
                product)));
    object.add(
        "accountingMappings",
        ProductPolicyWorkflow.JSON.toJsonTree(
            jdbc.queryForList(
                "SELECT * FROM acc_product_mapping WHERE product_id=? AND product_type=2 ORDER BY"
                    + " id",
                product)));
    return object;
  }

  void validate(long product, ProductPolicyData.Policy policy, ProductPolicyValidation validation) {
    var r =
        jdbc.queryForMap(
            "SELECT"
                + " currency_code,currency_digits,min_required_opening_balance,nominal_annual_interest_rate"
                + " FROM m_savings_product WHERE id=?",
            product);
    validation.validateProduct(
        policy,
        (String) r.get("currency_code"),
        ((Number) r.get("currency_digits")).intValue(),
        (BigDecimal) r.get("min_required_opening_balance"),
        (BigDecimal) r.get("nominal_annual_interest_rate"));
    if (policy.feeChargeId() != null
        && jdbc.queryForList(
                "SELECT id FROM m_charge WHERE id=? AND is_active=true AND is_deleted=false AND"
                    + " currency_code='MXN' AND charge_applies_to_enum=2 AND charge_time_enum=2 AND"
                    + " charge_calculation_enum=1 AND is_penalty=false FOR UPDATE",
                Long.class,
                policy.feeChargeId())
            .isEmpty())
      throw ProductPolicyValidation.invalid("feeChargeId", "Referenced fee is no longer eligible.");
  }

  void validateAccount(SavingsAccount a, JsonObject expected) {
    Map<String, Object> actual = new LinkedHashMap<>();
    actual.put("currency_code", a.getCurrency().getCode());
    actual.put("currency_digits", a.getCurrency().getDigitsAfterDecimal());
    actual.put("currency_multiplesof", a.getCurrency().getInMultiplesOf());
    actual.put("nominal_annual_interest_rate", a.getNominalAnnualInterestRate());
    actual.put("interest_compounding_period_enum", a.getInterestCompoundingPeriodType());
    actual.put("interest_posting_period_enum", a.getInterestPostingPeriodType());
    actual.put("interest_calculation_type_enum", a.getInterestCalculationType());
    actual.put(
        "interest_calculation_days_in_year_type_enum", a.getInterestCalculationDaysInYearType());
    actual.put("min_required_opening_balance", a.getMinRequiredOpeningBalance());
    actual.put("lockin_period_frequency", a.getLockinPeriodFrequency());
    actual.put("lockin_period_frequency_enum", a.getLockinPeriodFrequencyType());
    actual.put("withdrawal_fee_for_transfer", a.isWithdrawalFeeApplicableForTransfer());
    actual.put("allow_overdraft", a.isAllowOverdraft());
    actual.put("overdraft_limit", a.getOverdraftLimit());
    actual.put("nominal_annual_interest_rate_overdraft", a.getNominalAnnualInterestRateOverdraft());
    actual.put("min_overdraft_for_interest_calculation", a.getMinOverdraftForInterestCalculation());
    actual.put("enforce_min_required_balance", a.isEnforceMinRequiredBalance());
    actual.put("min_required_balance", a.getMinRequiredBalance());
    actual.put("is_lien_allowed", a.isLienAllowed());
    actual.put("max_allowed_lien_limit", a.getMaxAllowedLienLimit());
    actual.put("min_balance_for_interest_calculation", a.getMinBalanceForInterestCalculation());
    actual.put("withhold_tax", a.isWithHoldTax());
    actual.put("tax_group_id", a.getTaxGroup() == null ? null : a.getTaxGroup().getId());
    for (var entry : actual.entrySet()) {
      var value = ProductPolicyWorkflow.JSON.toJsonTree(entry.getValue());
      var required = expected.get(entry.getKey());
      // Native SavingsAccount normalizes these three nullable product defaults to zero.
      // Other nulls remain meaningful, and every nonzero caller override still conflicts.
      if (required.isJsonNull()
          && java.util.Set.of(
                  "overdraft_limit",
                  "nominal_annual_interest_rate_overdraft",
                  "min_overdraft_for_interest_calculation")
              .contains(entry.getKey()))
        required = ProductPolicyWorkflow.JSON.toJsonTree(BigDecimal.ZERO);
      if (!Objects.equals(required, value))
        throw ProductPolicyValidation.invalid(
            entry.getKey(), "Account setting conflicts with the approved product snapshot.");
    }
    // Creation saves and flushes account and charges before firing its synchronous event.
    // Compare configured amounts/types, never outstanding/paid amounts or transaction balances.
    var charges =
        jdbc.queryForList(
            "SELECT"
                + " charge_id,amount,calculation_percentage,charge_time_enum,charge_calculation_enum,fee_on_month,fee_on_day,fee_interval,is_penalty,is_active"
                + " FROM m_savings_account_charge WHERE savings_account_id=? ORDER BY charge_id",
            a.getId());
    var configured = expected.getAsJsonArray("charges");
    if (charges.size() != configured.size())
      throw ProductPolicyValidation.invalid(
          "charges", "Product charges cannot be added or removed during account opening.");
    for (int i = 0; i < charges.size(); i++) {
      var row = charges.get(i);
      var charge = configured.get(i).getAsJsonObject();
      int time = charge.get("charge_time_enum").getAsInt();
      if (time == 2 || time == 11)
        throw ProductPolicyValidation.invalid(
            "charges", "Dated product fees require a dedicated approved due-date contract.");
      for (String key : List.of("fee_on_month", "fee_on_day", "fee_interval")) {
        var required =
            (time == 6 || time == 7) && (!key.equals("fee_interval") || time == 7)
                ? charge.get(key)
                : com.google.gson.JsonNull.INSTANCE;
        if (!Objects.equals(ProductPolicyWorkflow.JSON.toJsonTree(row.get(key)), required))
          throw ProductPolicyValidation.invalid(
              "charges", "Charge schedules are product controlled.");
      }
      for (String key : List.of("is_penalty", "is_active"))
        if (!Objects.equals(ProductPolicyWorkflow.JSON.toJsonTree(row.get(key)), charge.get(key)))
          throw ProductPolicyValidation.invalid(
              "charges", "Charge eligibility is product controlled.");
      for (String key : List.of("charge_time_enum", "charge_calculation_enum"))
        if (!Objects.equals(ProductPolicyWorkflow.JSON.toJsonTree(row.get(key)), charge.get(key)))
          throw ProductPolicyValidation.invalid("charges", "Charge types are product controlled.");
      Object amount =
          ((Number) row.get("charge_calculation_enum")).intValue() == 1
              ? row.get("amount")
              : row.get("calculation_percentage");
      if (!Objects.equals(
              ProductPolicyWorkflow.JSON.toJsonTree(row.get("charge_id")), charge.get("id"))
          || !Objects.equals(ProductPolicyWorkflow.JSON.toJsonTree(amount), charge.get("amount")))
        throw ProductPolicyValidation.invalid("charges", "Charge amounts are product controlled.");
    }
  }
}
