package org.apache.fineract.baseteller.service;

import org.apache.fineract.baseteller.data.CashAllocationReceiptData;
import org.apache.fineract.baseteller.data.CashAllocationRequest;

public interface CashAllocationWritePlatformService {
  CashAllocationReceiptData allocate(CashAllocationRequest request);
}
