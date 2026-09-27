package org.apache.fineract.baseteller.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.baseteller.data.CreditPaymentReceiptData;
import org.apache.fineract.baseteller.service.CreditPaymentReadPlatformService;
import org.apache.fineract.baseteller.service.CreditPaymentWritePlatformService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreditPaymentsApiResourceTest {

  @Mock private PlatformSecurityContext context;
  @Mock private AppUser user;
  @Mock private CreditPaymentReadPlatformService readService;
  @Mock private CreditPaymentWritePlatformService writeService;
  private CreditPaymentsApiResource resource;

  @BeforeEach
  void setUp() {
    when(context.authenticatedUser()).thenReturn(user);
    resource = new CreditPaymentsApiResource(context, readService, writeService);
  }

  @Test
  void customerSearchRequiresReadPermission() {
    when(readService.searchCustomers(1L, null, null, 20)).thenReturn(List.of());
    resource.customers(1L, null, null, 20);
    verify(user).validateHasReadPermission("BASE_TELLER_CREDIT_PAYMENT");
    verify(readService).searchCustomers(1L, null, null, 20);
  }

  @Test
  void clearRequiresBackendAuthorizationPermission() {
    final CreditPaymentReceiptData receipt = null;
    when(writeService.clear(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
        .thenReturn(receipt);
    resource.clear(7L, "{\"idempotencyKey\":\"clear-1\"}");
    verify(user).validateHasPermissionTo("AUTHORIZE_BASE_TELLER_CHECK_CLEARING");
  }

  @Test
  void returnRequiresDedicatedPermission() {
    resource.returnCheck(7L, "{\"idempotencyKey\":\"return-1\",\"reason\":\"NSF\"}");
    verify(user).validateHasPermissionTo("RETURN_BASE_TELLER_CREDIT_PAYMENT_CHECK");
  }

  @Test
  void receiptRequiresReprintPermission() {
    resource.receipt("CP-1");
    verify(user).validateHasPermissionTo("REPRINT_BASE_TELLER_CREDIT_PAYMENT");
  }
}
