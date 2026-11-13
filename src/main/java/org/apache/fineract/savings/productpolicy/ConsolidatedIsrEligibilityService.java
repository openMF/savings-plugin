/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.ConsolidatedIsrData.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Native identities and immutable policy authorization, without today's policy lifecycle writes.
 */
@Component
@RequiredArgsConstructor
public class ConsolidatedIsrEligibilityService {
  static final int MAX_ACCOUNTS = 200;
  private final JdbcTemplate jdbc;
  private final ConsolidatedIsrAccess access;
  private final ProductPolicyWorkflow workflow;
  private final ProductPolicyCoreSnapshot core;

  List<Account> accounts(long client, Period period) {
    return jdbc
        .queryForList(
            "SELECT a.id,a.product_id,a.client_id,a.group_id,c.office_id,"
                + "a.deposit_type_enum,a.status_enum,a.sub_status_enum,a.activatedon_date,a.closedon_date,"
                + "a.currency_code,a.currency_digits,a.withhold_tax,a.tax_group_id,a.account_balance_derived,"
                + "p.accounting_type,b.version,b.savings_product_id AS bound_product,b.purpose AS bound_purpose,"
                + "r.main_savings_account_id,t.id AS tax_group_exists"
                + " FROM m_savings_account a JOIN m_client c ON c.id=a.client_id JOIN m_office o ON o.id=c.office_id"
                + " JOIN m_savings_product p ON p.id=a.product_id"
                + " LEFT JOIN m_savings_account_policy b ON b.savings_account_id=a.id"
                + " LEFT JOIN m_savings_pocket_relationship r ON r.savings_account_id=a.id AND r.status='LINKED'"
                + " LEFT JOIN m_tax_group t ON t.id=a.tax_group_id"
                + " WHERE a.client_id=? AND o.hierarchy LIKE ? ORDER BY a.id LIMIT ?",
            client,
            access.hierarchy(),
            MAX_ACCOUNTS + 1)
        .stream()
        .map(r -> account(r, period))
        .toList();
  }

  private Account account(Map<String, Object> r, Period period) {
    var reasons = new ArrayList<Reason>();
    String classification = null, purpose = null, evidence = null;
    Long version = number(r, "version"), product = number(r, "product_id");
    int type = integer(r, "deposit_type_enum"), status = integer(r, "status_enum");
    if (r.get("group_id") != null) reasons.add(Reason.ACCOUNT_OWNERSHIP_UNSUPPORTED);
    if (type != 100) reasons.add(Reason.INVESTMENT_TREATMENT_UNAPPROVED);
    if (status != 300
        || integer(r, "sub_status_enum") != 0
        || date(r, "activatedon_date") == null
        || date(r, "activatedon_date").isAfter(period.from())
        || date(r, "closedon_date") != null) reasons.add(Reason.ACCOUNT_STATUS_INELIGIBLE);
    if (r.get("tax_group_exists") == null) reasons.add(Reason.TAX_GROUP_UNAVAILABLE);
    String currency = (String) r.get("currency_code");
    int digits = integer(r, "currency_digits");
    if (currency == null || !currency.matches("[A-Z]{3}") || digits < 0 || digits > 6)
      reasons.add(Reason.CURRENCY_UNSUPPORTED);
    if (r.get("account_balance_derived") == null) reasons.add(Reason.SOURCE_DATA_UNAVAILABLE);
    ProductPolicyWorkflowData.Version approved;
    try {
      approved = workflow.resolve(product, period.through());
    } catch (com.google.gson.JsonParseException | IllegalArgumentException e) {
      approved = null;
    }
    if (version == null
        || !Objects.equals(product, number(r, "bound_product"))
        || approved == null
        || approved.checkerId() == null
        || approved.policy() == null
        || approved.coreSettings() == null) reasons.add(Reason.PRODUCT_POLICY_UNAVAILABLE);
    else {
      var p = approved.policy();
      classification = p.classification() == null ? null : p.classification().name();
      purpose = p.purpose() == null ? null : p.purpose().name();
      if (!Objects.equals(purpose, r.get("bound_purpose")))
        reasons.add(Reason.PRODUCT_POLICY_UNAVAILABLE);
      if (version != approved.version()) reasons.add(Reason.PRODUCT_POLICY_NOT_EFFECTIVE);
      if (approved.effectiveDate().isAfter(period.from()))
        reasons.add(Reason.PRODUCT_POLICY_TRANSITION_UNSUPPORTED);
      var current = core.capture(product);
      evidence = ConsolidatedIsrValidation.hash(List.of(approved, current));
      if (!ProductPolicyWorkflow.JSON.toJsonTree(approved.coreSettings()).equals(current))
        reasons.add(Reason.PRODUCT_CORE_CHANGED);
      if (current.get("currency_code").isJsonNull()
          || current.get("currency_digits").isJsonNull()
          || current.get("withhold_tax").isJsonNull()
          || r.get("withhold_tax") == null) reasons.add(Reason.SOURCE_DATA_UNAVAILABLE);
      else {
        if (!Objects.equals(currency, current.get("currency_code").getAsString())
            || digits != current.get("currency_digits").getAsInt())
          reasons.add(Reason.CURRENCY_UNSUPPORTED);
        if (Boolean.TRUE.equals(r.get("withhold_tax")) != current.get("withhold_tax").getAsBoolean()
            || !Objects.equals(
                number(r, "tax_group_id"), number(approved.coreSettings(), "tax_group_id")))
          reasons.add(Reason.TAX_CONFIGURATION_MISMATCH);
      }
    }
    if (!List.of("SAVINGS", "INTEREST_BEARING_SIGHT")
            .contains(classification == null ? "" : classification)
        || type != 100) reasons.add(Reason.ACCOUNT_CLASSIFICATION_UNSUPPORTED);
    boolean structural = reasons.isEmpty();
    reasons.add(Reason.ISR_POLICY_NOT_APPROVED);
    reasons.add(Reason.TAX_PROFILE_UNVERIFIED);
    reasons.add(Reason.AGGREGATION_BOUNDARY_UNAPPROVED);
    reasons.add(Reason.HISTORICAL_ACCOUNT_STATE_UNVERIFIED);
    if (Boolean.TRUE.equals(r.get("withhold_tax")))
      reasons.add(Reason.NATIVE_WITHHOLDING_REMAINS_AUTHORITATIVE);
    return new Account(
        number(r, "id"),
        product,
        number(r, "client_id"),
        number(r, "office_id"),
        type,
        status,
        integer(r, "sub_status_enum"),
        date(r, "activatedon_date"),
        date(r, "closedon_date"),
        currency,
        digits,
        version,
        classification,
        purpose,
        number(r, "main_savings_account_id"),
        Boolean.TRUE.equals(r.get("withhold_tax")),
        number(r, "tax_group_id"),
        r.get("accounting_type") == null ? null : integer(r, "accounting_type"),
        (BigDecimal) r.get("account_balance_derived"),
        evidence,
        structural,
        false,
        reasons);
  }

  static Long number(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : ((Number) row.get(key)).longValue();
  }

  static int integer(Map<String, Object> row, String key) {
    return row.get(key) == null ? -1 : ((Number) row.get(key)).intValue();
  }

  static LocalDate date(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : ((java.sql.Date) row.get(key)).toLocalDate();
  }
}
