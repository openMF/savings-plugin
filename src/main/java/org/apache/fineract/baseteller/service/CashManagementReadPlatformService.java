package org.apache.fineract.baseteller.service;

import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.baseteller.data.CashHoldingData;
import org.apache.fineract.baseteller.data.CashManagementStatus;
import org.apache.fineract.baseteller.data.CashOperationData;
import org.apache.fineract.baseteller.data.CashOperationType;
import org.apache.fineract.baseteller.data.CashierClosingContextData;
import org.apache.fineract.baseteller.data.CashierClosingReceiptData;
import org.apache.fineract.baseteller.data.GlobalCashCountData;
import org.apache.fineract.infrastructure.core.service.Page;

public interface CashManagementReadPlatformService {
  CashierClosingContextData closingContext(Long cashierId, String currencyCode, LocalDate businessDate);

  CashierClosingReceiptData closing(Long id);

  GlobalCashCountData globalSettlementContext(LocalDate businessDate, String currencyCode);

  Page<CashOperationData> transactionHistory(
      LocalDate fromDate,
      LocalDate toDate,
      Long cashierId,
      String currencyCode,
      CashManagementStatus status,
      CashOperationType transactionType,
      String query,
      Integer offset,
      Integer limit);

  List<CashHoldingData> cashHoldings(LocalDate businessDate, Long cashierId, String currencyCode);
}
