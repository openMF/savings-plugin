package org.apache.fineract.baseteller.withdrawal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;

class WithdrawalAuthorizationTest {
  @Test
  void createRequiresBothPluginAndNativeWithdrawalPermissionsBeforeReadingFinancialState() {
    var security = mock(PlatformSecurityContext.class);
    var actor = mock(AppUser.class);
    when(security.authenticatedUser()).thenReturn(actor);
    doThrow(new IllegalStateException("denied"))
        .when(actor)
        .validateHasPermissionTo("WITHDRAWAL_SAVINGSACCOUNT");
    var service =
        new WithdrawalPlatformService(
            security, null, null, null, null, null, null, null, null, null);
    assertThrows(IllegalStateException.class, () -> service.preview(null));
    verify(actor).validateHasPermissionTo("CREATE_BASE_TELLER_WITHDRAWAL");
    verify(actor).validateHasPermissionTo("WITHDRAWAL_SAVINGSACCOUNT");
  }
}
