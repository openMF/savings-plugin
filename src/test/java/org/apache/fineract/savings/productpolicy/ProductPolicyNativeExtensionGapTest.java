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
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.data.SavingsAccountDataDTO;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.service.SavingsApplicationProcessWritePlatformServiceJpaRepositoryImpl;
import org.junit.jupiter.api.Test;

/** Pins the dependency's eventless internal path so its known enforcement gap is explicit. */
class ProductPolicyNativeExtensionGapTest {
  static Object dependency(Class<?> type) {
    if (!java.lang.reflect.Modifier.isFinal(type.getModifiers())) return mock(type);
    try {
      var constructor = type.getConstructors()[0];
      return constructor.newInstance(new Object[constructor.getParameterCount()]);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void directInternalAutoOpeningDoesNotEmitASupportedValidationEvent() throws Exception {
    var constructor =
        Arrays.stream(
                SavingsApplicationProcessWritePlatformServiceJpaRepositoryImpl.class
                    .getConstructors())
            .max(Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount))
            .orElseThrow();
    Map<Class<?>, Object> dependencies = new HashMap<>();
    Object[] arguments =
        Arrays.stream(constructor.getParameterTypes())
            .map(type -> dependencies.computeIfAbsent(type, t -> dependency(t)))
            .toArray();
    var nativeService =
        (SavingsApplicationProcessWritePlatformServiceJpaRepositoryImpl)
            constructor.newInstance(arguments);
    var assembler = (SavingsAccountAssembler) dependencies.get(SavingsAccountAssembler.class);
    var account = mock(SavingsAccount.class);
    when(account.getId()).thenReturn(1L);
    when(account.activateWithBalance()).thenReturn(mock(Money.class));
    var dto = mock(SavingsAccountDataDTO.class);
    when(assembler.assembleFrom(
            dto.getClient(),
            dto.getGroup(),
            dto.getSavingsProduct(),
            dto.getApplicationDate(),
            dto.getAppliedBy()))
        .thenReturn(account);
    assertEquals(1L, nativeService.createActiveApplication(dto).getSavingsId());
    verify(account).approveAndActivateApplication(dto.getApplicationDate(), dto.getAppliedBy());
    verifyNoInteractions(
        (BusinessEventNotifierService) dependencies.get(BusinessEventNotifierService.class));
    // Client API callers are protected by client events; other direct callers need a core event.
  }
}
