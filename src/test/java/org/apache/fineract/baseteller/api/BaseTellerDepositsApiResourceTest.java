package org.apache.fineract.baseteller.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerCustomerData;
import org.apache.fineract.baseteller.data.BaseTellerDepositReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerDepositStatus;
import org.apache.fineract.baseteller.data.BaseTellerFundingType;
import org.apache.fineract.baseteller.service.BaseTellerReadPlatformService;
import org.apache.fineract.baseteller.service.BaseTellerWritePlatformService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;

class BaseTellerDepositsApiResourceTest {

  @Test
  void customerSearchRequiresBaseTellerReadPermissionAndDelegates() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final BaseTellerReadPlatformService readService = mock(BaseTellerReadPlatformService.class);
    final BaseTellerWritePlatformService writeService = mock(BaseTellerWritePlatformService.class);
    final List<BaseTellerCustomerData> customers = List.of();
    when(context.authenticatedUser()).thenReturn(user);
    when(readService.searchCustomers(1L, null, "Ada", 10)).thenReturn(customers);

    final BaseTellerDepositsApiResource resource =
        new BaseTellerDepositsApiResource(context, readService, writeService);

    assertSame(customers, resource.searchCustomers(1L, null, "Ada", 10));

    verify(user).validateHasReadPermission("BASE_TELLER_DEPOSIT");
    verify(readService).searchCustomers(1L, null, "Ada", 10);
  }

  @Test
  void depositParsesJsonRequiresPermissionAndDelegates() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final BaseTellerReadPlatformService readService = mock(BaseTellerReadPlatformService.class);
    final BaseTellerWritePlatformService writeService = mock(BaseTellerWritePlatformService.class);
    final BaseTellerDepositReceiptData receipt = receipt();
    when(context.authenticatedUser()).thenReturn(user);
    when(writeService.deposit(org.mockito.ArgumentMatchers.any())).thenReturn(receipt);

    final BaseTellerDepositsApiResource resource =
        new BaseTellerDepositsApiResource(context, readService, writeService);
    final String json =
        "{\"idempotencyKey\":\"idem-1\",\"clientId\":11,\"savingsAccountId\":33,"
            + "\"funding\":{\"type\":\"CASH\",\"amount\":10,"
            + "\"currencyCode\":\"USD\",\"paymentTypeId\":1,"
            + "\"denominations\":[{\"denominationId\":\"10\","
            + "\"value\":10,\"quantity\":1}]}}";

    assertSame(receipt, resource.deposit(json));

    verify(user).validateHasCreatePermission("BASE_TELLER_DEPOSIT");
    verify(writeService).deposit(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void retrieveReceiptRequiresPermissionAndDelegates() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final BaseTellerReadPlatformService readService = mock(BaseTellerReadPlatformService.class);
    final BaseTellerWritePlatformService writeService = mock(BaseTellerWritePlatformService.class);
    final BaseTellerDepositReceiptData receipt = receipt();
    when(context.authenticatedUser()).thenReturn(user);
    when(readService.retrieveDepositReceipt("BTD-1")).thenReturn(receipt);

    final BaseTellerDepositsApiResource resource =
        new BaseTellerDepositsApiResource(context, readService, writeService);

    assertSame(receipt, resource.retrieveReceipt("BTD-1"));

    verify(user).validateHasReadPermission("BASE_TELLER_DEPOSIT");
    verify(readService).retrieveDepositReceipt("BTD-1");
  }

  private static BaseTellerDepositReceiptData receipt() {
    return new BaseTellerDepositReceiptData(
        "BTD-1",
        BaseTellerDepositStatus.COMPLETED,
        null,
        11L,
        "Ada Lovelace",
        33L,
        "000000033",
        22L,
        "Savings",
        44L,
        BaseTellerFundingType.CASH,
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
        List.of());
  }
}
