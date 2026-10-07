package org.apache.fineract.baseteller.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.baseteller.data.TransactionHistoryContextData;
import org.apache.fineract.baseteller.data.TransactionHistoryDenominationsData;
import org.apache.fineract.baseteller.data.TransactionHistoryDetailData;
import org.apache.fineract.baseteller.data.TransactionHistoryReceiptData;
import org.apache.fineract.baseteller.data.TransactionHistoryReportData;
import org.apache.fineract.baseteller.data.TransactionHistorySearchData;
import org.apache.fineract.baseteller.service.TransactionHistoryReadPlatformService;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;

class TransactionHistoryApiResourceTest {

  @Test
  void allEndpointsRequireDedicatedReadPermissionAndDelegate() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final TransactionHistoryReadPlatformService service =
        mock(TransactionHistoryReadPlatformService.class);
    final TransactionHistoryApiResource resource =
        new TransactionHistoryApiResource(context, service);
    when(context.authenticatedUser()).thenReturn(user);

    final TransactionHistoryContextData contextData =
        new TransactionHistoryContextData(
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    final TransactionHistorySearchData searchData =
        new TransactionHistorySearchData(List.of(), 0, 0, 25, List.of());
    final TransactionHistoryDetailData detailData =
        new TransactionHistoryDetailData(
            "SERVICE_PAYMENT:1",
            null,
            null,
            "PAY_SERVICE",
            "POWER",
            "SP-1",
            null,
            "COMPLETED",
            "CRC",
            2,
            null,
            null,
            null,
            null,
            null,
            null,
            true);
    final TransactionHistoryDenominationsData denominationData =
        new TransactionHistoryDenominationsData("SERVICE_PAYMENT:1", false, List.of(), false, List.of());
    final TransactionHistoryReceiptData receiptData =
        new TransactionHistoryReceiptData("SERVICE_PAYMENT:1", "SERVICE_PAYMENT", 1L, null);
    final TransactionHistoryReportData reportData =
        new TransactionHistoryReportData(null, List.of(), 0, List.of());
    when(service.context()).thenReturn(contextData);
    when(service.search(any())).thenReturn(searchData);
    when(service.detail("SERVICE_PAYMENT:1")).thenReturn(detailData);
    when(service.denominations("SERVICE_PAYMENT:1")).thenReturn(denominationData);
    when(service.receipt("SERVICE_PAYMENT:1")).thenReturn(receiptData);
    when(service.report(any())).thenReturn(reportData);

    assertSame(contextData, resource.context());
    assertSame(
        searchData,
        resource.search(
            "2026-01-01",
            "2026-12-31",
            1L,
            "CRC",
            "COMPLETED",
            "CASH",
            "PAY_SERVICE",
            "POWER",
            "receipt",
            0,
            25,
            "transactionDate",
            "DESC"));
    assertSame(detailData, resource.detail("SERVICE_PAYMENT:1"));
    assertSame(denominationData, resource.denominations("SERVICE_PAYMENT:1"));
    assertSame(receiptData, resource.receipt("SERVICE_PAYMENT:1"));
    assertSame(
        reportData,
        resource.report(
            null, null, null, null, null, null, null, null, null, null, null));

    verify(user, org.mockito.Mockito.times(6))
        .validateHasPermissionTo("READ_BASE_TELLER_TRANSACTION_HISTORY");
    verify(service).search(any());
    verify(service).report(any());
  }

  @Test
  void invalidDateIsRejectedBeforeServiceInvocation() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    when(context.authenticatedUser()).thenReturn(mock(AppUser.class));
    final TransactionHistoryApiResource resource =
        new TransactionHistoryApiResource(
            context, mock(TransactionHistoryReadPlatformService.class));

    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () ->
            resource.search(
                "07-10-2026",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));
  }
}
