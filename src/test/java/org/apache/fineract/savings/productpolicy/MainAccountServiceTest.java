/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

class MainAccountServiceTest {
  final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final ProductPolicyWorkflow workflow = mock(ProductPolicyWorkflow.class);
  final ProductTransactionPolicyResolver resolver = mock(ProductTransactionPolicyResolver.class);
  final SavingsAccountRepositoryWrapper accounts = mock(SavingsAccountRepositoryWrapper.class);
  final MainAccountAuditService audit = mock(MainAccountAuditService.class);
  final MainAccountService service =
      new MainAccountService(security, jdbc, workflow, resolver, accounts, audit);

  AppUser authenticated() {
    var actor = mock(AppUser.class);
    when(actor.getId()).thenReturn(1L);
    when(security.authenticatedUser()).thenReturn(actor);
    ThreadLocalContextUtil.setTenant(mock(FineractPlatformTenant.class));
    ProductPolicyWorkflowIntegrationTest.setDay(ProductPolicyWorkflowIntegrationTest.DAY);
    return actor;
  }

  @Test
  void strictBindingRequestRejectsMalformedTypesDuplicateFieldsAndOverridesBeforeReadingAccounts() {
    authenticated();
    for (String json :
        List.of(
            "{}",
            "{\"savingsAccountId\":0}",
            "{\"savingsAccountId\":-1}",
            "{\"savingsAccountId\":1.0}",
            "{\"savingsAccountId\":1e0}",
            "{\"savingsAccountId\":\"1\"}",
            "{\"savingsAccountId\":1000000000000000000}",
            "{\"savingsAccountId\":null}",
            "{\"savingsAccountId\":1,\"savingsAccountId\":2}",
            "{\"savingsAccountId\":1,\"override\":true}",
            "{\"savingsAccountId\":1,\"clientId\":2}",
            "[]",
            "{} {}")) {
      assertThrows(
          org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException
              .class,
          () -> service.bind(1, json),
          json);
    }
    assertThrows(
        org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException.class,
        () -> service.bind(1, null));
    verify(jdbc, never()).update(anyString(), any(Object[].class));
    verifyNoInteractions(workflow, resolver, accounts, audit);
  }

  @Test
  void missingPermissionTenantOrBusinessDateCannotReadOrBind() {
    var actor = authenticated();
    doThrow(new AccessDeniedException("Denied"))
        .when(actor)
        .validateHasPermissionTo(MainAccountService.BIND);
    assertThrows(AccessDeniedException.class, () -> service.bind(1, "{\"savingsAccountId\":1}"));
    ThreadLocalContextUtil.clearTenant();
    assertThrows(AccessDeniedException.class, () -> service.get(1));
    ThreadLocalContextUtil.setTenant(mock(FineractPlatformTenant.class));
    ThreadLocalContextUtil.setBusinessDates(new java.util.HashMap<>());
    assertThrows(AccessDeniedException.class, () -> service.audit(1, 0, 10));
    verifyNoInteractions(workflow, resolver, accounts, audit);
  }

  @Test
  void guaranteeModelsAndCapabilitiesDoNotEnableFinancialOperations() {
    authenticated();
    var capabilities = service.template();
    assertTrue(capabilities.mainAccountRelationships());
    assertFalse(capabilities.guaranteedAccountCreation());
    assertFalse(capabilities.guaranteeAllocation());
    assertFalse(capabilities.guaranteeRelease());
    assertFalse(capabilities.loanDisbursementRouting());
    assertFalse(capabilities.paymentReferenceProvider());
    assertEquals(
        List.of(GuaranteeRelationshipData.State.BLOCKED_PENDING_APPROVAL),
        List.of(GuaranteeRelationshipData.State.values()));
    assertFalse(ProductPolicyData.capabilities().financialEnforcement());
    assertFalse(ProductTransactionPolicyData.capabilities().freezingLifecycle());
    assertFalse(ProductTransactionPolicyData.capabilities().universalNativeCoverage());
    verifyNoInteractions(jdbc, workflow, resolver, accounts, audit);
  }

  @AfterEach
  void clear() {
    ThreadLocalContextUtil.reset();
  }
}
