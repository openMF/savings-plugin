package org.apache.fineract.baseteller.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerCustomerData;
import org.apache.fineract.baseteller.data.BaseTellerFundingType;
import org.apache.fineract.baseteller.data.BaseTellerOpeningReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerOpeningStatus;
import org.apache.fineract.baseteller.service.BaseTellerReadPlatformService;
import org.apache.fineract.baseteller.service.BaseTellerWritePlatformService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;

class BaseTellerSavingsOpeningApiResourceTest {

  @Test
  void customerSearchRequiresBaseTellerReadPermissionAndDelegates() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final BaseTellerReadPlatformService readService = mock(BaseTellerReadPlatformService.class);
    final BaseTellerWritePlatformService writeService = mock(BaseTellerWritePlatformService.class);
    final List<BaseTellerCustomerData> customers = List.of();
    when(context.authenticatedUser()).thenReturn(user);
    when(readService.searchCustomers(1L, null, "Ada", 10)).thenReturn(customers);

    final BaseTellerSavingsOpeningApiResource resource =
        new BaseTellerSavingsOpeningApiResource(context, readService, writeService);

    assertSame(customers, resource.searchCustomers(1L, null, "Ada", 10));

    verify(user).validateHasReadPermission("BASE_TELLER_SAVINGS_OPENING");
    verify(readService).searchCustomers(1L, null, "Ada", 10);
  }

  @Test
  void openSavingsAccountParsesJsonRequiresPermissionAndDelegates() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final BaseTellerReadPlatformService readService = mock(BaseTellerReadPlatformService.class);
    final BaseTellerWritePlatformService writeService = mock(BaseTellerWritePlatformService.class);
    final BaseTellerOpeningReceiptData receipt =
        new BaseTellerOpeningReceiptData(
            "BTSA-1",
            BaseTellerOpeningStatus.COMPLETED,
            null,
            11L,
            "Ada Lovelace",
            33L,
            "000000033",
            22L,
            "Savings",
            44L,
            BaseTellerFundingType.CHECK,
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
            List.of(),
            null);
    when(context.authenticatedUser()).thenReturn(user);
    when(writeService.openSavingsAccount(org.mockito.ArgumentMatchers.any())).thenReturn(receipt);

    final BaseTellerSavingsOpeningApiResource resource =
        new BaseTellerSavingsOpeningApiResource(context, readService, writeService);
    final String json =
        "{\"idempotencyKey\":\"idem-1\",\"clientId\":11,\"productId\":22,"
            + "\"savingsAccount\":{\"clientId\":11,\"productId\":22},"
            + "\"initialFunding\":{\"type\":\"CHECK\",\"amount\":10,"
            + "\"currencyCode\":\"USD\",\"paymentTypeId\":2,"
            + "\"check\":{\"checkType\":\"PERSONAL\",\"bank\":\"ABC\","
            + "\"checkNumber\":\"123\"}}}";

    assertSame(receipt, resource.openSavingsAccount(json));

    verify(user).validateHasCreatePermission("BASE_TELLER_SAVINGS_OPENING");
    verify(writeService).openSavingsAccount(org.mockito.ArgumentMatchers.any());
  }
}
