package org.apache.fineract.baseteller.data;

import java.util.List;

public record TransactionHistoryContextData(
    List<TransactionHistoryTellerData> tellers,
    List<TransactionHistoryCurrencyData> currencies,
    List<TransactionHistoryOptionData> statuses,
    List<TransactionHistoryOptionData> types,
    List<TransactionHistoryOptionData> operations,
    List<TransactionHistoryOptionData> concepts) {}
