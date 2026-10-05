package org.apache.fineract.baseteller.service;

import java.util.List;
import org.apache.fineract.baseteller.data.CashInventoryContextData;
import org.apache.fineract.baseteller.data.CashInventoryData;

public interface CashInventoryReadPlatformService {
  CashInventoryContextData context();

  List<CashInventoryData> inventory(
      String custodianKey,
      String transactionType,
      String currencyCode,
      boolean showLastCutOff);
}
