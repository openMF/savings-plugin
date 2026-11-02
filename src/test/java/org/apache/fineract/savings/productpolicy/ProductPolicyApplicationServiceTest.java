/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.*;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;

class ProductPolicyApplicationServiceTest {
  final ProductPolicyWorkflow workflow = mock(ProductPolicyWorkflow.class);
  final PortfolioCommandSourceWritePlatformService commands =
      mock(PortfolioCommandSourceWritePlatformService.class);
  final ProductPolicyApplicationService service =
      new ProductPolicyApplicationService(workflow, commands);

  @AfterEach
  void clear() {
    ThreadLocalContextUtil.reset();
  }

  @Test
  void permissionsAreCheckedBeforeParsingOrNativeCommandDispatch() {
    when(workflow.authorize("CREATE_SAVINGSACCOUNT"))
        .thenThrow(new AccessDeniedException("Denied"));
    assertThrows(AccessDeniedException.class, () -> service.apply(1, "{}"));
    verifyNoInteractions(commands);
  }

  @Test
  void protectedParametersDuplicatesAndInvalidIdentifiersAreRejectedBeforeDispatch() {
    for (String body :
        List.of(
            "{}",
            "{",
            "{\"clientId\":1,\"clientId\":2,\"expectedPolicyVersion\":1}",
            "{\"clientId\":1,\"expectedPolicyVersion\":1,\"nominalAnnualInterestRate\":9}",
            "{\"clientId\":1,\"expectedPolicyVersion\":1,\"purpose\":\"MAIN\"}",
            "{\"clientId\":1,\"expectedPolicyVersion\":1,\"tenantId\":2}",
            "{\"clientId\":-1,\"expectedPolicyVersion\":1}",
            "{\"clientId\":\"1\",\"expectedPolicyVersion\":1}"))
      assertThrows(PlatformApiDataValidationException.class, () -> service.apply(1, body));
    verifyNoInteractions(commands);
  }

  @Test
  void pendingOrStaleSelectedVersionsNeverDispatchNativeCreation() {
    ThreadLocalContextUtil.setBusinessDates(
        new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 11, 1))));
    assertThrows(
        ProductPolicyService.Conflict.class,
        () -> service.apply(1, "{\"clientId\":1,\"expectedPolicyVersion\":1}"));
    verifyNoInteractions(commands);
  }
}
