package org.apache.fineract.baseteller.service;

import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerCustomerData;
import org.apache.fineract.baseteller.data.BaseTellerCustomerPositionData;
import org.apache.fineract.baseteller.data.BaseTellerDepositReceiptData;

public interface BaseTellerReadPlatformService {

  List<BaseTellerCustomerData> searchCustomers(
      Long clientId, String accountNo, String query, Integer limit);

  BaseTellerCustomerPositionData retrieveCustomerPosition(Long clientId);

  BaseTellerDepositReceiptData retrieveDepositReceipt(String receiptNumber);
}
