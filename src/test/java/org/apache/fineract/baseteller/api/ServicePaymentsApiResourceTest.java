package org.apache.fineract.baseteller.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.baseteller.data.ServicePaymentPayerType;
import org.apache.fineract.baseteller.data.ServicePaymentReceiptData;
import org.apache.fineract.baseteller.data.ServicePaymentServiceData;
import org.apache.fineract.baseteller.service.ServicePaymentReadPlatformService;
import org.apache.fineract.baseteller.service.ServicePaymentWritePlatformService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;

class ServicePaymentsApiResourceTest {

  @Test
  void serviceCatalogRequiresReadPermission() {
    final Fixture fixture = fixture();
    final List<ServicePaymentServiceData> services = List.of();
    when(fixture.read.services()).thenReturn(services);

    assertSame(services, fixture.resource.services());
    verify(fixture.user).validateHasReadPermission("BASE_TELLER_SERVICE_PAYMENT");
    verify(fixture.read).services();
  }

  @Test
  void quoteParsesJsonAndDelegatesWithoutPosting() {
    final Fixture fixture = fixture();
    fixture.resource.quote(
        "{\"payerType\":\"NON_CLIENT\",\"payerName\":\"Ada\","
            + "\"serviceId\":3,\"serviceReference\":\"INV-42\",\"baseAmount\":100,\"currencyCode\":\"CRC\"}");

    verify(fixture.user).validateHasReadPermission("BASE_TELLER_SERVICE_PAYMENT");
    verify(fixture.read).quote(any());
  }

  @Test
  void createRequiresCreatePermissionAndDelegates() {
    final Fixture fixture = fixture();
    final ServicePaymentReceiptData receipt = receipt();
    when(fixture.write.create(any())).thenReturn(receipt);

    assertSame(
        receipt,
        fixture.resource.create(
            "{\"idempotencyKey\":\"idem-1\","
                + "\"payerType\":\"NON_CLIENT\",\"payerName\":\"Ada\",\"serviceId\":3,"
                + "\"serviceReference\":\"INV-42\",\"baseAmount\":100,\"currencyCode\":\"CRC\","
                + "\"businessDate\":\"2026-09-22\",\"paymentTypeId\":1,\"denominations\":[]}"));
    verify(fixture.user).validateHasCreatePermission("BASE_TELLER_SERVICE_PAYMENT");
    verify(fixture.write).create(any());
  }

  @Test
  void receiptReprintOnlyReadsExistingTransaction() {
    final Fixture fixture = fixture();
    final ServicePaymentReceiptData receipt = receipt();
    when(fixture.read.receipt(17L)).thenReturn(receipt);

    assertSame(receipt, fixture.resource.reprint(17L));
    verify(fixture.read).receipt(17L);
    verify(fixture.user).validateHasReadPermission("BASE_TELLER_SERVICE_PAYMENT");
  }

  private static Fixture fixture() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final ServicePaymentReadPlatformService read = mock(ServicePaymentReadPlatformService.class);
    final ServicePaymentWritePlatformService write = mock(ServicePaymentWritePlatformService.class);
    when(context.authenticatedUser()).thenReturn(user);
    return new Fixture(user, read, write, new ServicePaymentsApiResource(context, read, write));
  }

  private static ServicePaymentReceiptData receipt() {
    return new ServicePaymentReceiptData(
        17L,
        "SP-17",
        "COMPLETED",
        null,
        1L,
        "Office",
        2L,
        "Teller",
        3L,
        "Cashier",
        4L,
        "operator",
        ServicePaymentPayerType.NON_CLIENT,
        null,
        null,
        "Ada",
        5L,
        "UTILITY",
        "Utility",
        "INV-42",
        null,
        null,
        null,
        null,
        null,
        null,
        "CRC",
        null,
        null,
        null,
        null,
        List.of());
  }

  private record Fixture(
      AppUser user,
      ServicePaymentReadPlatformService read,
      ServicePaymentWritePlatformService write,
      ServicePaymentsApiResource resource) {}
}
