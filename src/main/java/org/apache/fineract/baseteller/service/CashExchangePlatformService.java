package org.apache.fineract.baseteller.service;

import org.apache.fineract.baseteller.data.CashExchangeContextData;
import org.apache.fineract.baseteller.data.CashExchangeData;
import org.apache.fineract.baseteller.data.CashExchangeInventoryData;
import org.apache.fineract.baseteller.data.CashExchangePreviewData;
import org.apache.fineract.baseteller.data.CashExchangeRequest;

public interface CashExchangePlatformService {
  CashExchangeContextData context();

  CashExchangeInventoryData denominations(Long cashierId, String currencyCode);

  CashExchangePreviewData preview(CashExchangeRequest request);

  CashExchangeData create(CashExchangeRequest request);

  CashExchangeData retrieve(Long id);

  CashExchangeData receipt(Long id);
}
