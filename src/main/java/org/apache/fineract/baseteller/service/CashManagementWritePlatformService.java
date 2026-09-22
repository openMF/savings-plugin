package org.apache.fineract.baseteller.service;

import org.apache.fineract.baseteller.data.CashOperationData;
import org.apache.fineract.baseteller.data.CashOperationRequest;
import org.apache.fineract.baseteller.data.CashierClosingReceiptData;
import org.apache.fineract.baseteller.data.CashierClosingRequest;

public interface CashManagementWritePlatformService {
  CashierClosingReceiptData close(CashierClosingRequest request);

  CashOperationData createOperation(CashOperationRequest request);
}
