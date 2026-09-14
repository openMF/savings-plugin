package org.apache.fineract.baseteller.service;

import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerCustomerData;
import org.apache.fineract.baseteller.data.BaseTellerCustomerPositionData;
import org.apache.fineract.baseteller.data.BaseTellerDepositReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerOpeningReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerSavingsProductData;

public interface BaseTellerReadPlatformService {

  List<BaseTellerCustomerData> searchCustomers(
      Long clientId, String accountNo, String query, Integer limit);

  BaseTellerCustomerPositionData retrieveCustomerPosition(Long clientId);

  List<BaseTellerSavingsProductData> retrieveSavingsProducts(String currencyCode);

  BaseTellerOpeningReceiptData retrieveOpeningReceipt(String receiptNumber);

  BaseTellerDepositReceiptData retrieveDepositReceipt(String receiptNumber);
}
