package org.apache.fineract.baseteller.service;

import org.apache.fineract.baseteller.data.CashAllocationContextData;
import org.apache.fineract.baseteller.data.CashAllocationPreviewData;
import org.apache.fineract.baseteller.data.CashAllocationReceiptData;
import org.apache.fineract.baseteller.data.CashAllocationRequest;

public interface CashAllocationReadPlatformService {
  CashAllocationContextData context(Long officeId, String currencyCode);

  CashAllocationPreviewData preview(CashAllocationRequest request);

  CashAllocationReceiptData retrieve(Long allocationId);

  CashAllocationReceiptData reprint(Long allocationId);
}
