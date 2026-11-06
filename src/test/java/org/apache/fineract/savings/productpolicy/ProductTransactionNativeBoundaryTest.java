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
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.event.business.domain.savings.transaction.*;
import org.apache.fineract.infrastructure.event.business.service.*;
import org.apache.fineract.infrastructure.event.external.service.ExternalEventService;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountDomainServiceJpa;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformServiceJpaRepositoryImpl;
import org.apache.fineract.portfolio.savings.service.SavingsApplicationProcessWritePlatformServiceJpaRepositoryImpl;
import org.junit.jupiter.api.Test;
import org.springframework.asm.*;

/**
 * Pins real dependency bytecode and local synchronous listeners, not external after-commit
 * delivery.
 */
class ProductTransactionNativeBoundaryTest {
  static List<String> calls(Class<?> type, String method) throws Exception {
    List<String> calls = new ArrayList<>();
    try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
      assertNotNull(stream);
      new ClassReader(stream)
          .accept(
              new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                  if (!method.equals(name)) return null;
                  return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                        int opcode,
                        String owner,
                        String name,
                        String descriptor,
                        boolean isInterface) {
                      calls.add(owner + "." + name);
                    }
                  };
                }
              },
              ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    return calls;
  }

  @Test
  void depositAndWithdrawalEventsFollowMutationAndJournalPostingInPinnedDependency()
      throws Exception {
    for (String method : List.of("handleDeposit", "handleWithdrawal")) {
      var calls = calls(SavingsAccountDomainServiceJpa.class, method);
      int journal = -1, post = -1;
      for (int i = 0; i < calls.size(); i++) {
        if (calls.get(i).endsWith(".postJournalEntries")) journal = i;
        if (calls.get(i).endsWith(".notifyPostBusinessEvent")) post = i;
      }
      assertTrue(journal >= 0 && post > journal, calls.toString());
      assertTrue(calls.stream().noneMatch(c -> c.endsWith(".notifyPreBusinessEvent")));
    }
  }

  @Test
  void adjustmentUndoAndInternalAutoOpeningHaveNoSuitableValidationEvent() throws Exception {
    for (String method : List.of("adjustSavingsTransaction", "undoTransaction")) {
      var calls = calls(SavingsAccountWritePlatformServiceJpaRepositoryImpl.class, method);
      assertFalse(calls.isEmpty());
      assertTrue(
          calls.stream()
              .noneMatch(
                  c ->
                      c.contains(".notifyPostBusinessEvent")
                          || c.contains(".notifyPreBusinessEvent")),
          calls.toString());
    }
    var calls =
        calls(
            SavingsApplicationProcessWritePlatformServiceJpaRepositoryImpl.class,
            "createActiveApplication");
    assertTrue(calls.stream().anyMatch(c -> c.endsWith(".approveAndActivateApplication")));
    assertTrue(calls.stream().noneMatch(c -> c.contains(".notifyPostBusinessEvent")));
  }

  @Test
  void realNotifierPropagatesLocalVetoSynchronouslyEvenWhenExternalEventsAreDisabled() {
    var properties = new FineractProperties();
    properties.setEvents(new FineractProperties.FineractEventsProperties());
    properties.getEvents().setExternal(new FineractProperties.FineractExternalEventsProperties());
    var external = mock(ExternalEventService.class);
    var notifier =
        new BusinessEventNotifierServiceImpl(
            external,
            properties,
            mock(TransactionHelper.class),
            mock(ExternalBusinessEventConfigurationService.class));
    var enforcement = mock(ProductTransactionEnforcement.class);
    new ProductTransactionRestrictionGuard(notifier, enforcement).register();
    var failure = new IllegalStateException("synchronous veto");
    doThrow(failure).when(enforcement).validate(isNull(), any());
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> notifier.notifyPostBusinessEvent(new SavingsDepositBusinessEvent(null))));
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> notifier.notifyPostBusinessEvent(new SavingsWithdrawalBusinessEvent(null))));
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                notifier.notifyPostBusinessEvent(
                    new SavingsAccountForceWithdrawalBusinessEvent(null))));
    verify(enforcement).validate(null, ProductTransactionPolicyData.Operation.DEPOSIT);
    verify(enforcement).validate(null, ProductTransactionPolicyData.Operation.WITHDRAWAL);
    verify(enforcement).validate(null, ProductTransactionPolicyData.Operation.FORCED_WITHDRAWAL);
    verifyNoInteractions(external);
  }
}
