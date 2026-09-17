package org.apache.fineract.baseteller.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckSearchData;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckStatus;
import org.apache.fineract.baseteller.service.BaseTellerReadPlatformService;
import org.apache.fineract.baseteller.service.BaseTellerWritePlatformService;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;

class BaseTellerReturnedChecksApiResourceTest {

  @Test
  void searchRequiresReturnedCheckReadPermissionAndDelegates() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final BaseTellerReadPlatformService readService = mock(BaseTellerReadPlatformService.class);
    final BaseTellerWritePlatformService writeService = mock(BaseTellerWritePlatformService.class);
    final Page<BaseTellerReturnedCheckSearchData> page = new Page<>(List.of(), 0);
    when(context.authenticatedUser()).thenReturn(user);
    when(readService.searchReturnedChecks("2026-09-16", "Ada", 7L, "USD", null, null, null, null, 0, 25))
        .thenReturn(page);

    final BaseTellerReturnedChecksApiResource resource =
        new BaseTellerReturnedChecksApiResource(context, readService, writeService);

    assertSame(page, resource.search("2026-09-16", "Ada", 7L, "USD", null, null, null, null, 0, 25));

    verify(user).validateHasReadPermission("BASE_TELLER_RETURNED_CHECK_PAYMENT");
    verify(readService).searchReturnedChecks("2026-09-16", "Ada", 7L, "USD", null, null, null, null, 0, 25);
  }

  @Test
  void settleRequiresReturnedCheckCreatePermissionAndDelegates() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final BaseTellerReadPlatformService readService = mock(BaseTellerReadPlatformService.class);
    final BaseTellerWritePlatformService writeService = mock(BaseTellerWritePlatformService.class);
    final BaseTellerReturnedCheckReceiptData receipt = receipt();
    when(context.authenticatedUser()).thenReturn(user);
    when(writeService.settleReturnedCheck(org.mockito.ArgumentMatchers.eq(99L), any()))
        .thenReturn(receipt);

    final BaseTellerReturnedChecksApiResource resource =
        new BaseTellerReturnedChecksApiResource(context, readService, writeService);
    final String json =
        "{\"idempotencyKey\":\"idem-1\",\"cashReceived\":125,"
            + "\"currencyCode\":\"USD\",\"paymentTypeId\":1,"
            + "\"denominations\":[{\"denominationId\":\"100\",\"value\":100,\"quantity\":1},"
            + "{\"denominationId\":\"25\",\"value\":25,\"quantity\":1}]}";

    assertSame(receipt, resource.settle(99L, json));

    verify(user).validateHasCreatePermission("BASE_TELLER_RETURNED_CHECK_PAYMENT");
    verify(writeService).settleReturnedCheck(org.mockito.ArgumentMatchers.eq(99L), any());
  }

  private static BaseTellerReturnedCheckReceiptData receipt() {
    return new BaseTellerReturnedCheckReceiptData(
        "BTRC-1",
        BaseTellerReturnedCheckStatus.SETTLED,
        null,
        99L,
        88L,
        "CHK-1",
        11L,
        "Ada Lovelace",
        null,
        null,
        null,
        "USD",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of());
  }
}
