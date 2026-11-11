/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProductPolicyOpeningEnforcementTest {
  final ProductPolicyWorkflow workflow = mock(ProductPolicyWorkflow.class);
  final ProductPolicyCoreSnapshot core = mock(ProductPolicyCoreSnapshot.class);
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final ProductPolicyOpeningEnforcement guard =
      new ProductPolicyOpeningEnforcement(
          workflow, core, jdbc, new ProductPolicyValidation(), mock(MainAccountAuditService.class));

  @Test
  void boundAccountCannotSwitchToALegacyProductToBypassOpeningValidation() {
    var account = mock(SavingsAccount.class);
    when(account.productId()).thenReturn(2L);
    when(account.getId()).thenReturn(9L);
    when(jdbc.queryForList(
            "SELECT savings_product_id,version FROM m_savings_account_policy WHERE"
                + " savings_account_id=?",
            9L))
        .thenReturn(List.of(Map.of("savings_product_id", 1L, "version", 1L)));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> guard.validate(account, "APPROVE_SAVINGSACCOUNT"));
    verifyNoInteractions(core);
  }

  @Test
  void legacyProductsKeepNativeDefaultsAndNoPolicyBinding() {
    var account = mock(SavingsAccount.class);
    when(account.productId()).thenReturn(2L);
    when(account.getId()).thenReturn(9L);
    when(jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_savings_product_policy WHERE savings_product_id=?",
            Long.class,
            2L))
        .thenReturn(0L);
    guard.validate(account, "CREATE_SAVINGSACCOUNT");
    verifyNoInteractions(core);
    verify(workflow).authorize("CREATE_SAVINGSACCOUNT");
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void futureOrSupersededVersionsAndUnavailableFinancialWorkflowsFailClosed() {
    var day = java.time.LocalDate.of(2026, 11, 1);
    org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil.setBusinessDates(
        new java.util.HashMap<>(
            Map.of(
                org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType
                    .BUSINESS_DATE,
                day)));
    try {
      var account = mock(SavingsAccount.class);
      when(account.productId()).thenReturn(2L);
      when(account.getId()).thenReturn(9L);
      when(account.clientId()).thenReturn(1L);
      when(jdbc.queryForObject(
              "SELECT COUNT(*) FROM m_savings_product_policy WHERE savings_product_id=?",
              Long.class,
              2L))
          .thenReturn(1L);
      assertThrows(
          PlatformApiDataValidationException.class,
          () -> guard.validate(account, "CREATE_SAVINGSACCOUNT"));
      for (String variant :
          List.of("TIERED", "GUARANTEED", "PLANNED_MAIN", "TERM", "SUPERSEDED", "ACTIVATION")) {
        when(account.isActive()).thenReturn(variant.equals("ACTIVATION"));
        var body =
            com.google.gson.JsonParser.parseString(ProductPolicyValidationTest.JSON)
                .getAsJsonObject();
        var policy = body.getAsJsonObject("policy");
        if (variant.equals("TIERED")) {
          policy.addProperty("classification", "INTEREST_BEARING_SIGHT");
          policy.addProperty("interestMode", "TIERED");
        }
        if (variant.equals("GUARANTEED")) {
          policy.addProperty("purpose", "GUARANTEED");
          policy.addProperty("liquidGuarantee", true);
        }
        if (variant.equals("PLANNED_MAIN")) policy.addProperty("purpose", "PLANNED_MAIN");
        if (variant.equals("TERM")) {
          policy.addProperty("purpose", "POCKET");
          policy.addProperty("multiplicity", "UNLIMITED");
          policy.addProperty("freezing", "TERM");
          policy.addProperty("freezingTermDays", 30);
        }
        var configuration = new ProductPolicyValidation().parse(body.toString()).policy();
        var version =
            new ProductPolicyWorkflowData.Version(
                2,
                1,
                variant.equals("SUPERSEDED") ? "SUPERSEDED" : "ACTIVE",
                day,
                null,
                1L,
                2L,
                java.time.Instant.now(),
                java.time.Instant.now(),
                null,
                configuration,
                Map.of());
        when(workflow.resolve(2L, day)).thenReturn(version);
        when(core.capture(2L)).thenReturn(new com.google.gson.JsonObject());
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> guard.validate(account, "CREATE_SAVINGSACCOUNT"),
            variant);
      }
      verify(jdbc, never()).update(anyString(), any(Object[].class));
    } finally {
      org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil.reset();
    }
  }
}
