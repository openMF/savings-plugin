package org.apache.fineract.baseteller.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.CashAllocationContextData;
import org.apache.fineract.baseteller.data.CashAllocationPreviewData;
import org.apache.fineract.baseteller.data.CashAllocationReceiptData;
import org.apache.fineract.baseteller.data.CashAllocationType;
import org.apache.fineract.baseteller.data.CashManagementStatus;
import org.apache.fineract.baseteller.service.CashAllocationReadPlatformService;
import org.apache.fineract.baseteller.service.CashAllocationWritePlatformService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;

class CashAllocationsApiResourceTest {

  @Test
  void contextRequiresReadPermission() {
    final Fixture fixture = fixture();
    final CashAllocationContextData result =
        new CashAllocationContextData(null, 1L, "Office", "CRC", BigDecimal.ZERO, List.of(), List.of());
    when(fixture.read.context(1L, "CRC")).thenReturn(result);

    assertSame(result, fixture.resource.allocationContext(1L, "CRC"));
    verify(fixture.user).validateHasReadPermission("BASE_TELLER_CASH_ALLOCATION");
  }

  @Test
  void previewParsesRequestAndDoesNotPost() {
    final Fixture fixture = fixture();
    final CashAllocationPreviewData result =
        new CashAllocationPreviewData(
            CashAllocationType.SAFE_VAULT_OPENING,
            null,
            1L,
            "CRC",
            "Opening contra",
            "Vault",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ONE,
            List.of());
    when(fixture.read.preview(any())).thenReturn(result);

    assertSame(result, fixture.resource.preview(json()));
    verify(fixture.user).validateHasReadPermission("BASE_TELLER_CASH_ALLOCATION");
    verify(fixture.read).preview(any());
  }

  @Test
  void allocationRequiresCreatePermission() {
    final Fixture fixture = fixture();
    final CashAllocationReceiptData result =
        new CashAllocationReceiptData(
            1L,
            "CA-1",
            CashAllocationType.SAFE_VAULT_OPENING,
            CashManagementStatus.COMPLETED,
            null,
            1L,
            "Office",
            1L,
            "user",
            null,
            "Opening contra",
            null,
            "Vault",
            "CRC",
            BigDecimal.ONE,
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ONE,
            null,
            null,
            "accounting-1",
            null,
            null,
            null,
            List.of());
    when(fixture.write.allocate(any())).thenReturn(result);

    assertSame(result, fixture.resource.allocate(json()));
    verify(fixture.user).validateHasCreatePermission("BASE_TELLER_CASH_ALLOCATION");
    verify(fixture.write).allocate(any());
  }

  @Test
  void retrievalAndReprintUseDistinctPermissions() {
    final Fixture fixture = fixture();
    fixture.resource.retrieve(7L);
    fixture.resource.reprint(7L);

    verify(fixture.user).validateHasReadPermission("BASE_TELLER_CASH_ALLOCATION");
    verify(fixture.user).validateHasPermissionTo("REPRINT_BASE_TELLER_CASH_ALLOCATION");
    verify(fixture.read).retrieve(7L);
    verify(fixture.read).reprint(7L);
  }

  private static String json() {
    return "{\"idempotencyKey\":\"opening-1\",\"operationType\":\"SAFE_VAULT_OPENING\","
        + "\"officeId\":1,\"businessDate\":\"2026-09-24\",\"currencyCode\":\"CRC\","
        + "\"amount\":100,\"denominations\":[{\"denominationId\":\"bill-100\",\"quantity\":1}]}";
  }

  private static Fixture fixture() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final CashAllocationReadPlatformService read = mock(CashAllocationReadPlatformService.class);
    final CashAllocationWritePlatformService write = mock(CashAllocationWritePlatformService.class);
    when(context.authenticatedUser()).thenReturn(user);
    return new Fixture(user, read, write, new CashAllocationsApiResource(context, read, write));
  }

  private record Fixture(
      AppUser user,
      CashAllocationReadPlatformService read,
      CashAllocationWritePlatformService write,
      CashAllocationsApiResource resource) {}
}
