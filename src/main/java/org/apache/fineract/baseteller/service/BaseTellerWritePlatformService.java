package org.apache.fineract.baseteller.service;

import org.apache.fineract.baseteller.data.BaseTellerDepositReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerDepositRequest;

public interface BaseTellerWritePlatformService {

  BaseTellerDepositReceiptData deposit(BaseTellerDepositRequest request);
}
