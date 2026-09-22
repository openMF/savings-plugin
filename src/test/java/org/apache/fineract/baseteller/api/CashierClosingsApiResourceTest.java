package org.apache.fineract.baseteller.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.baseteller.data.CashManagementStatus;
import org.apache.fineract.baseteller.data.CashDifferenceType;
import org.apache.fineract.baseteller.data.CashierClosingContextData;
import org.apache.fineract.baseteller.data.CashierClosingReceiptData;
import org.apache.fineract.baseteller.service.CashManagementReadPlatformService;
import org.apache.fineract.baseteller.service.CashManagementWritePlatformService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;

class CashierClosingsApiResourceTest {

  @Test
  void contextRequiresNarrowReadPermission() {
    final Fixture fixture = fixture();
    final CashierClosingContextData value =
        new CashierClosingContextData(
            LocalDate.of(2026, 9, 22),
            1L,
            "HQ",
            2L,
            "Main",
            3L,
            "Ada",
            "USD",
            BigDecimal.TEN,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("11"),
            List.of(),
            CashManagementStatus.OPEN);
    when(fixture.readService.closingContext(3L, "USD", LocalDate.of(2026, 9, 22)))
        .thenReturn(value);

    assertSame(value, fixture.resource.context(3L, "USD", "2026-09-22"));

    verify(fixture.user).validateHasPermissionTo("READ_CASHIER_CLOSING");
  }

  @Test
  void closeParsesBusinessDateAndRequiresCreatePermission() {
    final Fixture fixture = fixture();
    final CashierClosingReceiptData receipt =
        new CashierClosingReceiptData(
            1L,
            "CCR-1",
            LocalDate.of(2026, 9, 22),
            1L,
            "HQ",
            2L,
            "Main",
            3L,
            "Ada",
            "USD",
            new BigDecimal("40"),
            BigDecimal.ZERO,
            new BigDecimal("40"),
            new BigDecimal("40"),
            BigDecimal.ZERO,
            CashDifferenceType.BALANCED,
            5L,
            "supervisor",
            null,
            CashManagementStatus.COMPLETED,
            List.of(),
            List.of());
    when(fixture.writeService.close(any())).thenReturn(receipt);
    final String json =
        "{\"idempotencyKey\":\"close-1\",\"cashierId\":3,\"businessDate\":\"2026-09-22\","
            + "\"currencyCode\":\"USD\",\"denominations\":[{\"denominationId\":\"20\","
            + "\"value\":20,\"quantity\":2}],\"checkIds\":[]}";

    assertSame(receipt, fixture.resource.close(json));

    verify(fixture.user).validateHasPermissionTo("CREATE_CASHIER_CLOSING");
    verify(fixture.writeService).close(any());
  }

  private static Fixture fixture() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final CashManagementReadPlatformService readService = mock(CashManagementReadPlatformService.class);
    final CashManagementWritePlatformService writeService = mock(CashManagementWritePlatformService.class);
    when(context.authenticatedUser()).thenReturn(user);
    return new Fixture(
        user,
        readService,
        writeService,
        new CashierClosingsApiResource(context, readService, writeService));
  }

  private record Fixture(
      AppUser user,
      CashManagementReadPlatformService readService,
      CashManagementWritePlatformService writeService,
      CashierClosingsApiResource resource) {}
}
