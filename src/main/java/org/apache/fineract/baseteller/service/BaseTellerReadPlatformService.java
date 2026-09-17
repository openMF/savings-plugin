package org.apache.fineract.baseteller.service;

import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerCustomerData;
import org.apache.fineract.baseteller.data.BaseTellerCustomerPositionData;
import org.apache.fineract.baseteller.data.BaseTellerDepositReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerOpeningReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckDetailData;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckSearchData;
import org.apache.fineract.baseteller.data.BaseTellerSavingsProductData;
import org.apache.fineract.infrastructure.core.service.Page;

public interface BaseTellerReadPlatformService {

  List<BaseTellerCustomerData> searchCustomers(
      Long clientId, String accountNo, String query, Integer limit);

  BaseTellerCustomerPositionData retrieveCustomerPosition(Long clientId);

  List<BaseTellerSavingsProductData> retrieveSavingsProducts(String currencyCode);

  BaseTellerOpeningReceiptData retrieveOpeningReceipt(String receiptNumber);

  BaseTellerDepositReceiptData retrieveDepositReceipt(String receiptNumber);

  Page<BaseTellerReturnedCheckSearchData> searchReturnedChecks(
      String returnedOnDate,
      String customerName,
      Long tellerId,
      String currencyCode,
      String checkNumber,
      Long clientId,
      Long officeId,
      String status,
      Integer offset,
      Integer limit);

  BaseTellerReturnedCheckDetailData retrieveReturnedCheck(Long returnedCheckId);

  BaseTellerReturnedCheckReceiptData retrieveReturnedCheckReceipt(String receiptNumber);
}
