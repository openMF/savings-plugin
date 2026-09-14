package org.apache.fineract.baseteller.service;

import org.apache.fineract.baseteller.data.BaseTellerDepositReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerDepositRequest;
import org.apache.fineract.baseteller.data.BaseTellerOpeningReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerSavingsOpeningRequest;

public interface BaseTellerWritePlatformService {

  BaseTellerOpeningReceiptData openSavingsAccount(BaseTellerSavingsOpeningRequest request);

  BaseTellerDepositReceiptData deposit(BaseTellerDepositRequest request);
}
