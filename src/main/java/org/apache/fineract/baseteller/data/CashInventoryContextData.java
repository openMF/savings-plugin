package org.apache.fineract.baseteller.data;

import java.util.List;

public record CashInventoryContextData(
    List<CashInventoryCustodianData> custodians,
    List<CashInventoryTransactionTypeData> transactionTypes,
    List<CashInventoryCurrencyData> currencies) {}
